package com.example.padmapper

import android.app.*
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.*
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku
import kotlin.math.hypot

class MappingService : Service(), InputManager.InputDeviceListener {
    private lateinit var wm: WindowManager
    private lateinit var im: InputManager
    private lateinit var store: ProfileStore
    private var profile = Profile()
    private var editMode = false
    private val nodeViews = HashMap<String, TextView>()
    private lateinit var bubble: TextView

    private var gamepadId = -1
    private var gamepadName: String? = null
    private var remote: IInputService? = null

    private val ALL = listOf("A", "B", "X", "Y", "L1", "R1", "L2", "R2", "L3", "R3", "START", "SELECT",
        "HAT_UP", "HAT_DOWN", "HAT_LEFT", "HAT_RIGHT", "LSTICK", "AIM")

    // ---------------- lifecycle ----------------
    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        im = getSystemService(Context.INPUT_SERVICE) as InputManager
        store = ProfileStore(this); profile = store.load()

        val ch = "mapper"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(ch, "Mapper", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(this, ch).setContentTitle("PadMapper running")
            .setSmallIcon(android.R.drawable.ic_menu_manage).build()
        ServiceCompat.startForeground(this, 1, n,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)

        // (1) gamepad detection
        im.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
        im.inputDeviceIds.forEach { onInputDeviceAdded(it) }

        buildBubble()
        ContextCompat.registerReceiver(this, profileReceiver, IntentFilter(ACTION_PROFILE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED)

        // (2) Shizuku wiring
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addRequestPermissionResultListener(permListener)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(profileReceiver) }
        im.unregisterInputDeviceListener(this)
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeRequestPermissionResultListener(permListener)
        runCatching { remote?.stop() }
        runCatching { Shizuku.unbindUserService(userArgs, conn, true) }
        clearNodes(); runCatching { wm.removeView(bubble) }
        super.onDestroy()
    }

    // Live updates from SettingsActivity sliders (ignored while editing node positions)
    private val profileReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (editMode) return
            profile = store.load()
            runCatching { remote?.setProfile(ProfileJson.toJson(profile)) }
            PadAccessibilityService.instance?.router?.profile = profile
        }
    }

    // ---------------- gamepad hot-plug ----------------
    private fun isGamepad(d: InputDevice?): Boolean {
        val s = d?.sources ?: return false
        return (s and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
               (s and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }
    override fun onInputDeviceAdded(id: Int) {
        val d = InputDevice.getDevice(id)
        if (isGamepad(d)) { gamepadId = id; gamepadName = d!!.name; pushAndStart() }
    }
    override fun onInputDeviceRemoved(id: Int) {
        if (id == gamepadId) { gamepadId = -1; gamepadName = null; runCatching { remote?.stop() } }
    }
    override fun onInputDeviceChanged(id: Int) {}

    // ---------------- Shizuku (primary engine) ----------------
    /*
     * SHIZUKU SETUP (once per boot unless you use wireless-debugging auto-start):
     *  1. Install Shizuku (moe.shizuku.privileged.api).
     *  2. Start it: Developer options > Wireless debugging > pair, then open Shizuku > "Start via Wireless debugging"
     *     (or `adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh`).
     *  3. Open PadMapper; Shizuku.requestPermission() below shows the grant dialog.
     *  4. After grant, bindUserService() launches InputUserService in a shell-uid process.
     */
    private val userArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(packageName, InputUserService::class.java.name))
            .processNameSuffix("input").daemon(false).debuggable(false).version(1)
    }
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) { remote = IInputService.Stub.asInterface(b); pushAndStart() }
        override fun onServiceDisconnected(n: ComponentName) { remote = null }
    }
    private val binderListener = Shizuku.OnBinderReceivedListener { tryBind() }
    private val permListener = Shizuku.OnRequestPermissionResultListener { _, r ->
        if (r == PackageManager.PERMISSION_GRANTED) tryBind()
    }
    private fun tryBind() {
        if (!Shizuku.pingBinder()) return
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) { Shizuku.requestPermission(100); return }
        Shizuku.bindUserService(userArgs, conn)
    }

    /** Push profile to whichever backend is alive. Shizuku preferred, Accessibility otherwise. */
    private fun pushAndStart() {
        val json = ProfileJson.toJson(profile)
        val r = remote
        if (r != null) { r.setProfile(json); gamepadName?.let { r.start(it) } }
        else PadAccessibilityService.instance?.router?.profile = profile
    }

    // ---------------- overlay UI ----------------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun lp(x: Int, y: Int) = WindowManager.LayoutParams(dp(48), dp(48),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START; this.x = x; this.y = y }

    private fun circle(c: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c); setStroke(2, Color.WHITE) }

    private fun buildBubble() {
        bubble = TextView(this).apply {
            text = "PLAY"; gravity = Gravity.CENTER; setTextColor(Color.WHITE); textSize = 10f
            background = circle(Color.argb(160, 0, 150, 0))
            setOnClickListener { toggleMode() }
        }
        wm.addView(bubble, lp(dp(8), dp(8)))
    }

    private fun toggleMode() {
        editMode = !editMode
        if (editMode) {
            runCatching { remote?.stop() }
            PadAccessibilityService.instance?.router?.stop()
            bubble.text = "EDIT"; bubble.background = circle(Color.argb(200, 200, 100, 0))
            showNodes()
        } else {
            store.save(profile); clearNodes()
            bubble.text = "PLAY"; bubble.background = circle(Color.argb(90, 0, 150, 0))
            PadAccessibilityService.instance?.router?.start()
            pushAndStart()
        }
    }

    private fun showNodes() {
        ALL.forEachIndexed { i, id ->
            val saved: Pair<Float, Float>? = when (id) {
                "LSTICK" -> profile.left.cx to profile.left.cy
                "AIM" -> profile.aim.cx to profile.aim.cy
                else -> profile.buttons.find { it.id == id }?.let { it.x to it.y }
            }
            val (px, py) = saved?.let { (it.first - dp(24)).toInt() to (it.second - dp(24)).toInt() }
                ?: (dp(70) + i * dp(50) to dp(8))            // unplaced nodes wait in a tray along the top
            val v = makeNode(id, saved != null)
            nodeViews[id] = v; wm.addView(v, lp(px, py))
        }
    }
    private fun clearNodes() { nodeViews.values.forEach { runCatching { wm.removeView(it) } }; nodeViews.clear() }

    private fun makeNode(id: String, placed: Boolean) = TextView(this).apply {
        text = id; gravity = Gravity.CENTER; setTextColor(Color.WHITE); textSize = 9f
        background = circle(if (id == "AIM") Color.argb(150, 200, 0, 0) else if (id == "LSTICK") Color.argb(150, 0, 0, 200)
                            else Color.argb(if (placed) 170 else 100, 40, 40, 40))
        var dx = 0f; var dy = 0f; var sx = 0f; var sy = 0f; var t0 = 0L; var moved = false
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        setOnTouchListener { v, e ->
            val p = v.layoutParams as WindowManager.LayoutParams
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX - p.x; dy = e.rawY - p.y; sx = e.rawX; sy = e.rawY; t0 = e.eventTime; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (hypot(e.rawX - sx, e.rawY - sy) > slop) moved = true
                    if (moved) { p.x = (e.rawX - dx).toInt(); p.y = (e.rawY - dy).toInt(); wm.updateViewLayout(v, p) }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) commit(id, v)
                    else if (e.eventTime - t0 > 600) removeMapping(id, v)   // long-press = unmap
                }
            }
            true
        }
    }

    /** Save the node's true on-screen center (this is the coordinate the injector will touch). */
    private fun commit(id: String, v: View) {
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        val cx = loc[0] + v.width / 2f; val cy = loc[1] + v.height / 2f
        when (id) {
            "LSTICK" -> { profile.left.cx = cx; profile.left.cy = cy }
            "AIM" -> { profile.aim.cx = cx; profile.aim.cy = cy }
            else -> profile.buttons.find { it.id == id }?.let { it.x = cx; it.y = cy }
                ?: profile.buttons.add(ButtonNode(id, cx, cy))
        }
        v.background = circle(Color.argb(170, 40, 40, 40))
        store.save(profile)
    }

    private fun removeMapping(id: String, v: View) {
        if (id == "LSTICK" || id == "AIM") return
        profile.buttons.removeAll { it.id == id }
        val p = v.layoutParams as WindowManager.LayoutParams
        p.x = dp(70) + ALL.indexOf(id) * dp(50); p.y = dp(8); wm.updateViewLayout(v, p)
        v.background = circle(Color.argb(100, 40, 40, 40)); store.save(profile)
    }
}
