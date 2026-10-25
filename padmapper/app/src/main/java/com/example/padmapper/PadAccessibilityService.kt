package com.example.padmapper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent

/**
 * FALLBACK (no Shizuku). Enable in Settings > Accessibility > PadMapper Fallback.
 * Limits: ~1 frame extra latency, max 10 strokes, buttons arrive via onKeyEvent; analog sticks only
 * on API 34+ through onMotionEvent (experimental: verify against your compileSdk).
 */
class PadAccessibilityService : AccessibilityService() {
    companion object { @Volatile var instance: PadAccessibilityService? = null }
    lateinit var router: MappingRouter

    override fun onServiceConnected() {
        instance = this
        router = MappingRouter(AccessibilityTouchInjector(this)).also {
            it.profile = ProfileStore(this).load(); it.start()
        }
        if (Build.VERSION.SDK_INT >= 34) {
            serviceInfo = serviceInfo.apply { setMotionEventSources(InputDevice.SOURCE_JOYSTICK) }
        }
    }

    override fun onKeyEvent(e: KeyEvent): Boolean {
        val n = Maps.ANDROID_KEYS[e.keyCode] ?: return false
        if (e.repeatCount == 0) router.button(n, e.action == KeyEvent.ACTION_DOWN)
        return true // consume so the game doesn't also react
    }

    override fun onMotionEvent(e: MotionEvent) {
        if (e.actionMasked != MotionEvent.ACTION_MOVE) return
        router.axis("LX", e.getAxisValue(MotionEvent.AXIS_X)); router.axis("LY", e.getAxisValue(MotionEvent.AXIS_Y))
        router.axis("RX", e.getAxisValue(MotionEvent.AXIS_Z)); router.axis("RY", e.getAxisValue(MotionEvent.AXIS_RZ))
        router.hat(e.getAxisValue(MotionEvent.AXIS_HAT_X).toInt(), e.getAxisValue(MotionEvent.AXIS_HAT_Y).toInt())
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(i: Intent?): Boolean { instance = null; router.stop(); return super.onUnbind(i) }
}

/** Chains 16 ms continued strokes per active pointer so holds and drags stay "down" between frames. */
class AccessibilityTouchInjector(private val svc: AccessibilityService) : TouchInjector {
    private class P(var x: Float, var y: Float) {
        var px = x; var py = y; var stroke: GestureDescription.StrokeDescription? = null; var ending = false
    }
    private val ptrs = LinkedHashMap<Int, P>()
    private var inFlight = false
    private val FRAME = 16L

    @Synchronized override fun down(id: Int, x: Float, y: Float) { ptrs[id] = P(x, y) }
    @Synchronized override fun move(id: Int, x: Float, y: Float) { ptrs[id]?.let { it.x = x; it.y = y } }
    @Synchronized override fun up(id: Int) { ptrs[id]?.ending = true }

    @Synchronized override fun flush() {
        if (inFlight || ptrs.isEmpty()) return
        val b = GestureDescription.Builder()
        val it = ptrs.values.iterator()
        while (it.hasNext()) {
            val p = it.next()
            val path = Path().apply { moveTo(p.px, p.py); if (p.px != p.x || p.py != p.y) lineTo(p.x, p.y) }
            val cont = !p.ending
            val s = p.stroke?.continueStroke(path, 0, FRAME, cont) ?: GestureDescription.StrokeDescription(path, 0, FRAME, cont)
            p.stroke = s; p.px = p.x; p.py = p.y
            b.addStroke(s)
            if (p.ending) it.remove()
        }
        inFlight = true
        svc.dispatchGesture(b.build(), object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { inFlight = false; flush() }
            override fun onCancelled(g: GestureDescription?) {
                synchronized(this@AccessibilityTouchInjector) { inFlight = false; ptrs.values.forEach { it.stroke = null } }
            }
        }, null)
    }
}
