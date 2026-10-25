package com.example.padmapper

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/** Live tuning. Each slider writes the active profile and broadcasts so the running engine updates instantly. */
class SettingsActivity : Activity() {
    private lateinit var store: ProfileStore
    private lateinit var p: Profile

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        store = ProfileStore(this); p = store.load()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 24, 32, 24) }

        slider(col, "Sensitivity X", 0.1f, 3f, p.aim.sensX) { p.aim.sensX = it }
        slider(col, "Sensitivity Y", 0.1f, 3f, p.aim.sensY) { p.aim.sensY = it }
        slider(col, "Response curve (gamma)", 1f, 3f, p.aim.gamma) { p.aim.gamma = it }
        slider(col, "Max pan speed (px/s)", 400f, 4000f, p.aim.maxSpeed) { p.aim.maxSpeed = it }
        slider(col, "Aim deadzone", 0f, 0.3f, p.aim.deadzone) { p.aim.deadzone = it }
        slider(col, "Pan region half-width (px)", 100f, 700f, p.aim.halfW) { p.aim.halfW = it }
        slider(col, "Pan region half-height (px)", 100f, 500f, p.aim.halfH) { p.aim.halfH = it }
        slider(col, "Move stick radius (px)", 60f, 250f, p.left.radius) { p.left.radius = it }
        slider(col, "Move stick deadzone", 0f, 0.3f, p.left.deadzone) { p.left.deadzone = it }
        col.addView(CheckBox(this).apply {
            text = "Invert Y"; isChecked = p.aim.invertY
            setOnCheckedChangeListener { _, c -> p.aim.invertY = c; commit() }
        })
        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun slider(parent: LinearLayout, label: String, min: Float, max: Float, init: Float, set: (Float) -> Unit) {
        val tv = TextView(this)
        fun show(v: Float) { tv.text = "%s: %.2f".format(label, v) }
        show(init)
        val sb = SeekBar(this).apply {
            this.max = 1000
            progress = (((init - min) / (max - min)) * 1000).toInt().coerceIn(0, 1000)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, pr: Int, user: Boolean) {
                    val v = min + (max - min) * pr / 1000f
                    show(v); set(v)
                    if (user) commit()
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        parent.addView(tv); parent.addView(sb)
    }

    private fun commit() {
        store.save(p)
        sendBroadcast(Intent(ACTION_PROFILE_CHANGED).setPackage(packageName))
    }
}
