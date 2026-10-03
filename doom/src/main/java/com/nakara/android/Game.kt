package com.nakara.android

import android.content.res.Configuration
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import com.nakara.android.ui.overlay.GameButtonView
import com.nakara.android.ui.overlay.GameKeyboardView
import com.nakara.android.ui.overlay.JoystickView
import org.libsdl.app2012.SDLActivity

/**
 * The gameplay activity. Since the rebase onto emileb's GZDoom 4.15 mobile
 * port this is a thin subclass of the vendored SDL2 SDLActivity: SDL owns the
 * surface and EGL context, and the engine main loop runs on the SDL thread
 * (SDLOpenTouch.RunApplication -> NativeLib.init, which never returns).
 *
 * Launch contract from the Compose launcher (unchanged): Intent extras
 * "args" (engine command line), "game_path" (Freedoom base dir),
 * "res_div" (resolution divider), "game" (selected IWAD index).
 */
class Game : SDLActivity() {

    override fun getLibraries(): Array<String> = arrayOf(
        "hidapi",
        "saffal",
        "openal",
        "zmusic_uz",
        "touchcontrols",
        "SDL2",
        "uzdoom",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Force-hide the legacy Beloko touch controls — we're replacing them with
        // our own overlay. Without this, the old panel (gamma, keyboard, etc.)
        // can still appear.
        com.beloko.touchcontrols.TouchSettings.setBoolOption(this, "hide_touch_controls", true)
        com.beloko.touchcontrols.TouchSettings.hideTouchControls = true
        // Belt-and-suspenders: the SDL surface itself must never receive touches.
        // Our overlay covers it and consumes touches, but if any touch reaches the
        // surface (e.g. during overlay setup/teardown), it could trigger the old
        // Beloko dimming. Consume everything at the surface level.
        try {
            val surfaceField = SDLActivity::class.java.getDeclaredField("mSurface")
            surfaceField.isAccessible = true
            val surface = surfaceField.get(null) as? android.view.View
            surface?.setOnTouchListener { _, _ -> true }
        } catch (_: Exception) {
            // If reflection fails, the overlay's touch consumption is the fallback.
        }
        addGameOverlay()
    }

    /**
     * Adds the Steam Link-style control overlay (joystick, buttons, keyboard)
     * on top of the SDL surface. Uses plain Android Views (no Compose) because
     * SDLActivity is not a ComponentActivity.
     */
    private fun addGameOverlay() {
        val density = resources.displayMetrics.density
        val overlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            // Consume ALL touches so they never reach the SDL surface / Beloko system.
            // Without this, touches on empty areas fall through and trigger the old
            // Beloko control panel.
            isClickable = true
            isFocusable = true
            setOnTouchListener { _, _ -> true }
        }

        // Left: virtual joystick
        val joystickSize = (160 * density).toInt()
        val joystick = JoystickView(this).apply {
            // Left stick: WASD movement
            keyUp = KeyEvent.KEYCODE_W
            keyDown = KeyEvent.KEYCODE_S
            keyLeft = KeyEvent.KEYCODE_A
            keyRight = KeyEvent.KEYCODE_D
            layoutParams = FrameLayout.LayoutParams(joystickSize, joystickSize).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                leftMargin = (32 * density).toInt()
                bottomMargin = (48 * density).toInt()
            }
        }
        overlay.addView(joystick)

        // Right: ABXY buttons (toggleable with right joystick for looking)
        val buttonSize = (64 * density).toInt()
        val buttonContainerSize = (200 * density).toInt()
        val buttonContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(buttonContainerSize, buttonContainerSize).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                rightMargin = (32 * density).toInt()
                bottomMargin = (48 * density).toInt()
            }
        }

        fun addGameButton(label: String, keyCode: Int, gravity: Int, primary: Boolean = false) {
            val size = if (primary) (72 * density).toInt() else buttonSize
            val btn = GameButtonView(this).apply {
                this.label = label
                this.keyCode = keyCode
                this.isPrimary = primary
                layoutParams = FrameLayout.LayoutParams(size, size).apply { this.gravity = gravity }
            }
            buttonContainer.addView(btn)
        }

        // Y = E: Nakara game action
        addGameButton("Y", KeyEvent.KEYCODE_E, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        // X = Space: use/jump/advance
        addGameButton("X", KeyEvent.KEYCODE_SPACE, Gravity.CENTER_VERTICAL or Gravity.START)
        // B = Escape: back/skip (reverted from mouse)
        addGameButton("B", KeyEvent.KEYCODE_ESCAPE, Gravity.CENTER_VERTICAL or Gravity.END)
        // A = Enter: start/confirm/advance (reverted from mouse, primary)
        addGameButton("A", KeyEvent.KEYCODE_ENTER, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, primary = true)
        overlay.addView(buttonContainer)

        // Gallery button (BTNS mode only): change gallery rooms.
        // Sends K directly — the game prompts "pad k to change room" and
        // waits for the K key itself. K is also bound to CloseGallerymenu
        // via autoexec.cfg as a backup (runs GalleryChecks ACS).
        // Placed to the left of the ABXY cluster, visible only when the ABXY
        // buttons are visible.
        val galleryBtn = GameButtonView(this).apply {
            label = "G"
            keyCode = KeyEvent.KEYCODE_K
            layoutParams = FrameLayout.LayoutParams(buttonSize, buttonSize).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                rightMargin = (32 * density).toInt() + buttonContainerSize + (16 * density).toInt()
                bottomMargin = (48 * density).toInt() + (buttonContainerSize / 2) - (buttonSize / 2)
            }
        }
        overlay.addView(galleryBtn)

        // Right joystick for looking (hidden by default, toggle with button)
        val lookJoystick = JoystickView(this).apply {
            // Right stick: mouse-look (twin-stick). Emulates relative mouse motion
            // for turning and looking up/down. Properly handles inversion via
            // the game's mouse settings, not hardcoded keys.
            mouseLookMode = true
            mouseSensitivity = 12f
            layoutParams = FrameLayout.LayoutParams(joystickSize, joystickSize).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                rightMargin = (32 * density).toInt()
                bottomMargin = (48 * density).toInt()
            }
            visibility = android.view.View.GONE
        }
        overlay.addView(lookJoystick)

        // Left/Right combat buttons beside the right joystick (for STICK mode).
        // Mouse-based: L = left click (sword swing), R = right click (magnet).
        // The game binds combat actions to mouse buttons, not keys, so these
        // send real SDL mouse button events via onNativeMouse.
        // Only visible when the right joystick is visible.
        val clickButtonSize = (56 * density).toInt()
        val leftClickBtn = GameButtonView(this).apply {
            label = "L"
            mouseButton = 1 // left click = sword swing
            keyCode = KeyEvent.KEYCODE_J // FAILSAFE: also sends J (+attack) if mouse fails
            isPrimary = true
            layoutParams = FrameLayout.LayoutParams(clickButtonSize, clickButtonSize).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                rightMargin = (32 * density).toInt() + joystickSize + (16 * density).toInt()
                bottomMargin = (48 * density).toInt() + (32 * density).toInt()
            }
            visibility = android.view.View.GONE
        }
        overlay.addView(leftClickBtn)

        val rightClickBtn = GameButtonView(this).apply {
            label = "R"
            mouseButton = 2 // right click = magnet ring grab
            // NOTE: No key failsafe for R — K (+use) was triggering the Y/E
            // animation due to key overlap. R is mouse-only.
            // Simple press: down on touch, up on release (no timed hold).
            layoutParams = FrameLayout.LayoutParams(clickButtonSize, clickButtonSize).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                rightMargin = (32 * density).toInt() + joystickSize + (16 * density).toInt()
                bottomMargin = (48 * density).toInt() + (32 * density).toInt() + clickButtonSize + (16 * density).toInt()
            }
            visibility = android.view.View.GONE
        }
        overlay.addView(rightClickBtn)

        // Toggle button: switch between ABXY buttons and right look joystick
        val stickToggle = android.widget.Button(this).apply {
            text = "STICK"
            textSize = 12f
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (48 * density).toInt()
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = (16 * density).toInt()
                rightMargin = (16 * density).toInt()
            }
            setBackgroundColor(android.graphics.Color.argb(128, 0, 0, 0))
            setTextColor(android.graphics.Color.WHITE)
            setOnClickListener {
                // Reset mouse button state on mode switch to prevent desync.
                GameButtonView.resetMouseState()
                if (buttonContainer.visibility == android.view.View.VISIBLE) {
                    buttonContainer.visibility = android.view.View.GONE
                    galleryBtn.visibility = android.view.View.GONE
                    lookJoystick.visibility = android.view.View.VISIBLE
                    leftClickBtn.visibility = android.view.View.VISIBLE
                    rightClickBtn.visibility = android.view.View.VISIBLE
                    text = "BTNS"
                } else {
                    buttonContainer.visibility = android.view.View.VISIBLE
                    galleryBtn.visibility = android.view.View.VISIBLE
                    lookJoystick.visibility = android.view.View.GONE
                    leftClickBtn.visibility = android.view.View.GONE
                    rightClickBtn.visibility = android.view.View.GONE
                    text = "STICK"
                }
            }
        }
        overlay.addView(stickToggle)

        // Keyboard view removed — the toggle was replaced by the USE button
        // per user request. (GameKeyboardView no longer added to overlay.)

        // NOTE: USE button temporarily removed (2026-09-29) per user request.
        // (The keyboard toggle was already removed earlier.)

        // SKIP button for cutscenes. Sends C which is bound to +crouch via
        // launch args (+bind c +crouch). The game displays "Skip C (+crouch)"
        // and the cutscene runner skips when the bound key is pressed.
        // Uses GameButtonView for consistency with the working A/B/X/Y buttons.
        val skipSize = (64 * density).toInt()
        val skip = GameButtonView(this).apply {
            label = "SKIP"
            keyCode = KeyEvent.KEYCODE_C
            layoutParams = FrameLayout.LayoutParams(skipSize, skipSize).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = (16 * density).toInt()
            }
        }
        overlay.addView(skip)

        addContentView(overlay, overlay.layoutParams)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Utils.setImmersionMode(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Utils.onWindowFocusChanged(this, hasFocus)
    }

    /**
     * Block the Android back button from opening the Doom menu.
     * The user wants focus on the game itself, not the menu system.
     */
    @Deprecated("Use onBackPressedDispatcher instead")
    override fun onBackPressed() {
        // Do nothing — consume the back press to prevent the menu from opening.
    }

    override fun onDestroy() {
        super.onDestroy()
        // The engine cannot re-initialise in the same process (static state,
        // and NativeLib.init never returns); kill the process so the next
        // launch starts clean.
        System.exit(0)
    }
}
