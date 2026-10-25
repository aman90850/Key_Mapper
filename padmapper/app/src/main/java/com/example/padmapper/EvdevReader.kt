package com.example.padmapper

import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads raw /dev/input/eventN. Works only in the shell-uid process (group "input").
 * This is what lets us see the pad while the GAME is in the foreground (an Activity/overlay cannot).
 * struct input_event on 64-bit Android = 24 bytes: timeval(16) | u16 type | u16 code | s32 value
 */
class EvdevReader(private val deviceName: String, private val router: MappingRouter) : Thread("evdev-reader") {
    @Volatile var active = true
    private val ranges = HashMap<Int, IntArray>()
    private var hx = 0; private var hy = 0

    companion object {
        const val TAG = "PadMapper"
        // Zebronics-style pads often report the right stick on ABS_Z / ABS_RZ (matches AXIS_Z / AXIS_RZ).
        // XInput-style pads use ABS_RX(3)/ABS_RY(4) instead -> swap these two constants.
        const val RIGHT_X = 2; const val RIGHT_Y = 5
        val ABS_NAMES = mapOf("ABS_X" to 0, "ABS_Y" to 1, "ABS_Z" to 2, "ABS_RX" to 3, "ABS_RY" to 4,
            "ABS_RZ" to 5, "ABS_GAS" to 9, "ABS_BRAKE" to 10, "ABS_HAT0X" to 16, "ABS_HAT0Y" to 17)
    }

    override fun run() {
        val path = findNode() ?: run { Log.e(TAG, "no evdev node for $deviceName"); return }
        loadRanges(path)
        val buf = ByteArray(24)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        try {
            // Exclusive grab via JNI so the game no longer sees the pad natively (kernel releases it when fd closes).
            val pfd: ParcelFileDescriptor? = runCatching {
                if (Native.load()) Native.openRead(path).takeIf { it >= 0 }?.let { ParcelFileDescriptor.adoptFd(it) } else null
            }.getOrNull()
            val stream = if (pfd != null) {
                val rc = Native.grab(pfd.fd, true)
                if (rc != 0) Log.w(TAG, "EVIOCGRAB failed rc=$rc (game will also see the pad)")
                FileInputStream(pfd.fileDescriptor)
            } else { Log.w(TAG, "JNI unavailable, reading without grab"); FileInputStream(path) }
            stream.use { fis ->
                while (active) {
                    var off = 0
                    while (off < 24) { val r = fis.read(buf, off, 24 - off); if (r < 0) return; off += r }
                    val type = bb.getShort(16).toInt() and 0xFFFF
                    val code = bb.getShort(18).toInt() and 0xFFFF
                    val v = bb.getInt(20)
                    if (type == 1 && v != 2) {
                        val n = Maps.EVDEV_KEYS[code]
                        if (n != null) router.button(n, v == 1) else Log.d(TAG, "unmapped key $code")
                    } else if (type == 3) onAbs(code, v)
                }
            }
        } catch (_: IOException) { /* device unplugged */ }
        finally { router.stop() }
    }

    private fun onAbs(code: Int, v: Int) {
        when (code) {
            0 -> router.axis("LX", stick(code, v))
            1 -> router.axis("LY", stick(code, v))
            RIGHT_X -> router.axis("RX", stick(code, v))
            RIGHT_Y -> router.axis("RY", stick(code, v))
            9 -> router.axis("R2", trigger(code, v))
            10 -> router.axis("L2", trigger(code, v))
            16 -> { hx = v; router.hat(hx, hy) }
            17 -> { hy = v; router.hat(hx, hy) }
        }
    }

    private fun stick(code: Int, v: Int): Float {
        val r = ranges[code] ?: intArrayOf(0, 255)
        val mid = (r[0] + r[1]) / 2f; val half = (r[1] - r[0]) / 2f
        return ((v - mid) / half).coerceIn(-1f, 1f)
    }
    private fun trigger(code: Int, v: Int): Float {
        val r = ranges[code] ?: intArrayOf(0, 255)
        return ((v - r[0]).toFloat() / (r[1] - r[0])).coerceIn(0f, 1f)
    }

    private fun findNode(): String? {
        File("/sys/class/input").listFiles { f -> f.name.startsWith("event") }?.sortedBy { it.name }?.forEach {
            val n = runCatching { File(it, "device/name").readText().trim() }.getOrNull()
            if (n == deviceName) return "/dev/input/${it.name}"
        }
        return null
    }

    /** `getevent -lp` prints per-axis min/max, so we never hardcode 0..255 vs -32768..32767. */
    private fun loadRanges(path: String) {
        runCatching {
            val out = ProcessBuilder("getevent", "-lp", path).redirectErrorStream(true).start()
                .inputStream.bufferedReader().readText()
            val re = Regex("(ABS_\\w+)\\s*:\\s*(?:value\\s+-?\\d+,\\s*)?min\\s+(-?\\d+),\\s*max\\s+(-?\\d+)")
            re.findAll(out).forEach { m ->
                ABS_NAMES[m.groupValues[1]]?.let { ranges[it] = intArrayOf(m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
            }
        }
    }
}
