package com.nakara.android.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import org.libsdl.app2012.SDLActivity

/**
 * Tracks the combined SDL mouse button state across all GameButtonViews.
 * The native Android_OnMouse uses a single static last_state; if two buttons
 * are pressed, the state bitmask must reflect BOTH, or releases get lost.
 *
 * If the Java state and native state desync (e.g., a release is lost),
 * [reset] forces both back to zero by sending an UP with empty state.
 *
 * All methods are synchronized to prevent race conditions from rapid taps.
 */
private object MouseButtonState {
    // Android button bits: 1 = BUTTON_PRIMARY (left), 2 = BUTTON_SECONDARY (right)
    private var state: Int = 0
    @Synchronized fun press(bit: Int): Int { state = state or bit; return state }
    @Synchronized fun release(bit: Int): Int { state = state and bit.inv(); return state }
    @Synchronized fun reset() {
        state = 0
        // ACTION_UP with empty state forces native last_state = 0.
        SDLActivity.onNativeMouse(0, MotionEvent.ACTION_UP, 0f, 0f, true)
    }
    @Synchronized fun getState(): Int = state
}

/**
 * Circular game button (plain Android View, no Compose).
 * Sends key down on press, key up on release to SDL.
 *
 * Set [mouseButton] to 1 (left) or 2 (right) to send SDL mouse button events
 * instead of keyboard keys — used for L/R combat buttons (sword = left click,
 * magnet = right click). Events are sent relative with zero delta so the view
 * does not teleport; only the button state matters to the engine.
 *
 * Set [autoHoldMs] > 0 for a timed hold: pressing sends button down, and it
 * is automatically released after [autoHoldMs] milliseconds (press-and-forget).
 */
class GameButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var label: String = ""
        set(value) { field = value; invalidate() }
    var keyCode: Int = 0
    /** 0 = key mode; 1 = left mouse button; 2 = right mouse button. */
    var mouseButton: Int = 0
    var isPrimary: Boolean = false
        set(value) { field = value; invalidate() }
    /** If > 0, button is auto-released this many ms after press (timed hold). */
    var autoHoldMs: Long = 0L

    private var holdReleaseRunnable: Runnable? = null

    private fun pressDown() {
        // FAILSAFE: For mouse buttons, send BOTH the mouse event AND a keyboard
        // key event. The key is bound to the same action via autoexec.cfg
        // (J=+attack for L, K=+use for R). If the mouse event gets lost in
        // the native layer, the key event still triggers the action. This
        // dual-path redundancy guarantees the button works.
        if (mouseButton == 1) {
            // Android BUTTON_PRIMARY bit -> SDL left button.
            // Relative with zero delta: no view movement, only the click.
            val s = MouseButtonState.press(1)
            SDLActivity.onNativeMouse(s, MotionEvent.ACTION_DOWN, 0f, 0f, true)
            // Failsafe: also send the key event.
            if (keyCode != 0) {
                try { SDLActivity.onNativeKeyDown(keyCode) } catch (_: Exception) {}
            }
        } else if (mouseButton == 2) {
            // Android BUTTON_SECONDARY bit -> SDL right button.
            val s = MouseButtonState.press(2)
            SDLActivity.onNativeMouse(s, MotionEvent.ACTION_DOWN, 0f, 0f, true)
            // Failsafe: also send the key event.
            if (keyCode != 0) {
                try { SDLActivity.onNativeKeyDown(keyCode) } catch (_: Exception) {}
            }
        } else {
            SDLActivity.onNativeKeyDown(keyCode)
        }
    }

    private fun releaseUp() {
        if (mouseButton == 1) {
            val s = MouseButtonState.release(1)
            SDLActivity.onNativeMouse(s, MotionEvent.ACTION_UP, 0f, 0f, true)
            // Failsafe: also release the key.
            if (keyCode != 0) {
                try { SDLActivity.onNativeKeyUp(keyCode) } catch (_: Exception) {}
            }
        } else if (mouseButton == 2) {
            val s = MouseButtonState.release(2)
            SDLActivity.onNativeMouse(s, MotionEvent.ACTION_UP, 0f, 0f, true)
            // Failsafe: also release the key.
            if (keyCode != 0) {
                try { SDLActivity.onNativeKeyUp(keyCode) } catch (_: Exception) {}
            }
        } else {
            SDLActivity.onNativeKeyUp(keyCode)
        }
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(153, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = (width.coerceAtMost(height) / 2f) * 0.95f

        bgPaint.color = if (isPrimary) Color.argb(179, 76, 175, 80) else Color.argb(102, 0, 0, 0)
        canvas.drawCircle(cx, cy, radius, bgPaint)
        canvas.drawCircle(cx, cy, radius, borderPaint)

        textPaint.textSize = radius * 0.8f
        val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, cx, textY, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                pressDown()
                // Timed hold: auto-release after autoHoldMs.
                if (autoHoldMs > 0) {
                    holdReleaseRunnable?.let { removeCallbacks(it) }
                    val r = Runnable { releaseUp() }
                    holdReleaseRunnable = r
                    postDelayed(r, autoHoldMs)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                // For timed hold, let the timer do the release (press-and-forget).
                if (autoHoldMs <= 0) {
                    releaseUp()
                }
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                // ALWAYS release on cancel, even for timed-hold buttons.
                // A lost release desyncs the native button state permanently.
                holdReleaseRunnable?.let { removeCallbacks(it) }
                holdReleaseRunnable = null
                releaseUp()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        // Force-release if the view is destroyed while pressed.
        // Prevents permanent native state desync.
        holdReleaseRunnable?.let { removeCallbacks(it) }
        holdReleaseRunnable = null
        if (mouseButton == 1 || mouseButton == 2) {
            releaseUp()
        } else if (keyCode != 0) {
            try { SDLActivity.onNativeKeyUp(keyCode) } catch (_: Exception) {}
        }
        super.onDetachedFromWindow()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    companion object {
        /** Resets the shared mouse button state (Java + native). Call when
         * buttons are shown/hidden to prevent permanent desync. */
        @JvmStatic
        fun resetMouseState() {
            MouseButtonState.reset()
        }
    }
}
