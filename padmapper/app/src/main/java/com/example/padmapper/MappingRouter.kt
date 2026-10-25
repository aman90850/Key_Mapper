package com.example.padmapper

import android.view.KeyEvent
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow

/** Backend-agnostic touch sink. Pointer ids are stable per control. */
interface TouchInjector {
    fun down(id: Int, x: Float, y: Float)
    fun move(id: Int, x: Float, y: Float)   // only records position
    fun up(id: Int)
    fun flush()                              // emits ONE batched ACTION_MOVE for all moved pointers
}

object Maps {
    // Linux evdev codes (Shizuku/shell path)
    val EVDEV_KEYS = mapOf(304 to "A", 305 to "B", 307 to "X", 308 to "Y", 310 to "L1", 311 to "R1",
        312 to "L2", 313 to "R2", 314 to "SELECT", 315 to "START", 317 to "L3", 318 to "R3")
    // Android keycodes (Activity / AccessibilityService path)
    val ANDROID_KEYS = mapOf(
        KeyEvent.KEYCODE_BUTTON_A to "A", KeyEvent.KEYCODE_BUTTON_B to "B",
        KeyEvent.KEYCODE_BUTTON_X to "X", KeyEvent.KEYCODE_BUTTON_Y to "Y",
        KeyEvent.KEYCODE_BUTTON_L1 to "L1", KeyEvent.KEYCODE_BUTTON_R1 to "R1",
        KeyEvent.KEYCODE_BUTTON_L2 to "L2", KeyEvent.KEYCODE_BUTTON_R2 to "R2",
        KeyEvent.KEYCODE_BUTTON_THUMBL to "L3", KeyEvent.KEYCODE_BUTTON_THUMBR to "R3",
        KeyEvent.KEYCODE_BUTTON_START to "START", KeyEvent.KEYCODE_BUTTON_SELECT to "SELECT",
        KeyEvent.KEYCODE_DPAD_UP to "HAT_UP", KeyEvent.KEYCODE_DPAD_DOWN to "HAT_DOWN",
        KeyEvent.KEYCODE_DPAD_LEFT to "HAT_LEFT", KeyEvent.KEYCODE_DPAD_RIGHT to "HAT_RIGHT")
}

/**
 * Turns normalized gamepad state into touches. Thread-safe.
 * Pointer ids: 0 = left stick, 1 = aim pan, 2+ = buttons (by index in profile.buttons).
 */
class MappingRouter(private val inj: TouchInjector) {
    @Volatile var profile: Profile = Profile()

    private val lock = Any()
    private val held = HashSet<String>()
    private val tapRelease = HashMap<Int, Long>()
    private var lx = 0f; private var ly = 0f; private var rx = 0f; private var ry = 0f
    private var leftDown = false; private var aimDown = false
    private var aimX = 0f; private var aimY = 0f
    private var hx = 0; private var hy = 0
    @Volatile private var running = false
    private var thread: Thread? = null

    companion object {
        const val PTR_LEFT = 0; const val PTR_AIM = 1; const val PTR_BTN = 2
        const val TICK_NS = 8_000_000L   // ~125 Hz pan/update loop; lower for more samples/sec
        const val TAP_NS = 25_000_000L
    }

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "mapper-tick").also { it.priority = Thread.MAX_PRIORITY; it.start() }
    }

    fun stop() { running = false; synchronized(lock) { releaseAll() } }

    private fun loop() {
        var last = System.nanoTime()
        while (running) {
            val now = System.nanoTime()
            val dt = ((now - last) / 1e9f).coerceAtMost(0.05f); last = now
            synchronized(lock) { tick(dt, now) }
            LockSupport.parkNanos(TICK_NS)
        }
    }

    // ---- inputs ----
    fun button(name: String, pressed: Boolean) = synchronized(lock) {
        val p = profile
        val idx = p.buttons.indexOfFirst { it.id == name }
        if (idx < 0) return@synchronized
        val n = p.buttons[idx]; val pid = PTR_BTN + idx
        if (pressed && name !in held) {
            held += name
            inj.down(pid, n.x, n.y); inj.flush()
            if (n.mode == Mode.TAP) tapRelease[pid] = System.nanoTime() + TAP_NS
        } else if (!pressed && name in held) {
            held -= name
            if (n.mode == Mode.HOLD) { inj.up(pid); inj.flush() }
        }
    }

    /** name: LX, LY, RX, RY in [-1,1]; L2, R2 analog triggers in [0,1] */
    fun axis(name: String, v: Float) = synchronized(lock) {
        when (name) {
            "LX" -> lx = v; "LY" -> ly = v; "RX" -> rx = v; "RY" -> ry = v
            "L2", "R2" -> button(name, v > 0.5f)
        }
    }

    fun hat(x: Int, y: Int) {
        if (x != hx) { button("HAT_LEFT", x < 0); button("HAT_RIGHT", x > 0); hx = x }
        if (y != hy) { button("HAT_UP", y < 0); button("HAT_DOWN", y > 0); hy = y }
    }

    // ---- per-frame logic ----
    private fun tick(dt: Float, now: Long) {
        tapRelease.entries.removeAll { if (now >= it.value) { inj.up(it.key); true } else false }

        // Left stick -> virtual movement joystick
        val l = profile.left
        val lm = hypot(lx, ly)
        if (lm > l.deadzone) {
            val k = minOf(lm, 1f)
            val tx = l.cx + lx / lm * k * l.radius
            val ty = l.cy + ly / lm * k * l.radius      // stick up = negative = screen up
            if (!leftDown) { inj.down(PTR_LEFT, l.cx, l.cy); leftDown = true }
            inj.move(PTR_LEFT, tx, ty)
        } else if (leftDown) { inj.up(PTR_LEFT); leftDown = false }

        // Right stick -> continuous relative pan
        val a = profile.aim
        val mag = hypot(rx, ry)
        if (mag > a.deadzone) {
            // 1) radial deadzone, rescaled so output starts at 0 right after the deadzone
            val s = ((mag - a.deadzone) / (1f - a.deadzone)).coerceIn(0f, 1f)
            // 2) response curve: speed = s^gamma * maxSpeed  (fine control near center, full speed at edge)
            val speed = s.pow(a.gamma) * a.maxSpeed
            // 3) unit direction * speed * sensitivity * dt  => pixels this frame (kept as float: sub-pixel accumulation)
            val ux = rx / mag
            val uy = (if (a.invertY) -ry else ry) / mag
            if (!aimDown) { aimX = a.cx; aimY = a.cy; inj.down(PTR_AIM, aimX, aimY); aimDown = true }
            aimX += ux * speed * a.sensX * dt
            aimY += uy * speed * a.sensY * dt
            // 4) finger ran out of room: lift and re-anchor at center, keep panning
            if (abs(aimX - a.cx) > a.halfW || abs(aimY - a.cy) > a.halfH) {
                inj.up(PTR_AIM); aimX = a.cx; aimY = a.cy; inj.down(PTR_AIM, aimX, aimY)
            } else inj.move(PTR_AIM, aimX, aimY)
        } else if (aimDown) { inj.up(PTR_AIM); aimDown = false }

        inj.flush()
    }

    private fun releaseAll() {
        if (leftDown) inj.up(PTR_LEFT); if (aimDown) inj.up(PTR_AIM)
        profile.buttons.forEachIndexed { i, b -> if (b.id in held || tapRelease.containsKey(PTR_BTN + i)) inj.up(PTR_BTN + i) }
        leftDown = false; aimDown = false; held.clear(); tapRelease.clear(); inj.flush()
    }
}
