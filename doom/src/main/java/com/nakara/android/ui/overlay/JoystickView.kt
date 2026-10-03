package com.nakara.android.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import org.libsdl.app2012.SDLActivity
import kotlin.math.sqrt

/**
 * Virtual joystick view (plain Android View, no Compose).
 * Dragging sends DPAD key events (Up/Down/Left/Right) to SDL.
 */
class JoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(77, 0, 0, 0) // ~30% black
        style = Paint.Style.FILL
    }
    private val baseBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(128, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(153, 255, 255, 255) // ~60% white
        style = Paint.Style.FILL
    }
    private val knobBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private var knobX = 0f
    private var knobY = 0f
    private var activeKeys = setOf<Int>()

    // Configurable key mappings. Default is DPAD (movement).
    // For look stick, use turn/look keys.
    var keyUp: Int = KeyEvent.KEYCODE_DPAD_UP
    var keyDown: Int = KeyEvent.KEYCODE_DPAD_DOWN
    var keyLeft: Int = KeyEvent.KEYCODE_DPAD_LEFT
    var keyRight: Int = KeyEvent.KEYCODE_DPAD_RIGHT

    // When true, sends relative mouse motion instead of keys (for looking).
    // This is the proper twin-stick approach: the stick emulates mouse movement.
    var mouseLookMode: Boolean = false
    // Sensitivity multiplier for mouse look
    var mouseSensitivity: Float = 8f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = (width.coerceAtMost(height) / 2f) * 0.95f
        val knobRadius = baseRadius * 0.4f

        // Base
        canvas.drawCircle(cx, cy, baseRadius, basePaint)
        canvas.drawCircle(cx, cy, baseRadius, baseBorderPaint)
        // Knob
        canvas.drawCircle(cx + knobX, cy + knobY, knobRadius, knobPaint)
        canvas.drawCircle(cx + knobX, cy + knobY, knobRadius, knobBorderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cx = width / 2f
        val cy = height / 2f
        val maxRadius = (width.coerceAtMost(height) / 2f) * 0.55f

        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                var dx = event.x - cx
                var dy = event.y - cy
                val dist = sqrt(dx * dx + dy * dy)
                if (dist > maxRadius) {
                    val scale = maxRadius / dist
                    dx *= scale
                    dy *= scale
                }
                knobX = dx
                knobY = dy
                updateKeys(dx, dy, maxRadius)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                knobX = 0f
                knobY = 0f
                for (key in activeKeys) SDLActivity.onNativeKeyUp(key)
                activeKeys = emptySet()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateKeys(dx: Float, dy: Float, maxRadius: Float) {
        if (mouseLookMode) {
            // Send relative mouse motion for looking.
            // dx/dy are -maxRadius to maxRadius; scale by sensitivity.
            // In relative mode, SDL treats x/y as motion deltas.
            val moveX = (dx / maxRadius) * mouseSensitivity
            val moveY = (dy / maxRadius) * mouseSensitivity
            // Only send if there's significant movement (deadzone)
            val deadzone = 0.15f
            if (kotlin.math.abs(dx / maxRadius) > deadzone || kotlin.math.abs(dy / maxRadius) > deadzone) {
                SDLActivity.onNativeMouse(0, MotionEvent.ACTION_MOVE, moveX, moveY, true)
            }
            return
        }

        val deadzone = maxRadius * 0.3f
        val newKeys = mutableSetOf<Int>()
        if (dx > deadzone) newKeys.add(keyRight)
        if (dx < -deadzone) newKeys.add(keyLeft)
        if (dy > deadzone) newKeys.add(keyDown)
        if (dy < -deadzone) newKeys.add(keyUp)

        for (key in activeKeys - newKeys) SDLActivity.onNativeKeyUp(key)
        for (key in newKeys - activeKeys) SDLActivity.onNativeKeyDown(key)
        activeKeys = newKeys
    }
}
