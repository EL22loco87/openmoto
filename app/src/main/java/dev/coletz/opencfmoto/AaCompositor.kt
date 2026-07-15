package dev.coletz.opencfmoto

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * GPU letterbox compositor for the Android Auto → bike video path.
 *
 * The AA video decoder renders into [inputSurface] (backed by a [SurfaceTexture]). Each decoded
 * frame is drawn — aspect-preserved and centered, on a black background — into the encoder's input
 * surface (set later via [setOutput], once the bike tells us its canvas size). This decouples the
 * AA source resolution (e.g. portrait 720x1280) from the bike canvas (e.g. 800x944): the source no
 * longer gets stretched to fill a different-shaped canvas — it's letterboxed.
 *
 * [inputSurface] exists immediately (before the bike connects) so AA can reach steady video, which
 * is what triggers the bike hand-off in the first place. Until [setOutput] is called the render
 * thread just drains decoded frames (keeps AA flowing) without drawing anywhere.
 *
 * All GL work happens on a dedicated thread with the EGL context current. Based on the standard
 * SurfaceTexture→encoder pattern (Grafika).
 */
class AaCompositor(private val log: (String) -> Unit) {

    private val thread = HandlerThread("aa-compositor").apply { start() }
    private val handler = Handler(thread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE   // keeps a current surface before output exists
    private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    // Optional second output: an on-screen preview for the in-app AA control surface. Same texture,
    // drawn a second time into the app's SurfaceView so the rider can see AND touch the AA UI.
    private var previewSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    // The Surface currently attached as preview; used to reject stale detach calls (see detachPreview).
    @Volatile private var previewOwner: Surface? = null
    @Volatile private var previewW = 0
    @Volatile private var previewH = 0
    private var previewSrcW = 0
    private var previewSrcH = 0
    // The letterboxed rect the AA image occupies inside the preview view, in VIEW pixels (top-left
    // origin — symmetric centering makes this valid from either origin). The control surface reads
    // these to map a finger touch back to AA coordinates. 0 until a preview is set.
    @Volatile var pvpX = 0
        private set
    @Volatile var pvpY = 0
        private set
    @Volatile var pvpW = 0
        private set
    @Volatile var pvpH = 0
        private set

    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uTexMatrix = 0
    private var textureId = 0
    private lateinit var surfaceTexture: SurfaceTexture

    /** Where the AA decoder renders. Valid after [start]. */
    @Volatile var inputSurface: Surface? = null
        private set

    // Output canvas (bike) + source (AA) dims; viewport is derived from these.
    @Volatile private var canvasW = 0
    @Volatile private var canvasH = 0
    @Volatile private var srcW = 0
    @Volatile private var srcH = 0
    @Volatile private var vpX = 0
    @Volatile private var vpY = 0
    @Volatile private var vpW = 0
    @Volatile private var vpH = 0

    private val texMatrix = FloatArray(16)

    // Preview throttle: cap the on-screen preview to ~22fps so it can't starve the decoder drain.
    private var lastPreviewMs = 0L
    private val PREVIEW_MIN_INTERVAL_MS = 45L

    // Idle keepalive: Android Auto only sends video on change, so on a static screen (e.g. parked)
    // it stops emitting frames — onFrame() stops firing, the encoder goes silent, and the bike's
    // media socket times out (~9s, its socketTimeoutPeriodWifi) and drops the connection. Re-present
    // the last decoded frame at a low floor rate so the dash stays fed and the picture holds. It
    // self-gates: during active streaming onFrame() keeps lastEncoderDrawMs fresh, so this never
    // double-draws; it only fires once the live path has gone quiet.
    private var lastEncoderDrawMs = 0L
    private var lastFrameValid = false
    private var lastPresentedPtsNs = 0L
    private val KEEPALIVE_INTERVAL_MS = 500L   // ~2 fps floor, well under the bike's ~9s socket timeout
    private val keepalive = object : Runnable {
        override fun run() {
            if (windowSurface != EGL14.EGL_NO_SURFACE && lastFrameValid &&
                android.os.SystemClock.uptimeMillis() - lastEncoderDrawMs >= KEEPALIVE_INTERVAL_MS
            ) {
                // No updateTexImage() — no new frame arrived; re-present the one the encoder last saw,
                // with an advanced timestamp so the encoder treats it as a fresh (all-IDR) frame.
                lastPresentedPtsNs += KEEPALIVE_INTERVAL_MS * 1_000_000L
                renderTo(windowSurface, vpX, vpY, vpW, vpH, preview = false, ptsNs = lastPresentedPtsNs)
                lastEncoderDrawMs = android.os.SystemClock.uptimeMillis()
            }
            handler.postDelayed(this, KEEPALIVE_INTERVAL_MS)
        }
    }

    // Full-screen quad (triangle strip): pos.xy + tex.uv interleaved.
    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(
                -1f, -1f, 0f, 0f,
                 1f, -1f, 1f, 0f,
                -1f,  1f, 0f, 1f,
                 1f,  1f, 1f, 1f,
            ))
            position(0)
        }

    fun start() {
        val latch = java.util.concurrent.CountDownLatch(1)
        handler.post {
            try {
                initEgl()
                initGl()
                surfaceTexture = SurfaceTexture(textureId)
                surfaceTexture.setOnFrameAvailableListener({ handler.post { onFrame() } }, handler)
                inputSurface = Surface(surfaceTexture)
                log("[COMPOSITOR] ready — AA decoder input surface up (no output canvas yet)")
                handler.post(keepalive)   // idle keepalive; no-ops until an output canvas is set
            } catch (e: Exception) {
                log("[COMPOSITOR] init failed: $e")
            } finally {
                latch.countDown()
            }
        }
        latch.await()
    }

    /** Point the compositor at the encoder's input surface, sized to the bike canvas. */
    fun setOutput(encoderSurface: Surface, cw: Int, ch: Int, sw: Int, sh: Int) {
        handler.post {
            try {
                if (windowSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, windowSurface)
                    windowSurface = EGL14.EGL_NO_SURFACE
                }
                val attrs = intArrayOf(EGL14.EGL_NONE)
                windowSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, encoderSurface, attrs, 0)
                canvasW = cw; canvasH = ch; srcW = sw; srcH = sh
                computeViewport()
                log("[COMPOSITOR] output set canvas=${cw}x$ch src=${sw}x$sh → letterbox rect=${vpW}x$vpH @($vpX,$vpY)")
            } catch (e: Exception) {
                log("[COMPOSITOR] setOutput failed: $e")
            }
        }
    }

    /** Fill width first, then fit height (crop letterbox instead of black bars on sides). */
    private fun computeViewport() {
        if (canvasW == 0 || canvasH == 0 || srcW == 0 || srcH == 0) return
        
        // Start by filling the full width
        vpW = canvasW
        vpH = Math.round(canvasW * srcH.toFloat() / srcW)
        
        // If height overflows, fit to height instead
        if (vpH > canvasH) {
            vpH = canvasH
            vpW = Math.round(canvasH * srcW.toFloat() / srcH)
        }
        
        vpX = (canvasW - vpW) / 2
        vpY = (canvasH - vpH) / 2
    }

    /**
     * Attach an on-screen preview output (the in-app control surface) so the same decoded AA frame
     * is also drawn into [surface], letterboxed to the AA aspect inside a [viewW]×[viewH] view.
     * [sourceW]/[sourceH] are the AA projection dims (profile aaVideo). Takes ownership: [surface]
     * becomes the current preview owner, so a late detach from a *previous* surface can't clobber it.
     */
    fun attachPreview(surface: Surface, viewW: Int, viewH: Int, sourceW: Int, sourceH: Int) {
        handler.post {
            try {
                if (previewSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)
                    EGL14.eglDestroySurface(eglDisplay, previewSurface)
                    previewSurface = EGL14.EGL_NO_SURFACE
                }
                val attrs = intArrayOf(EGL14.EGL_NONE)
                previewSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, attrs, 0)
                previewOwner = surface
                previewW = viewW; previewH = viewH; previewSrcW = sourceW; previewSrcH = sourceH
                computePreviewViewport()
                log("[COMPOSITOR] preview set view=${viewW}x$viewH src=${sourceW}x$sourceH → rect=${pvpW}x$pvpH @($pvpX,$pvpY)")
            } catch (e: Exception) {
                log("[COMPOSITOR] attachPreview failed: $e")
            }
        }
    }

    /**
     * Detach the preview — but ONLY if [surface] is the one currently attached. During a fullscreen
     * handoff two views briefly coexist and their surfaceDestroyed callbacks fire out of order; this
     * ownership check stops a dying view from tearing down the preview a new view just attached.
     */
    fun detachPreview(surface: Surface) {
        handler.post {
            if (surface !== previewOwner) return@post   // stale detach from a previous owner — ignore
            try {
                if (previewSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)
                    EGL14.eglDestroySurface(eglDisplay, previewSurface)
                    previewSurface = EGL14.EGL_NO_SURFACE
                }
            } catch (_: Exception) {}
            previewOwner = null
            pvpX = 0; pvpY = 0; pvpW = 0; pvpH = 0
            log("[COMPOSITOR] preview detached")
        }
    }

    /** Letterbox the AA source aspect inside the preview view (same fit logic as [computeViewport]). */
    private fun computePreviewViewport() {
        if (previewW == 0 || previewH == 0 || previewSrcW == 0 || previewSrcH == 0) return
        val srcAspect = previewSrcW.toFloat() / previewSrcH
        val viewAspect = previewW.toFloat() / previewH
        if (srcAspect < viewAspect) {
            pvpH = previewH
            pvpW = Math.round(previewH * srcAspect)
        } else {
            pvpW = previewW
            pvpH = Math.round(previewW / srcAspect)
        }
        pvpX = (previewW - pvpW) / 2
        pvpY = (previewH - pvpH) / 2
    }

    private fun onFrame() {
        try {
            surfaceTexture.updateTexImage()
        } catch (e: Exception) {
            return
        }
        // Draw to whichever outputs exist. Neither yet → just drain (keeps AA video flowing so it
        // reaches steady state before the bike connects, and supports preview-before-bike).
        if (windowSurface == EGL14.EGL_NO_SURFACE && previewSurface == EGL14.EGL_NO_SURFACE) return
        surfaceTexture.getTransformMatrix(texMatrix)
        // The ENCODER path is priority — it feeds the dash and must NOT be starved. Draw it first.
        if (windowSurface != EGL14.EGL_NO_SURFACE) {
            lastPresentedPtsNs = surfaceTexture.timestamp
            renderTo(windowSurface, vpX, vpY, vpW, vpH, preview = false, ptsNs = lastPresentedPtsNs)
            // Mark the live path as active so the idle keepalive stays out of the way.
            lastEncoderDrawMs = android.os.SystemClock.uptimeMillis()
            lastFrameValid = true
        }
        // The on-screen PREVIEW is best-effort and throttled: a full-size vsync-throttled swap on this
        // (the decoder-draining) thread can stall the decoder → AA forces a restart → session teardown.
        // Cap it to ~22fps and use a non-blocking swap so it can never back up the decoder/encoder.
        if (previewSurface != EGL14.EGL_NO_SURFACE) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastPreviewMs >= PREVIEW_MIN_INTERVAL_MS) {
                lastPreviewMs = now
                renderTo(previewSurface, pvpX, pvpY, pvpW, pvpH, preview = true, ptsNs = surfaceTexture.timestamp)
            }
        }
    }

    /** Draw the current external texture into [target], letterboxed to the given GL viewport rect. */
    private fun renderTo(target: EGLSurface, vx: Int, vy: Int, vw: Int, vh: Int, preview: Boolean, ptsNs: Long) {
        EGL14.eglMakeCurrent(eglDisplay, target, target, eglContext)
        // Non-blocking swap for the preview so it never waits on vsync/BufferQueue and stalls the
        // decoder drain. The encoder surface isn't a display, so its interval is irrelevant.
        if (preview) EGL14.eglSwapInterval(eglDisplay, 0)

        // Black background (the letterbox bars).
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glViewport(vx, vy, vw, vh)
        GLES20.glUseProgram(program)

        quad.position(0)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPosition)
        quad.position(2)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTexCoord)

        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)

        EGLExt.eglPresentationTimeANDROID(eglDisplay, target, ptsNs)
        EGL14.eglSwapBuffers(eglDisplay, target)
    }

    fun release() {
        handler.removeCallbacks(keepalive)
        handler.post {
            try { inputSurface?.release() } catch (_: Exception) {}
            try { if (::surfaceTexture.isInitialized) surfaceTexture.release() } catch (_: Exception) {}
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (previewSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, previewSurface)
                if (windowSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, windowSurface)
                if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, pbuffer)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
        }
        thread.quitSafely()
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)
        val cfgAttrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfig = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, cfgAttrs, 0, configs, 0, 1, numConfig, 0)
        eglConfig = configs[0]
        val ctxAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttrs, 0)
        // 1x1 pbuffer so the context can be current before we have an output window surface.
        pbuffer = EGL14.eglCreatePbufferSurface(
            eglDisplay, eglConfig, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)
    }

    private fun initGl() {
        val vs = """
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """.trimIndent()
        val fs = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() { gl_FragColor = texture2D(sTexture, vTexCoord); }
        """.trimIndent()
        program = linkProgram(vs, fs)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        Matrix.setIdentityM(texMatrix, 0)

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun linkProgram(vsSrc: String, fsSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsSrc)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val err = GLES20.glGetProgramInfoLog(p)
            throw RuntimeException("program link failed: $err")
        }
        return p
    }

    private fun compileShader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val err = GLES20.glGetShaderInfoLog(s)
            throw RuntimeException("shader compile failed: $err")
        }
        return s
    }
}
