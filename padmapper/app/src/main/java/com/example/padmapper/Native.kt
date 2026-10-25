package com.example.padmapper

/** JNI bridge. Loaded inside the Shizuku user-service process (Shizuku exposes the app's native lib dir). */
object Native {
    @Volatile private var ok = false
    fun load(): Boolean {
        if (!ok) ok = runCatching { System.loadLibrary("padgrab"); true }.getOrDefault(false)
        return ok
    }
    external fun openRead(path: String): Int
    external fun grab(fd: Int, on: Boolean): Int   // 0 = success
}
