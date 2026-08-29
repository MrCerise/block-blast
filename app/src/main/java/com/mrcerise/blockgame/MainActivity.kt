package com.mrcerise.blockgame

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // If the previous run crashed, surface the stack trace first.
        val crash = CrashReporter.read(this)
        if (crash != null) {
            CrashReporter.clear(this)
            showCrashDialog(crash)
            return
        }

        startGame()
    }

    private fun startGame() {
        val view = GameView(this)
        // Keep the view on the hardware pipeline. LAYER_TYPE_NONE means "no off-screen
        // layer at all" -- LAYER_TYPE_HARDWARE would still force a full-screen buffer,
        // and LAYER_TYPE_SOFTWARE (used previously) forced every frame to be rasterized
        // on the CPU into a full-screen bitmap and re-uploaded to the GPU.
        view.setLayerType(View.LAYER_TYPE_NONE, null)
        view.onDisplayPrefsChanged = { applyRefreshRate(view.maxFps) }
        setContentView(view)
        makeFullscreen()
        // the decor view needs to be attached before its Display is available
        view.post { applyRefreshRate(view.maxFps) }
    }

    /**
     * Ask the compositor for the display's fastest mode at the current resolution.
     *
     * Android presents frames at vsync, so the panel's refresh rate is a hard ceiling --
     * a View cannot draw "unlimited" frames, and spinning faster would only burn battery
     * on frames that are never shown. What this does buy is real: many devices run apps
     * at 60Hz by default even on 90/120Hz panels, and this lifts the game to the panel's
     * maximum. Modes are filtered to the current resolution so requesting a faster mode
     * can never silently drop the render resolution.
     */
    private fun applyRefreshRate(max: Boolean) {
        try {
            val lp = window.attributes
            val display = window.decorView.display
            lp.preferredDisplayModeId = if (max && display != null) {
                val current = display.mode
                display.supportedModes
                    .filter {
                        it.physicalWidth == current.physicalWidth &&
                            it.physicalHeight == current.physicalHeight
                    }
                    .maxByOrNull { it.refreshRate }
                    ?.modeId ?: 0
            } else {
                0   // 0 = let the system choose
            }
            window.attributes = lp
        } catch (_: Throwable) {
            // never let a display-mode request stop the game from running
        }
    }

    private fun makeFullscreen() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
                val controller = window.insetsController
                if (controller != null) {
                    controller.hide(
                        android.view.WindowInsets.Type.statusBars() or
                            android.view.WindowInsets.Type.navigationBars()
                    )
                    controller.systemBarsBehavior =
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    )
            }
        } catch (_: Throwable) {
            // Never let immersive-mode setup prevent the game from starting.
        }
    }

    private fun showCrashDialog(crash: String) {
        try {
            val scroll = android.widget.ScrollView(this)
            val tv = android.widget.TextView(this)
            tv.text = crash
            tv.textSize = 11f
            tv.setPadding(32, 24, 32, 24)
            tv.typeface = android.graphics.Typeface.MONOSPACE
            scroll.addView(tv)

            AlertDialog.Builder(this)
                .setTitle("The game crashed")
                .setMessage("Please tap Share and send this report, or tap Try Again.")
                .setView(scroll)
                .setCancelable(false)
                .setPositiveButton("Share report") { d, _ ->
                    share(crash)
                    d.dismiss()
                    startGame()
                }
                .setNeutralButton("Try again") { d, _ ->
                    d.dismiss()
                    startGame()
                }
                .show()
        } catch (_: Throwable) {
            startGame()
        }
    }

    private fun share(text: String) {
        try {
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = "text/plain"
            intent.putExtra(Intent.EXTRA_SUBJECT, "Block Blast crash report")
            intent.putExtra(Intent.EXTRA_TEXT, text)
            startActivity(Intent.createChooser(intent, "Send crash report"))
        } catch (_: Throwable) {
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) makeFullscreen()
    }
}
