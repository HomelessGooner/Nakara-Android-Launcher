package com.nakara.android.ui.overlay

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.KeyEvent
import android.widget.Button
import android.widget.LinearLayout
import org.libsdl.app2012.SDLActivity

/**
 * Full QWERTY keyboard (plain Android Views, no Compose).
 * Each key sends key down + up to SDL. Includes Shift, Space, Enter, Hide.
 */
class GameKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private var shift = false
    private val keyButtons = mutableListOf<Button>()

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.argb(217, 0, 0, 0)) // ~85% black
        val pad = (8 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)

        // Number row
        addRow(listOf(
            "1" to KeyEvent.KEYCODE_1, "2" to KeyEvent.KEYCODE_2,
            "3" to KeyEvent.KEYCODE_3, "4" to KeyEvent.KEYCODE_4,
            "5" to KeyEvent.KEYCODE_5, "6" to KeyEvent.KEYCODE_6,
            "7" to KeyEvent.KEYCODE_7, "8" to KeyEvent.KEYCODE_8,
            "9" to KeyEvent.KEYCODE_9, "0" to KeyEvent.KEYCODE_0,
        ), isAlpha = false)

        // QWERTY row
        addRow(listOf(
            "Q" to KeyEvent.KEYCODE_Q, "W" to KeyEvent.KEYCODE_W,
            "E" to KeyEvent.KEYCODE_E, "R" to KeyEvent.KEYCODE_R,
            "T" to KeyEvent.KEYCODE_T, "Y" to KeyEvent.KEYCODE_Y,
            "U" to KeyEvent.KEYCODE_U, "I" to KeyEvent.KEYCODE_I,
            "O" to KeyEvent.KEYCODE_O, "P" to KeyEvent.KEYCODE_P,
        ), isAlpha = true)

        // ASDF row
        addRow(listOf(
            "A" to KeyEvent.KEYCODE_A, "S" to KeyEvent.KEYCODE_S,
            "D" to KeyEvent.KEYCODE_D, "F" to KeyEvent.KEYCODE_F,
            "G" to KeyEvent.KEYCODE_G, "H" to KeyEvent.KEYCODE_H,
            "J" to KeyEvent.KEYCODE_J, "K" to KeyEvent.KEYCODE_K,
            "L" to KeyEvent.KEYCODE_L,
        ), isAlpha = true)

        // ZXCV row with Shift and Backspace
        val zxcvRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        val shiftBtn = createKeyButton("⇪", 1.5f)
        shiftBtn.setOnClickListener {
            shift = !shift
            shiftBtn.text = if (shift) "⇧" else "⇪"
            refreshLabels()
        }
        zxcvRow.addView(shiftBtn)
        for ((label, code) in listOf(
            "Z" to KeyEvent.KEYCODE_Z, "X" to KeyEvent.KEYCODE_X,
            "C" to KeyEvent.KEYCODE_C, "V" to KeyEvent.KEYCODE_V,
            "B" to KeyEvent.KEYCODE_B, "N" to KeyEvent.KEYCODE_N,
            "M" to KeyEvent.KEYCODE_M,
        )) {
            val btn = createKeyButton(label, 1f)
            btn.tag = code to true // alpha key
            btn.setOnClickListener { sendKey(code, shift) }
            keyButtons.add(btn)
            zxcvRow.addView(btn)
        }
        val delBtn = createKeyButton("⌫", 1.5f)
        delBtn.setOnClickListener { tapKey(KeyEvent.KEYCODE_DEL) }
        zxcvRow.addView(delBtn)
        addView(zxcvRow)

        // Bottom row: Space, Enter, Hide
        val bottomRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        val spaceBtn = createKeyButton("Space", 5f)
        spaceBtn.setOnClickListener { tapKey(KeyEvent.KEYCODE_SPACE) }
        bottomRow.addView(spaceBtn)

        val enterBtn = createKeyButton("Enter", 2f)
        enterBtn.setOnClickListener { tapKey(KeyEvent.KEYCODE_ENTER) }
        bottomRow.addView(enterBtn)

        val hideBtn = createKeyButton("Hide", 2f)
        hideBtn.setOnClickListener { visibility = GONE }
        bottomRow.addView(hideBtn)
        addView(bottomRow)
    }

    private fun addRow(keys: List<Pair<String, Int>>, isAlpha: Boolean) {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        for ((label, code) in keys) {
            val btn = createKeyButton(if (isAlpha && !shift) label.lowercase() else label, 1f)
            if (isAlpha) {
                btn.tag = code to true
                keyButtons.add(btn)
            }
            btn.setOnClickListener { sendKey(code, shift) }
            row.addView(btn)
        }
        addView(row)
    }

    private fun createKeyButton(label: String, weight: Float): Button {
        return Button(context).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(0, (48 * resources.displayMetrics.density).toInt(), weight).apply {
                val m = (2 * resources.displayMetrics.density).toInt()
                setMargins(m, m, m, m)
            }
            setBackgroundColor(Color.argb(204, 64, 64, 64))
            setTextColor(Color.WHITE)
        }
    }

    private fun refreshLabels() {
        for (btn in keyButtons) {
            val (code, _) = btn.tag as Pair<Int, Boolean>
            // Find the label from the button's original text (stored in tag would be better,
            // but we use the keycode to derive it)
            val label = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
            btn.text = if (shift) label else label.lowercase()
        }
    }

    private fun sendKey(code: Int, useShift: Boolean) {
        if (useShift) SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT)
        SDLActivity.onNativeKeyDown(code)
        SDLActivity.onNativeKeyUp(code)
        if (useShift) SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT)
    }

    private fun tapKey(code: Int) {
        SDLActivity.onNativeKeyDown(code)
        SDLActivity.onNativeKeyUp(code)
    }
}
