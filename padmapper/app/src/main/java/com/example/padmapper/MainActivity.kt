package com.example.padmapper

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku

/**
 * Setup + gamepad tester. The dispatch* overrides only fire while THIS activity is focused,
 * so they are for calibration/testing (printing codes). In-game capture is done by EvdevReader
 * (Shizuku) or PadAccessibilityService.onKeyEvent.
 */
class MainActivity : Activity() {
    private lateinit var log: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        log = TextView(this)
        fun btn(t: String, a: () -> Unit) = Button(this).apply { text = t; setOnClickListener { a() } }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(btn("1. Grant overlay permission") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) })
            addView(btn("2. Shizuku permission") {
                if (Shizuku.pingBinder()) Shizuku.requestPermission(100) else log("Shizuku not running") })
            addView(btn("2b. (fallback) Accessibility settings") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
            addView(btn("3. Start overlay service") {
                if (!Settings.canDrawOverlays(this@MainActivity)) log("Grant overlay first")
                else startForegroundService(Intent(this@MainActivity, MappingService::class.java)) })
            addView(btn("4. Tuning (sensitivity / curve)") {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) })
            addView(ScrollView(this@MainActivity).apply { addView(log) })
        }
        setContentView(col)
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    private fun log(s: String) { log.append(s + "\n") }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val gp = (e.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                 (e.source and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD
        if (gp && e.repeatCount == 0) {
            log("KEY ${KeyEvent.keyCodeToString(e.keyCode)} ${if (e.action == KeyEvent.ACTION_DOWN) "down" else "up"} -> ${Maps.ANDROID_KEYS[e.keyCode]}")
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if ((e.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK && e.action == MotionEvent.ACTION_MOVE) {
            log("LS(%.2f,%.2f) RS(Z,RZ)(%.2f,%.2f) HAT(%.0f,%.0f) LT=%.2f RT=%.2f".format(
                e.getAxisValue(MotionEvent.AXIS_X), e.getAxisValue(MotionEvent.AXIS_Y),
                e.getAxisValue(MotionEvent.AXIS_Z), e.getAxisValue(MotionEvent.AXIS_RZ),
                e.getAxisValue(MotionEvent.AXIS_HAT_X), e.getAxisValue(MotionEvent.AXIS_HAT_Y),
                e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_RTRIGGER)))
            return true
        }
        return super.dispatchGenericMotionEvent(e)
    }
}
