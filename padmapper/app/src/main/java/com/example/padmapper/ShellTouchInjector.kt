package com.example.padmapper

import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.lang.reflect.Method

/**
 * Multi-touch injector. MUST run inside the Shizuku user-service process (shell uid 2000),
 * which holds android.permission.INJECT_EVENTS. Uses IInputManager.injectInputEvent via reflection.
 */
class ShellTouchInjector : TouchInjector {
    private val im: Any
    private val inject: Method
    private val ids = ArrayList<Int>()                       // active pointer ids, order = pointer index
    private val xy = HashMap<Int, FloatArray>()
    private var downTime = 0L
    private var dirty = false

    init {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, "input") as IBinder
        im = Class.forName("android.hardware.input.IInputManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
        inject = Class.forName("android.hardware.input.IInputManager")
            .getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    }

    @Synchronized override fun down(id: Int, x: Float, y: Float) {
        if (ids.contains(id)) return
        if (ids.isEmpty()) downTime = SystemClock.uptimeMillis()
        ids.add(id); xy[id] = floatArrayOf(x, y)
        val idx = ids.indexOf(id)
        send(if (ids.size == 1) MotionEvent.ACTION_DOWN
             else MotionEvent.ACTION_POINTER_DOWN or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
    }

    @Synchronized override fun move(id: Int, x: Float, y: Float) {
        val p = xy[id] ?: return
        if (p[0] != x || p[1] != y) { p[0] = x; p[1] = y; dirty = true }
    }

    @Synchronized override fun up(id: Int) {
        val idx = ids.indexOf(id); if (idx < 0) return
        flush()
        send(if (ids.size == 1) MotionEvent.ACTION_UP
             else MotionEvent.ACTION_POINTER_UP or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
        ids.removeAt(idx); xy.remove(id)
    }

    @Synchronized override fun flush() {
        if (dirty && ids.isNotEmpty()) { send(MotionEvent.ACTION_MOVE); dirty = false }
    }

    private fun send(action: Int) {
        val n = ids.size
        val props = Array(n) { MotionEvent.PointerProperties().apply { id = ids[it]; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(n) { MotionEvent.PointerCoords().apply { x = xy[ids[it]]!![0]; y = xy[ids[it]]!![1]; pressure = 1f; size = 1f } }
        val ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, n, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { inject.invoke(im, ev, 0 /* INJECT_INPUT_EVENT_MODE_ASYNC */) } finally { ev.recycle() }
    }
}
