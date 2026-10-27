package com.example.fitnotes.data

import androidx.room.withTransaction
import com.example.fitnotes.AppData
import java.util.Locale

fun aliasMap(values: List<ExerciseAlias>) = values.groupBy { it.nameKey }.mapValues { (_, aliases) -> aliases.map { it.exerciseId }.toSet() }
class ExerciseIdentityIndex(data: AppData) {
    private val mappings=data.exerciseMappings.associateBy { Triple(it.importId,it.sourceName,it.category) }
    private val categories=data.imported.workouts.flatMap{it.sets}.groupBy{normaliseExerciseName(it.exercise)}.mapValues{(_,rows)->rows.map{it.category}.toSet()}
    private val exact=data.exercises.groupBy{normaliseExerciseName(it.name)}.mapValues{(_,rows)->rows.map{it.id}.toSet()}
    private val aliases=aliasMap(data.exerciseAliases)
    fun source(importId:String,name:String,category:String):Long? {
        mappings[Triple(importId,name,category)]?.let{return it.exerciseId}
        val key=normaliseExerciseName(name)
        if(categories[key].orEmpty().size>1)return null
        return (exact[key].orEmpty()+aliases[key].orEmpty()).singleOrNull()
    }
}
fun canonicalSourceExercise(data:AppData,importId:String,name:String,category:String)=ExerciseIdentityIndex(data).source(importId,name,category)

data class ExerciseMergePreview(val survivor: Exercise, val removed: Exercise, val workoutCount: Int, val planCount: Int,
    val actualSets: Int, val targetSets: Int, val combinedWorkouts: Int, val combinedPlans: Int, val warnings: List<String>, val blockers: List<String>, val fingerprint: String)

class ExerciseIdentityRepository(private val db: FitDatabase) {
    private val dao=db.trainingDao()
    suspend fun addAlias(exerciseId:Long, raw:String) {
        require(db.dao().exercise(exerciseId)!=null) { "Exercise no longer exists." }
        val name=InputRules.exerciseName(raw); val key=normaliseExerciseName(name)
        require(dao.aliases().none { it.exerciseId==exerciseId && it.nameKey==key }) { "This alias already exists." }
        dao.putAlias(ExerciseAlias(exerciseId=exerciseId,name=name,nameKey=key))
    }
    suspend fun removeAlias(id:Long)=dao.deleteAlias(id)
    suspend fun mapSource(value:ExerciseSourceMapping) {
        require(db.dao().exercise(value.exerciseId)!=null && db.importedDao().imports().any { it.id==value.importId }) { "Exercise or import no longer exists." }
        require(db.importedDao().workouts().filter { it.importId==value.importId }.map { it.id }.toSet().let { ids -> db.importedDao().sets().any { it.workoutId in ids && it.exercise==value.sourceName && it.category==value.category } }) { "Source name/category no longer exists." }
        dao.putMapping(value)
    }
    private suspend fun signature(a:Long,b:Long):String {
        val entries=db.dao().entries().filter{it.exerciseId in setOf(a,b)}; val ids=entries.map{it.id}.toSet()
        val plans=db.planDao().exercises().filter{it.exerciseId in setOf(a,b)}; val planIds=plans.map{it.id}.toSet()
        return planFingerprint(listOf(db.dao().exercise(a),db.dao().exercise(b),entries,db.dao().sets().filter{it.entryId in ids},plans,
            db.planDao().sets().filter{it.plannedExerciseId in planIds},db.planDao().runTargets().filter{it.entryId in ids},dao.aliases(),dao.mappings(),dao.redirects(),dao.progression(),db.importedDao().sets()).joinToString("|"))
    }
    suspend fun preview(survivorId:Long,removedId:Long):ExerciseMergePreview {
        require(survivorId!=removedId) { "Choose two different exercises." }
        val survivor=requireNotNull(db.dao().exercise(survivorId)); val removed=requireNotNull(db.dao().exercise(removedId))
        val entries=db.dao().entries().filter{it.exerciseId in setOf(survivorId,removedId)}
        val plans=db.planDao().exercises().filter{it.exerciseId in setOf(survivorId,removedId)}
        val sets=db.dao().sets().filter{it.entryId in entries.map{e->e.id}}
        val targets=db.planDao().runTargets().filter{it.entryId in entries.map{e->e.id}}
        val planned=db.planDao().sets().filter{it.plannedExerciseId in plans.map{e->e.id}}
        val blockers=mutableListOf<String>(); val warnings=mutableListOf("Every exercise occurrence keeps its position, grouping, notes, targets and results. Original imported rows remain unchanged. The old name becomes an alias.")
        fun modes(id:Long)=sets.filter{set->entries.any{it.id==set.entryId && it.exerciseId==id}}.map{it.loadMode}.filter{it!=LoadMode.UNKNOWN}.toSet()+
            targets.filter{set->entries.any{it.id==set.entryId && it.exerciseId==id}}.map{it.loadMode}.filter{it!=LoadMode.UNKNOWN}+
            planned.filter{set->plans.any{it.id==set.plannedExerciseId&&it.exerciseId==id}}.map{it.loadMode}.filter{it!=LoadMode.UNKNOWN}
        val first=modes(survivorId);val second=modes(removedId)
        if(first.isNotEmpty() && second.isNotEmpty() && first!=second) blockers.add("Conflicting load modes. Keep bodyweight, external-load, assistance and reps-only variants separate.")
        if(dao.aliases().any{it.nameKey==normaliseExerciseName(removed.name)&&it.exerciseId !in setOf(survivorId,removedId)}) blockers.add("The old name is an alias for another exercise. Resolve that alias before merging.")
        val imported=db.importedDao().sets()
        val importedIds=canonicalImportedSetIds(db.dao().exercises(),dao.aliases(),dao.mappings(),db.importedDao().workouts(),imported)
        suspend fun categories(id:Long):Set<String> {
            val names=(dao.aliases().filter{it.exerciseId==id}.map{it.nameKey}+normaliseExerciseName(db.dao().exercise(id)!!.name)).toSet()
            val local=sets.filter{set->entries.any{it.id==set.entryId && it.exerciseId==id}}.flatMap { set -> buildList { if(set.reps>0)add("strength");if(set.distance!=null||set.durationSeconds!=null)add("cardio") } }
            return local.toSet()+imported.filter{importedIds[it.id]==id || (it.id !in importedIds && normaliseExerciseName(it.exercise) in names)}.map{if(it.category.equals("Cardio",true)||it.reps==null)"cardio" else "strength"}
        }
        val a=categories(survivorId); val b=categories(removedId)
        if(a.isNotEmpty()&&b.isNotEmpty()&&a!=b) blockers.add("Conflicting strength/cardio categories. Resolve source mappings or keep these movements separate.")
        if(sets.any{it.loadMode==LoadMode.UNKNOWN})warnings.add("Some recorded load modes are unknown. Confirm these names describe the same movement; no mode will be inferred.")
        val configs=dao.progression().filter{it.exerciseId in setOf(survivorId,removedId)}
        if(configs.size==2 && configs[0].copy(exerciseId=0)!=configs[1].copy(exerciseId=0)) blockers.add("Progression settings differ. Set the same progression on both exercises before merging.")
        return ExerciseMergePreview(survivor,removed,entries.map{it.workoutId}.distinct().size+imported.filter{importedIds[it.id] in setOf(survivorId,removedId)}.map{it.workoutId}.distinct().size,plans.map{it.planId}.distinct().size,sets.size+imported.count{importedIds[it.id] in setOf(survivorId,removedId)},targets.size+planned.size,
            entries.groupBy{it.workoutId}.count{it.value.size==2},plans.groupBy{it.planId}.count{it.value.size==2},warnings,blockers.distinct(),signature(survivorId,removedId))
    }
    suspend fun merge(preview:ExerciseMergePreview)=db.withTransaction {
        val a=preview.survivor.id;val b=preview.removed.id
        val fresh=preview(a,b)
        require(fresh.fingerprint==preview.fingerprint) { "Exercise data changed. Review the merge again." }
        require(fresh.blockers.isEmpty()) { fresh.blockers.joinToString("\n") }
        val aliases=dao.aliases();val importWorkouts=db.importedDao().workouts().associateBy{it.id}
        val sourceNames=(aliases.filter{it.exerciseId==b}.map{it.nameKey}+normaliseExerciseName(preview.removed.name)).toSet()
        db.importedDao().sets().filter{normaliseExerciseName(it.exercise) in sourceNames}.forEach { set ->
            val importId=importWorkouts.getValue(set.workoutId).importId
            val existing=dao.mappings().firstOrNull{it.importId==importId&&it.sourceName==set.exercise&&it.category==set.category}
            if(existing==null||existing.exerciseId==b)dao.putMapping(ExerciseSourceMapping(importId,set.exercise,set.category,a))
        }
        dao.mappings().filter{it.exerciseId==b}.forEach{dao.putMapping(it.copy(exerciseId=a))}
        aliases.filter{it.exerciseId==b}.forEach { alias ->
            if(dao.aliases().none{it.exerciseId==a&&it.nameKey==alias.nameKey}) dao.putAlias(alias.copy(exerciseId=a)) else dao.deleteAlias(alias.id)
        }
        if(dao.aliases().none{it.exerciseId==a&&it.nameKey==normaliseExerciseName(preview.removed.name)}) addAlias(a,preview.removed.name)
        dao.redirects().filter{it.exerciseId==b}.forEach{dao.putRedirect(it.copy(exerciseId=a))};dao.putRedirect(ExerciseRedirect(b,a))
        val config=dao.progression().firstOrNull{it.exerciseId==b}
        if(config!=null&&dao.progression().none{it.exerciseId==a})dao.putProgression(config.copy(exerciseId=a))
        // Do not combine occurrences: their IDs preserve every result/target
        // association and their positions preserve ordering and superset groups.
        db.dao().entries().filter{it.exerciseId==b}.forEach{daoEntry->db.dao().updateEntry(daoEntry.copy(exerciseId=a))}
        db.planDao().exercises().filter{it.exerciseId==b}.forEach{target->dao.updatePlannedExercise(target.copy(exerciseId=a))}
        dao.deleteExercise(b)
    }
    private fun mergedNote(a:String,aName:String,b:String,bName:String)=if(a==b&&aName==bName)a else "$aName: ${a.ifBlank{"No exercise note."}}\n$bName: ${b.ifBlank{"No exercise note."}}"
}

fun canonicalImportedSetIds(exercises:List<Exercise>,aliases:List<ExerciseAlias>,mappings:List<ExerciseSourceMapping>,workouts:List<ImportedWorkout>,sets:List<ImportedSet>):Map<String,Long> {
    val sourceIds=workouts.associate{it.id to it.importId}
    val byName=exercises.groupBy{normaliseExerciseName(it.name)}.mapValues{(_,rows)->rows.map{it.id}.toSet()}
    val aliasIds=aliasMap(aliases)
    val mapped=mappings.associateBy{Triple(it.importId,it.sourceName,it.category)}
    val categories=sets.groupBy{normaliseExerciseName(it.exercise)}.mapValues{(_,rows)->rows.map{it.category}.toSet()}
    return sets.mapNotNull { set->
        val key=normaliseExerciseName(set.exercise)
        val id=mapped[Triple(sourceIds[set.workoutId],set.exercise,set.category)]?.exerciseId ?: if(categories[key].orEmpty().size==1)(byName[key].orEmpty()+aliasIds[key].orEmpty()).singleOrNull() else null
        id?.let{set.id to it}
    }.toMap()
}
fun canonicalImportedSetIds(data:AppData)=canonicalImportedSetIds(data.exercises,data.exerciseAliases,data.exerciseMappings,data.imported.workouts.map{it.workout},data.imported.workouts.flatMap{it.sets})
suspend fun FitDatabase.incompatibleStrengthExercises():Set<Long> {
    val exercises=dao().exercises();val imported=importedDao().sets()
    return incompatiblePlanExercises(exercises,dao().entries(),dao().sets(),imported,canonicalImportedSetIds(exercises,trainingDao().aliases(),trainingDao().mappings(),importedDao().workouts(),imported))
}
