package com.example.fitnotes.data

import org.json.JSONArray
import org.json.JSONObject

/** Version 14 unifies public backup 13 with the local development backup 9. */
internal object MigratedBackupFields {
    val keys = arrayOf("bodyProfile", "runLinks")
    fun add(root: JSONObject, backup: Backup) {
        root.put("bodyProfile", backup.bodyProfile?.let(BodyProfileJson::encode) ?: JSONObject.NULL)
        root.put("runLinks", JSONArray().also { rows -> backup.runLinks.forEach {
            rows.put(JSONObject().put("sourceKey", it.sourceKey).put("canonicalKey", it.canonicalKey)
                .put("retiredKey", it.retiredKey).put("ignored", it.ignored).put("reviewed", it.reviewed))
        } })
        val rows = root.getJSONArray("bodyMeasurements")
        backup.bodyMeasurements.forEachIndexed { index, record -> rows.getJSONObject(index).put("createdAt", record.createdAt) }
    }
    fun links(root: JSONObject): List<RunLink> {
        val rows = root.getJSONArray("runLinks")
        require(rows.length() <= 100000)
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            require(row.keys().asSequence().toSet() == setOf("sourceKey", "canonicalKey", "retiredKey", "ignored", "reviewed"))
            fun text(key: String) = (row.get(key) as? String) ?: throw IllegalArgumentException("Invalid run link.")
            fun flag(key: String) = (row.get(key) as? Boolean) ?: throw IllegalArgumentException("Invalid run decision.")
            RunLink(text("sourceKey"), text("canonicalKey"), text("retiredKey"), flag("ignored"), flag("reviewed"))
        }
    }
    fun upgradeLocal(root: JSONObject) {
        if (root.optInt("version") != 9 || !root.has("bodyProfile") || !root.has("runLinks")) return
        fun rows(key: String, action: (JSONObject) -> Unit) {
            val values = root.getJSONArray(key)
            for (i in 0 until values.length()) action(values.getJSONObject(i))
        }
        root.put("version", 15)
        for (key in ProgramBackupJson.keys + LoadAdviceBackupJson.keys) root.put(key, JSONArray())
        rows("workouts") { row ->
            for (key in listOf("lastActionAt", "autoFinishedAt", "resumeStartedAt", "resumeBaseSeconds", "staleSuppressedUntil")) row.put(key, JSONObject.NULL)
            for (key in listOf("timingEdited", "timingEstimated", "durationUnknown", "historicalDraft")) row.put(key, false)
            row.put("autoFinishAcknowledged", true).put("timingNote", "").put("draftSourceKey", "")
        }
        rows("plannedWorkouts") { it.put("archived", false).put("pinned", false).put("activityType","strength").put("runningJson","") }
        for (key in listOf("plannedSets", "runTargets")) rows(key) { it.put("rir", JSONObject.NULL).put("rpe", JSONObject.NULL) }
        rows("bodyMeasurements") { if (!it.has("createdAt")) it.put("createdAt", 0L) }
    }
}
