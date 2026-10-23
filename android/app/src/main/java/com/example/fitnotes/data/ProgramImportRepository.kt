package com.example.fitnotes.data

import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

val PROGRAM_IMPORT_MIGRATION_13_14 = object : Migration(13,14) {
    override fun migrate(db:SupportSQLiteDatabase) {
        for(column in listOf("definitionId","definitionVersion","definitionJson","sourceFingerprint"))db.execSQL("ALTER TABLE training_programs ADD COLUMN $column TEXT NOT NULL DEFAULT ''")
    }
}
sealed interface ProgramImportResult { data class Saved(val id:String):ProgramImportResult;data class Duplicate(val id:String):ProgramImportResult }

class ProgramImportRepository(private val db:FitDatabase) {
    suspend fun save(draft:ProgramImportDraft,allowDuplicate:Boolean=false):ProgramImportResult=db.withTransaction {
        val (definition,previews)=draft.validated();val dao=db.programDao()
        if(!allowDuplicate)dao.programs().firstOrNull{it.sourceFingerprint==definition.fingerprint || it.definitionId==definition.id && it.definitionVersion==definition.version}?.let{return@withTransaction ProgramImportResult.Duplicate(it.id)}
        // Resolve all explicit creations once, across days, inside the same
        // transaction. A failed final day rolls back the catalogue and program.
        val pending=previews.flatMap{it.resolutions}.mapNotNull{it.createName}.map(InputRules::exerciseName).distinctBy{it.lowercase(Locale.ROOT)}
        val created=mutableMapOf<String,Long>()
        for(name in pending) {
            val key=name.lowercase(Locale.ROOT)
            require(db.dao().exerciseByKey(key)==null) { "$name now exists. Review its mapping before saving." }
            created[key]=db.dao().insertExercise(Exercise(name=name,nameKey=key))
        }
        val id=UUID.randomUUID().toString()
        dao.insert(TrainingProgram(id,definition.name,definition.weeks,definitionId=definition.id,definitionVersion=definition.version,definitionJson=definition.json,sourceFingerprint=definition.fingerprint))
        val sourceDays=definition.document.getJSONArray("days")
        val plans=WorkoutPlanRepository(db)
        previews.forEachIndexed { index,preview ->
            val incoming=sourceDays.getJSONObject(index).getJSONArray("exercises")
            val resolved=preview.resolutions.mapIndexed { eIndex,resolution ->
                val metadata=incoming.getJSONObject(eIndex);val guidance=guidance(metadata)
                val prescription=resolution.prescription.copy(note=listOf(resolution.prescription.note,guidance).filter{it.isNotBlank()}.joinToString("\n"))
                val createdId=resolution.createName?.let{created[InputRules.exerciseName(it).lowercase(Locale.ROOT)]}
                resolution.copy(prescription=prescription,exerciseId=resolution.exerciseId ?: createdId,createName=null)
            }
            val spec=preview.spec.copy(exercises=resolved.map{it.prescription},fingerprint=planFingerprint(definition.json+":$index"))
            val plan=(plans.save(PlanPreview(spec,resolved),allowDuplicate=true) as PlanSaveResult.Saved).id
            dao.insertDays(listOf(ProgramDay(UUID.randomUUID().toString(),id,index,plan)))
        }
        ProgramImportResult.Saved(id)
    }
    suspend fun editIdentity(id:String,title:String,description:String)=db.withTransaction {
        require(title.trim().isNotBlank() && title.trim().length<=200 && title.none(Char::isISOControl)) { "Enter a readable program title up to 200 characters." }
        require(description.length<=2000 && description.none{it.isISOControl() && it !in "\n\r\t"}) { "Description supports 2,000 readable characters." }
        val p=requireNotNull(db.programDao().detail(id)).program
        val definition=if(p.definitionJson.isBlank())"" else PortableProgramParser.parse(JSONObject(p.definitionJson).put("name",title.trim()).put("description",description).toString()).json
        // The source fingerprint remains the import receipt, so editing a local
        // copy never turns a repeat upstream paste into an implicit overwrite.
        db.programDao().update(p.copy(title=title.trim(),definitionJson=definition))
    }
    companion object {
        fun guidance(exercise:JSONObject):String {
            val parts=mutableListOf<String>();val sets=exercise.optJSONArray("sets") ?: JSONArray()
            for(i in 0 until sets.length()) {
                val s=sets.getJSONObject(i);val effort=listOf("rir","rpe").mapNotNull{k->if(!s.has(k)||s.isNull(k))null else k.uppercase()+" "+s.get(k)}
                if(effort.isNotEmpty())parts.add("Set ${i+1}: "+effort.joinToString(" · "))
            }
            exercise.optJSONArray("substitutions")?.takeIf{it.length()>0}?.let{a->parts.add("Optional substitutions (choose explicitly): "+(0 until a.length()).joinToString(", "){a.getString(it)})}
            return parts.joinToString("\n")
        }
    }
}
