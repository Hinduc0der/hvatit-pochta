package com.hndcdr.pochta

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Журнал проверок и счётчик заблокированных запросов */
class LogActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (12 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        val text = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
        }
        fun fill() {
            text.text = "Заблокировано запросов с запуска: ${AdBlock.blocked.get()}\n\n" +
                Log.read(this).lines().reversed().joinToString("\n")
        }
        val clear = Button(this).apply {
            this.text = "Очистить"
            setOnClickListener { Log.clear(this@LogActivity); fill() }
        }
        root.addView(clear)
        root.addView(ScrollView(this).apply { addView(text) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        fill()
    }
}
