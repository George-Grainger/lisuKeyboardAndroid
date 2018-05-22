package com.amosgwa.lisukeyboard.keyboard

import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.inputmethodservice.Keyboard
import android.util.Xml
import com.amosgwa.lisukeyboard.R

class GwaKeyboardKey(res: Resources?, parent: Keyboard.Row?, x: Int, y: Int, parser: XmlResourceParser?) : Keyboard.Key(res, parent, x, y, parser) {
    var subLabel: CharSequence?
    init {
        val a = res?.obtainAttributes(Xml.asAttributeSet(parser),
                R.styleable.GwaKeyboardKey)
        subLabel = a?.getText(R.styleable.GwaKeyboardKey_subLabel)
        a?.recycle()
    }
}