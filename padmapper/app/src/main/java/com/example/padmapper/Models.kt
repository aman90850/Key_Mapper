package com.example.padmapper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

const val ACTION_PROFILE_CHANGED = "com.example.padmapper.PROFILE_CHANGED"

enum class Mode { HOLD, TAP }

/** One gamepad control -> one fixed screen point. id: A,B,X,Y,L1,R1,L2,R2,L3,R3,START,SELECT,HAT_UP/DOWN/LEFT/RIGHT */
data class ButtonNode(val id: String, var x: Float, var y: Float, var mode: Mode = Mode.HOLD)

/** Left stick drives the game's virtual movement joystick. */
data class LeftStickCfg(var cx: Float = 300f, var cy: Float = 800f, var radius: Float = 120f, var deadzone: Float = 0.12f)

/** Right stick -> continuous camera pan. All tunables live here. */
data class AimCfg(
    var cx: Float = 1700f, var cy: Float = 500f,      // anchor: where the pan pointer touches down
    var halfW: Float = 350f, var halfH: Float = 250f, // pan region half-size; leaving it re-anchors
    var deadzone: Float = 0.08f,
    var gamma: Float = 1.6f,                          // response curve: 1.0 linear, >1 finer near center
    var maxSpeed: Float = 1800f,                      // px/sec at full deflection, sensitivity 1.0
    var sensX: Float = 1.0f, var sensY: Float = 1.0f, // sensitivity multipliers
    var invertY: Boolean = false
)

data class Profile(
    var name: String = "default",
    val buttons: MutableList<ButtonNode> = mutableListOf(),
    var left: LeftStickCfg = LeftStickCfg(),
    var aim: AimCfg = AimCfg()
)

object ProfileJson {
    fun toJson(p: Profile): String = JSONObject().apply {
        put("name", p.name)
        put("buttons", JSONArray().also { a -> p.buttons.forEach {
            a.put(JSONObject().put("id", it.id).put("x", it.x.toDouble()).put("y", it.y.toDouble()).put("mode", it.mode.name))
        } })
        put("left", JSONObject().put("cx", p.left.cx.toDouble()).put("cy", p.left.cy.toDouble())
            .put("radius", p.left.radius.toDouble()).put("dz", p.left.deadzone.toDouble()))
        put("aim", JSONObject().put("cx", p.aim.cx.toDouble()).put("cy", p.aim.cy.toDouble())
            .put("hw", p.aim.halfW.toDouble()).put("hh", p.aim.halfH.toDouble())
            .put("dz", p.aim.deadzone.toDouble()).put("gamma", p.aim.gamma.toDouble())
            .put("max", p.aim.maxSpeed.toDouble()).put("sx", p.aim.sensX.toDouble())
            .put("sy", p.aim.sensY.toDouble()).put("inv", p.aim.invertY))
    }.toString()

    fun fromJson(s: String): Profile {
        val o = JSONObject(s)
        val p = Profile(name = o.optString("name", "default"))
        o.optJSONArray("buttons")?.let { a -> for (i in 0 until a.length()) {
            val b = a.getJSONObject(i)
            p.buttons.add(ButtonNode(b.getString("id"), b.getDouble("x").toFloat(), b.getDouble("y").toFloat(),
                Mode.valueOf(b.optString("mode", "HOLD"))))
        } }
        o.optJSONObject("left")?.let { l -> p.left = LeftStickCfg(
            l.optDouble("cx", 300.0).toFloat(), l.optDouble("cy", 800.0).toFloat(),
            l.optDouble("radius", 120.0).toFloat(), l.optDouble("dz", 0.12).toFloat()) }
        o.optJSONObject("aim")?.let { a -> p.aim = AimCfg(
            a.optDouble("cx", 1700.0).toFloat(), a.optDouble("cy", 500.0).toFloat(),
            a.optDouble("hw", 350.0).toFloat(), a.optDouble("hh", 250.0).toFloat(),
            a.optDouble("dz", 0.08).toFloat(), a.optDouble("gamma", 1.6).toFloat(),
            a.optDouble("max", 1800.0).toFloat(), a.optDouble("sx", 1.0).toFloat(),
            a.optDouble("sy", 1.0).toFloat(), a.optBoolean("inv", false)) }
        return p
    }
}

class ProfileStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("padmapper", Context.MODE_PRIVATE)
    fun save(p: Profile) { sp.edit().putString("p:" + p.name, ProfileJson.toJson(p)).putString("active", p.name).apply() }
    fun load(name: String? = sp.getString("active", null)): Profile =
        name?.let { sp.getString("p:$it", null) }?.let(ProfileJson::fromJson) ?: Profile()
    fun names(): List<String> = sp.all.keys.filter { it.startsWith("p:") }.map { it.removePrefix("p:") }
}
