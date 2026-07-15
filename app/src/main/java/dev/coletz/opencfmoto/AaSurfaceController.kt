package dev.coletz.opencfmoto

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import dev.coletz.opencfmoto.aa.AaInput

/**
 * Wires a [SurfaceView] to the live Android Auto session: displays the AA video (a second output of
 * the AA compositor, see [VideoPipeline.setPreview]) and forwards the rider's touches into AA (via
 * [AaInput]). Shared by the compact inline surface in [MainActivity] and the fullscreen
 * [ControlActivity]. Only one preview can be attached at a time — the owning screen calls
 * [pause]/[resume] around foreground transitions so the two never fight over it.
 */
class AaSurfaceController(private val surfaceView: SurfaceView) {

    private var attachedSurface: Surface? = null
    private var viewW = 0
    private var viewH = 0

    private val pipeline: VideoPipeline? get() = AaVideoBridge.pipeline

    @SuppressLint("ClickableViewAccessibility")
    fun attach() {
        // Preview attach/detach is driven purely by the surface lifecycle. Detach is ownership-checked
        // in the compositor (by Surface identity), so a late surfaceDestroyed from a view being torn
        // down during a fullscreen handoff can't clobber the preview a new view just attached.
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                attachedSurface = holder.surface
                viewW = width; viewH = height
                tryAttach(0)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                attachedSurface?.let { pipeline?.detachPreview(it) }
                attachedSurface = null
            }
        })
        surfaceView.setOnTouchListener { _, e -> onTouch(e) }
    }

    /**
     * Attach the preview once the video pipeline exists. The inline surface is laid out the instant
     * Android Auto is tapped — before [AndroidAutoService] has created the pipeline — so the first
     * attach can land with pipeline==null. Retry (up to ~6s) until it's up instead of showing blank.
     */
    private fun tryAttach(attempt: Int) {
        val surface = attachedSurface ?: return          // surface went away — stop
        val p = pipeline
        if (p != null) {
            p.attachPreview(surface, viewW, viewH)
        } else if (attempt < 24) {
            surfaceView.postDelayed({ tryAttach(attempt + 1) }, 250)
        }
    }

    /**
     * Force a fresh attach if the surface is currently valid — call from lifecycle points where the
     * pipeline definitely exists (activity resume, AA video live). Attach is ownership-checked in the
     * compositor so re-attaching is always safe. Covers cases where the surfaceChanged callback fired
     * at an awkward moment (e.g. during the Wi-Fi-join dialog) and the initial attach was missed.
     */
    fun reattach() {
        val s = surfaceView.holder.surface
        if (s == null || !s.isValid) return
        attachedSurface = s
        if (surfaceView.width > 0) viewW = surfaceView.width
        if (surfaceView.height > 0) viewH = surfaceView.height
        if (viewW > 0 && viewH > 0) tryAttach(0)
    }

    private fun onTouch(e: MotionEvent): Boolean {
        val p = pipeline ?: return false
        if (!AaInput.isReady) return false
        val r = p.previewRect() ?: return false     // [x, y, w, h] in view pixels
        val rx = r[0]; val ry = r[1]; val rw = r[2]; val rh = r[3]
        if (rw == 0 || rh == 0) return false
        val vx = e.x - rx
        val vy = e.y - ry
        if (vx < 0 || vy < 0 || vx > rw || vy > rh) return true   // in a letterbox bar
        val aa = BikeProfileHolder.active.aaVideo
        val ax = (vx / rw * aa.width).toInt().coerceIn(0, aa.width - 1)
        val ay = (vy / rh * aa.height).toInt().coerceIn(0, aa.height - 1)
        val action = when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> AaInput.Action.DOWN
            MotionEvent.ACTION_MOVE -> AaInput.Action.MOVE
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> AaInput.Action.UP
            else -> return true
        }
        AaInput.sendTouch(ax, ay, action)
        return true
    }
}
