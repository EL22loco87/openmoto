package dev.coletz.opencfmoto

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Fullscreen, landscape Android Auto control view — reached from the compact inline surface's
 * fullscreen toggle. Shows the live AA video filling the screen (touch to control) with a slim bar
 * to return to the app. The tabbed/d-pad control lives inline in [MainActivity]; this screen is
 * purely the big touch view.
 */
class ControlActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (AaVideoBridge.pipeline == null) {
            Toast.makeText(this, "Start Android Auto first", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Match the fullscreen orientation to the bike's panel so portrait dashes (e.g. the 1000 MT-X,
        // 720x1280) aren't forced into a letterboxed landscape view.
        val aa = BikeProfileHolder.active.aaVideo
        requestedOrientation =
            if (aa.width >= aa.height) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContentView(R.layout.activity_control)

        AaSurfaceController(findViewById<SurfaceView>(R.id.aa_surface)).attach()
        findViewById<Button>(R.id.btn_exit_fs).setOnClickListener { finish() }
        applyImmersive(true)
    }

    private fun applyImmersive(on: Boolean) {
        WindowCompat.setDecorFitsSystemWindows(window, !on)
        val c = WindowInsetsControllerCompat(window, window.decorView)
        if (on) {
            c.hide(WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
