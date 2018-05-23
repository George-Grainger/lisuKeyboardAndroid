package com.amosgwa.lisukeyboard.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.inputmethodservice.Keyboard
import android.os.Build
import android.support.annotation.RequiresApi
import android.util.AttributeSet
import android.widget.LinearLayout
import com.amosgwa.lisukeyboard.R
import android.view.*
import android.util.DisplayMetrics
import android.util.Xml
import com.amosgwa.lisukeyboard.keyboard.CustomKey
import com.amosgwa.lisukeyboard.keyboard.CustomKeyboard

class CustomKeyboardView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr), View.OnTouchListener {
    private val renderedKeys = mutableListOf<CustomKeyPreview>()

    var currentX = 0
    var currentY = 0

    var keyTextColor: Int = 0
    var keyBackground: Drawable? = null

    var keyboard: CustomKeyboard? = null
        set(value) {
            // Create key views and add them to this view
            field = value
            value?.let { keyboard ->
                keyboard.getRows().let { rows ->
                    populateKeyViews(keyboard, rows)
                }
            }
            requestLayout()
        }

    private var keys = mutableListOf<List<CustomKey>>()
    private var keyViews = mutableListOf<CustomKeyView>()

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.CustomKeyboardView)
        /*
        * Load the styles from the keyboard xml for the child keys. Keyboard should be the only place
        * where we set the styles for the children views.
        * */
        keyTextColor = a.getColor(R.styleable.CustomKeyboardView_keyTextColor, context.getColor(R.color.default_key_text_color))
        keyBackground = a.getDrawable(R.styleable.CustomKeyboardView_keyBackground)

        // recycle the typed array
        a.recycle()

        // Set orientation for the rows
        orientation = VERTICAL

        // Clear the keys
        keys.clear()

        // Set listener to the keyboard
        setOnTouchListener(this)
    }

    private fun populateKeyViews(keyboard: Keyboard, rows: List<List<CustomKey>>) {
        val keyboardMinWidth = keyboard.minWidth
        for (row in rows) {
            val rowLinearLayout = LinearLayout(context)
            rowLinearLayout.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            rowLinearLayout.orientation = HORIZONTAL
            for (key in row) {
                val keyView = CustomKeyView(
                        context,
                        codes = key.codes,
                        textColor = keyTextColor,
                        keyBackground = keyBackground
                )
                keyView.layoutParams = LinearLayout.LayoutParams(
                        0,
                        key.height,
                        (key.width.toFloat() / keyboardMinWidth) + (key.gap / keyboardMinWidth)
                )
                // Keeps track of all of the key views
                keyViews.add(keyView)
                rowLinearLayout.addView(keyViews.last())
            }
            this.addView(rowLinearLayout)
        }
    }

    private fun getDisplayMetrics(): DisplayMetrics {
        val displayMetrics = DisplayMetrics()
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        return displayMetrics
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onTouch(v: View?, event: MotionEvent?): Boolean {
        when (event?.action) {
            MotionEvent.ACTION_DOWN -> {
                val pressedKey = CustomKeyPreview(context, height = 100)
                pressedKey.setBackgroundColor(context.resources.getColor(R.color.pink))
                pressedKey.x = currentX
                pressedKey.y = currentY
                currentX += 100
                currentY += 10

                renderPressedKey(pressedKey)
            }
            MotionEvent.ACTION_UP -> {
                clearRenderedKeys()
            }
        }
        return true
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun renderPressedKey(pressedKey: CustomKeyPreview) {
        val windowManager: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(100, 100,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSPARENT)
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        params.gravity = Gravity.TOP or Gravity.START
        params.x = pressedKey.x
        params.y = pressedKey.y
        windowManager.addView(pressedKey, params)
        renderedKeys.add(pressedKey)
    }

    private fun clearRenderedKeys() {
        val windowManager: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        renderedKeys.let {
            renderedKeys.map { keyView -> windowManager.removeView(keyView) }
            renderedKeys.clear()
        }
    }

    companion object {
        var pointers = 0
    }

    interface OnKeyboardActionListener {
        fun onPress()
        fun onKey()
        fun onRelease()
        fun onSwipe()
    }
}