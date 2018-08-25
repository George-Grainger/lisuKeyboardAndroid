package com.amosgwa.lisukeyboard.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Message
import android.util.*
import android.view.*
import android.widget.LinearLayout
import com.amosgwa.lisukeyboard.R
import com.amosgwa.lisukeyboard.keyboard.CustomKey
import com.amosgwa.lisukeyboard.keyboard.CustomKeyboard
import kotlin.properties.Delegates


class CustomKeyboardView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private lateinit var keyboardHandler: Handler
    private lateinit var longClickHandler: Handler


    private var swipeThreshold: Int = 0

    // Styles
    var globalKeyTextColor: Int = 0
    var globalKeyTextSize: Float = 0.0f
    var keyBackground: Drawable? = null

    // Keyboards
    var keyboards: SparseArray<CustomKeyboard> by Delegates.observable(SparseArray()) { _, _, _ ->
        preloadKeyViews()
    }

    var currentKeyboardType: Int by Delegates.observable(0) { _, _, _ ->
        invalidate()
        addKeyViews()
    }

    // Keys
    private val pressedKeys = SparseArray<CustomKeyView>()
    private var preloadedRowsWithKeyViews = SparseArray<MutableList<MutableList<CustomKeyView>>>()

    // Preview Keys
    private lateinit var MOD_KEYS: List<Int>
    private val renderedKeys = mutableListOf<CustomKeyPreview>()

    // Listeners
    var keyboardViewListener: KeyboardActionListener? = null

    // Gesture detector
    private lateinit var gestureDetector: GestureDetector

    fun currentKeyboard(): CustomKeyboard {
        return keyboards[currentKeyboardType]
    }

    // Current screen orientation
    private var isLandscape = false

    init {
        /*
        * Load the styles from the keyboard xml for the child keys. Keyboard should be the only place
        * where we set the styles for the children views.
        * */
        val a = context.obtainStyledAttributes(attrs, R.styleable.CustomKeyboardView)
        globalKeyTextColor = a.getColor(R.styleable.CustomKeyboardView_keyTextColor, context.getColor(R.color.default_key_text_color))
        globalKeyTextSize = a.getDimension(R.styleable.CustomKeyboardView_keyTextSize, CustomKeyTextView.DEFAULT_TEXT_SIZE)
        keyBackground = a.getDrawable(R.styleable.CustomKeyboardView_keyBackground)
        // recycle the typed array
        a.recycle()
        // Set orientation for the rows
        orientation = VERTICAL
        // Determine the current screen orientation
        determineScreenMode()
        // Get list of modifier keys
        generateModKeysList()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        initGestureDetector()
        keyboardHandler = Handler(Handler.Callback { msg ->
            when (msg.what) {
                MSG_REPEAT -> {
                    val pointerId = msg.obj
                    if (pointerId is Int && sendKey(pressedKeys[pointerId])) {
                        val repeatMsg = Message.obtain(keyboardHandler, MSG_REPEAT)
                        repeatMsg.obj = pointerId
                        keyboardHandler.sendMessageDelayed(repeatMsg, REPEAT_INTERVAL.toLong())
                    }
                }
            }
            false
        })

        // A handler to send after a delayed message from a long click.
        longClickHandler = Handler(Handler.Callback { lngClkMsg ->
            when (lngClkMsg.what) {
                MSG_LONG_CLICK -> {
                    val pointerId = lngClkMsg.obj
                    val msg = keyboardHandler.obtainMessage(MSG_REPEAT)
                    msg.obj = pointerId
                    keyboardHandler.sendMessage(msg)
                }
            }
            false
        })
    }

    private fun initGestureDetector() {
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                var isChangeLanguageSwipe = 0x000
                var direction = 0
                var swipeThreshold = width / 2

                val e1PointerId = e1.getPointerId(e1.actionIndex)
                val e2PointerId = e2.getPointerId(e2.actionIndex)
                if (e1PointerId != e2PointerId) return false
                // Check if the swipe is within the area of the language switch key.
                val e1Key = detectKey(e1.getX(e1.actionIndex), e1.getY(e1.actionIndex))
                val e2Key = detectKey(e2.getX(e2.actionIndex), e2.getY(e1.actionIndex))
                if (e1Key?.isChangeLanguage == true &&
                        e2Key?.isChangeLanguage == true) {
                    isChangeLanguageSwipe = 0x001 // 0x001 for being inside the key view.
                    swipeThreshold = e1Key.width / 4
                }
                var result = false
                val distanceY = e2.y - e1.y
                val distanceX = e2.x - e1.x
                if (Math.abs(distanceX) > Math.abs(distanceY) &&
                        Math.abs(distanceX) > swipeThreshold &&
                        Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                    direction = if (distanceX > 0) {
                        keyboardViewListener?.onSwipeRight()
                        KeyboardActionListener.SWIPE_DIRECTION_RIGHT
                    } else {
                        keyboardViewListener?.onSwipeLeft()
                        KeyboardActionListener.SWIPE_DIRECTION_LEFT
                    }
                    isChangeLanguageSwipe = isChangeLanguageSwipe or 0x010 // 0x011 for both being in the change language key view and swiping right or left.
                    result = true
                } else if (Math.abs(distanceY) > height / 2 &&
                        Math.abs(velocityY) > SWIPE_VELOCITY_THRESHOLD) {
                    if (distanceY > 0) {
                        keyboardViewListener?.onSwipeDown()
                    } else {
                        keyboardViewListener?.onSwipeUp()
                    }
                    result = true
                }
                if (isChangeLanguageSwipe == 0x011) {
                    keyboardViewListener?.onChangeKeyboardSwipe(direction)
                }
                return result
            }
        })
    }

    private fun addKeyViews() {
        if (preloadedRowsWithKeyViews[currentKeyboardType].size == childCount) {
            // Use the existing row linear layouts.
            preloadedRowsWithKeyViews[currentKeyboardType].forEachIndexed { idx, row ->
                val rowLinearLayout = getChildAt(idx) as LinearLayout
                rowLinearLayout.removeAllViews()
                row.forEach { key -> rowLinearLayout.addView(key) }
            }
        } else {
            removeAllKeyViews() // Remove parents of the key views.
            removeAllViews() // Remove all of the row Linear Layout
            preloadedRowsWithKeyViews[currentKeyboardType].forEach { row ->
                val rowLinearLayout = LinearLayout(context)
                rowLinearLayout.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                rowLinearLayout.orientation = HORIZONTAL
                rowLinearLayout.gravity = Gravity.CENTER
                row.forEach { key -> rowLinearLayout.addView(key) }
                addView(rowLinearLayout)
            }
        }
    }

    /*
    * Remove the parents of the keys in order to add new keys to the view.
    * */
    private fun removeAllKeyViews() {
        preloadedRowsWithKeyViews[currentKeyboardType].forEach { row ->
            row.forEach { key ->
                val parent = key.parent as ViewGroup?
                parent?.removeView(key)
            }
        }
    }

    // Create views for each individual keys for a keyboard
    private fun getKeyViews(rows: List<List<CustomKey>>, keyboardLanguage: String): MutableList<MutableList<CustomKeyView>> {
        val keyViews = mutableListOf<MutableList<CustomKeyView>>()
        rows.forEach { row ->
            // Keep track of the row keys.
            val rowKeyViews = mutableListOf<CustomKeyView>()
            // Create row linear layout for the key views.
            row.forEach { key ->
                // The background of the key has to be duplicate since the keys have different widths.
                val keyBackgroundCopy = keyBackground?.constantState?.newDrawable()?.mutate()
                val keyView = CustomKeyView(
                        context,
                        key = key,
                        globalTextColor = globalKeyTextColor,
                        globalTextSize = globalKeyTextSize,
                        isLandscape = isLandscape,
                        globalKeyBackground = keyBackgroundCopy
                )
                // Update the language for the key that is assigned with isChange
                if (key.isChangeLanguageKey == true) {
                    swipeThreshold = key.width / 2
                    keyView.updateLabel(keyboardLanguage)
                }
                // Keeps track of all of the key views
                rowKeyViews.add(keyView)
            }

            // Key tracks of the rows with key views.
            keyViews.add(rowKeyViews.toMutableList())
            rowKeyViews.clear()
        }
        return keyViews
    }

    private fun preloadKeyViews() {
        preloadedRowsWithKeyViews.clear()
        for (i in 0 until keyboards.size()) {
            val key = keyboards.keyAt(i)
            val kbd = keyboards.get(key)
            kbd.getRows().let { rows ->
                val timings = TimingLogger(LOG_TAG, "preloadKeyViews")
                preloadedRowsWithKeyViews.append(key, getKeyViews(rows, kbd.language))
                timings.addSplit("preloadKeyViews")
                timings.dumpToLog()
            }
        }
    }

    /*
    * Returns the size of the display.
    * */
    private fun getDisplayMetrics(): DisplayMetrics {
        val displayMetrics = DisplayMetrics()
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        return displayMetrics
    }

    private fun determineScreenMode() {
        val displayMetrics = getDisplayMetrics()
        isLandscape = displayMetrics.heightPixels < displayMetrics.widthPixels
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onInterceptTouchEvent(event: MotionEvent?): Boolean {
        if (event == null) {
            return true
        }
        return processTouchEvent(event)
    }

    private fun processTouchEvent(event: MotionEvent): Boolean {
        if (!gestureDetector.onTouchEvent(event)) {
            val pointerIndex = event.actionIndex
            val pointerId = event.getPointerId(pointerIndex)
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    // Check if the pointer is moved out of range for the key.
                    // If so, remove it.
                    val key = detectKey(event.getX(pointerIndex), event.getY(pointerIndex))
                    if (key != pressedKeys[pointerId]) {
                        pressedKeys.remove(pointerId)
                    }
                }
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN -> {
                    detectKey(event.getX(pointerIndex), event.getY(pointerIndex))?.let { pressedKey ->
                        val timings = TimingLogger(LOG_TAG, "populateKeyViews")
                        addPressedKey(pointerId, pressedKey)
                        if (pressedKey.repeatable == true) {
                            /*
                            * Determine if the click is a long press on the repeatable keys.
                            * */
                            val msg = longClickHandler.obtainMessage(MSG_LONG_CLICK)
                            msg.obj = pointerId
                            longClickHandler.sendMessageDelayed(msg, LONG_PRESS_DELAY.toLong())
                        }
                        timings.addSplit("Action Down")
                        timings.dumpToLog()
                    }
                    return false
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_POINTER_UP -> {
                    sendKey(pressedKeys.get(pointerId))
                    pressedKeys.remove(pointerId)
                    removeMessages()
                    return false
                }
            }
        }
        return false
    }

    private fun addPressedKey(id: Int, keyView: CustomKeyView) {
        pressedKeys.append(id, keyView)
        renderKeyPreview(keyView)
    }

    private fun removeMessages() {
        longClickHandler.removeMessages(MSG_LONG_CLICK)
        keyboardHandler.removeMessages(MSG_REPEAT)
    }

    /*
    * Key views are stored in 2d format. First, we check if the tap position is within the parent of
    * the key view's bounds. If so, find the key in that row.
    * */
    private fun detectKey(x: Float, y: Float): CustomKeyView? {
        preloadedRowsWithKeyViews[currentKeyboardType].forEach { row ->
            // Each row is composed in linear layout. Thus, we have to use it to find which row
            // the pointer falls into.
            val rowLinearLayout = row.first().parent as LinearLayout?
            rowLinearLayout?.let {
                if (x in rowLinearLayout.left..rowLinearLayout.right &&
                        y in rowLinearLayout.top..rowLinearLayout.bottom) {
                    // Normalize the tap location because the position of the children view are
                    // relative to the parent's.
                    row.forEach { key ->
                        if (x - rowLinearLayout.left in key.left..key.right &&
                                y - rowLinearLayout.top in key.top..key.bottom) {
                            return key
                        }
                    }
                }
            }
        }
        return null
    }

    private fun sendKey(key: CustomKeyView?): Boolean {
        key?.codes?.let { codes ->
            if (codes.isEmpty()) return false
            codes.first().let { primaryCode ->
                Log.d(LOG_TAG, "pressed : ${key.label}")
                val timings = TimingLogger("", "populateKeyViews")
                keyboardViewListener?.onKey(primaryCode, codes)
                timings.addSplit("Keyboard listener called")
                timings.dumpToLog()
                return true
            }
        }
        return false
    }

    private fun renderKeyPreview(pressedKey: CustomKeyView) {
        pressedKey.key?.let { key ->
            // If the key isn't any of the modifying key, render it
            var isModKey = false
            key.codes.forEach {
                isModKey = isModKey or MOD_KEYS.contains(it)
                if(!isModKey) return@forEach
            }

            if (!isModKey) {
                val keyPreviewWidth = key.width
                val windowManager: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val params = WindowManager.LayoutParams(keyPreviewWidth.toInt(), pressedKey.height * 2,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                        PixelFormat.TRANSPARENT)
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                params.gravity = Gravity.START or Gravity.TOP
                params.verticalMargin
                // Get the location of the keys on screen
                val location = IntArray(2)
                pressedKey.getLocationOnScreen(location)
                val x = location[0]
                val y = location[1]
                params.x = x
                params.y = y
                val keyPreview = CustomKeyPreview(context)
                // Add the preview key view to the window manager
                windowManager.addView(keyPreview, params)
//        renderedKeys.add(pressedKey)
            }
        }
    }

    fun generateModKeysList() {
        context.resources.let { res ->
            MOD_KEYS = listOf(
                    res.getInteger(R.integer.keycode_delete),
                    res.getInteger(R.integer.keycode_abc),
                    res.getInteger(R.integer.keycode_alt),
                    res.getInteger(R.integer.keycode_cancel),
                    res.getInteger(R.integer.keycode_done),
                    res.getInteger(R.integer.keycode_mode_change),
                    res.getInteger(R.integer.keycode_shift),
                    res.getInteger(R.integer.keycode_space),
                    res.getInteger(R.integer.keycode_switch_next_keyboard),
                    res.getInteger(R.integer.keycode_unshift),
                    res.getInteger(R.integer.keycode_sym)
            )
        }
    }

    private fun clearRenderedKeys() {
        val windowManager: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        renderedKeys.let {
            renderedKeys.map { keyView -> windowManager.removeView(keyView) }
            renderedKeys.clear()
        }
    }

    companion object {
        // Messages for handler
        const val MSG_REPEAT = 0
        const val MSG_LONG_CLICK = 1
        // Time intervals
        const val LONG_PRESS_DELAY = 500
        const val REPEAT_INTERVAL = 50 // ~20 keys per second

        const val LOG_TAG = "AMOS"

        const val SWIPE_VELOCITY_THRESHOLD = 50
    }
}

interface KeyboardActionListener {
    fun onKey(primaryCode: Int, keyCodes: IntArray?)
    fun onSwipeLeft()
    fun onSwipeRight()
    fun onSwipeUp()
    fun onSwipeDown()
    fun onChangeKeyboardSwipe(direction: Int)

    companion object {
        const val SWIPE_DIRECTION_LEFT = -1
        const val SWIPE_DIRECTION_RIGHT = 1
    }
}
