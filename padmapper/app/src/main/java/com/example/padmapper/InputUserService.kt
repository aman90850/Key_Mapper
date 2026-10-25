package com.example.padmapper

import org.lsposed.hiddenapibypass.HiddenApiBypass
import kotlin.system.exitProcess

/**
 * Runs in a separate process as SHELL uid, started by Shizuku. Everything latency-critical
 * (read evdev -> route -> inject) lives here, so no Binder hop per input event.
 */
class InputUserService() : IInputService.Stub() {
    private var injector: ShellTouchInjector? = null
    private var router: MappingRouter? = null
    private var reader: EvdevReader? = null
    private var profile = Profile()

    init { HiddenApiBypass.addHiddenApiExemptions("") }  // allow reflection on IInputManager

    override fun destroy() { stop(); exitProcess(0) }

    override fun setProfile(json: String) {
        profile = ProfileJson.fromJson(json)
        router?.profile = profile
    }

    @Synchronized override fun start(deviceName: String) {
        stop()
        val inj = ShellTouchInjector().also { injector = it }
        val r = MappingRouter(inj).also { it.profile = profile; it.start(); router = it }
        reader = EvdevReader(deviceName, r).also { it.start() }
    }

    @Synchronized override fun stop() {
        reader?.active = false; reader = null
        router?.stop(); router = null; injector = null
    }
}
