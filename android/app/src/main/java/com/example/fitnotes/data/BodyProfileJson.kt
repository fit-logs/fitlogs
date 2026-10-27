package com.example.fitnotes.data

import org.json.JSONObject

internal object BodyProfileJson {
    fun encode(profile: BodyProfile): JSONObject = JSONObject().put("id", profile.id).put("heightCm", profile.heightCm ?: JSONObject.NULL)
        .put("birthDate", profile.birthDate ?: JSONObject.NULL).put("weightUnit", profile.weightUnit).put("heightUnit", profile.heightUnit).put("chartRange", profile.chartRange)
    fun decode(json: JSONObject): BodyProfile {
        require(json.keys().asSequence().toSet() == setOf("id", "heightCm", "birthDate", "weightUnit", "heightUnit", "chartRange")) { "Invalid Body profile fields." }
        fun text(key: String): String = (json.get(key) as? String) ?: error("Invalid Body profile $key.")
        require(json.get("id") is Int && json.getInt("id") == 1) { "Invalid Body profile ID." }
        val height = if (json.isNull("heightCm")) null else (json.get("heightCm") as? Number)?.toDouble() ?: error("Invalid height.")
        return BodyProfile(heightCm = height, birthDate = if (json.isNull("birthDate")) null else text("birthDate"), weightUnit = text("weightUnit"), heightUnit = text("heightUnit"), chartRange = text("chartRange")).also { validateBodyProfile(it) }
    }
}
