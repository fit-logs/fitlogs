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
            db.planDao().sets().filter{it.plannedExerciseId in planIds},db.planDao().runTargets().filter{it.entryId in ids},dao.aliases(),dao.mappings(),dao.redirects(),dao.progression(),db.loadDao().profiles(),db.loadDao().contexts(),db.loadDao().locks(),db.importedDao().sets()).joinToString("|"))
    }
    suspend fun preview(survivorId:Long,removedId:Long):ExerciseMergePreview {
        require(survivorId!=removedId) { "Choose two different exercises." }
        val survivor=requireNotNull(db.dao().exercise(survivorId)); val removed=requireNotNull(db.dao().exercise(removedId))
        val entries=db.dao().entries().filter{it.exerciseId in setOf(survivorId,removedId)}
        val plans=db.planDao().exercises().filter{it.exerciseId in setOf(survivorId,removedId)}
        val sets=db.dao().sets().filter{it.entryId in entries.map{e->e.id}}
        val targets=db.planDao().runTargets().filter{it.entryId in entries.map{e->e.id}}
        val planned=db.planDao().sets().filter{it.plannedExerciseId in plans.map{e->e.id}}
        val blockers=mutableListOf<String>(); val warnings=mutableListOf("The surviving exercise keeps its order. Its sets come first; sets from the other name follow. Target/result IDs and notes remain linked. Original imported rows are unchanged.")
        fun modes(id:Long)=sets.filter{set->entries.any{it.id==set.entryId && it.exerciseId==id}}.map{it.loadMode}.filter{it!=LoadMode.UNKNOWN}.toSet()+
            targets.filter{set->entries.any{it.id==set.entryId && it.exerciseId==id}}.map{it.loadMode}.filter{it!=LoadMode.UNKNOWN}+
            planned.filter{set->plans.any{it.id==set.plannedExerciseId&&it.exerciseId==id}}.map{it.loadMode}.filter{it!=LoadMode.UNKNOWN}
        val first=modes(survivorId);val second=modes(removedId)
        if(first.isNotEmpty() && second.isNotEmpty() && first!=second) blockers.add("Conflicting load modes. Keep bodyweight, external-load, assistance and reps-only variants separate.")
        val imported=db.importedDao().sets()
        val importedIds=canonicalImportedSetIds(db.dao().exercises(),dao.aliases(),dao.mappings(),db.importedDao().workouts(),imported)
        suspend fun categories(id:Long):Set<String> {
            val names=(dao.aliases().filter{it.exerciseId==id}.map{it.nameKey}+normaliseExerciseName(db.dao().exercise(id)!!.name)).toSet()
            val local=sets.filter{set->entries.any{it.id==set.entryId && it.exerciseId==id}}.flatMap { set -> buildList { if(set.reps>0)add("strength");if(set.distance!=null||set.durationSeconds!=null)add("cardio") } }
            return local.toSet()+imported.filter{importedIds[it.id]==id || (it.id !in importedIds && normaliseExerciseName(it.exercise) in names)}.map{if(it.category.equals("Cardio",true)||it.reps==null)"cardio" else "strength"}
        }
        val a=categories(survivorId); val b=categories(removedId)
        if(a.isNotEmpty()&&b.isNotEmpty()&&a!=b) blockers.add("Conflicting strength/cardio categories. Resolve source mappings or keep these movements separate.")
        entries.groupBy{it.workoutId}.values.filter{it.size==2}.forEach { pair ->
            if(pair.map{it.supersetGroup}.filter{it.isNotBlank()}.distinct().size>1) blockers.add("A workout uses different superset groups. Edit those groups before merging.")
            if(targets.count{it.entryId in pair.map{e->e.id}}>100) blockers.add("A combined workout would exceed 100 targets. Review its targets first.")
            if(mergedNote(pair.first().planNote,survivor.name,pair.last().planNote,removed.name).length>2000) blockers.add("Combined workout guidance exceeds 2,000 characters. Review notes first.")
        }
        plans.groupBy{it.planId}.values.filter{it.size==2}.forEach { pair ->
            if(pair.map{it.supersetGroup}.filter{it.isNotBlank()}.distinct().size>1) blockers.add("A plan uses different superset groups. Edit those groups before merging.")
            if(planned.count{it.plannedExerciseId in pair.map{e->e.id}}>100) blockers.add("A combined plan would exceed 100 targets. Review its targets first.")
            if(mergedNote(pair.first().note,pair.first().sourceName,pair.last().note,pair.last().sourceName).length>2000) blockers.add("Combined plan guidance exceeds 2,000 characters. Review notes first.")
        }
        val profiles=db.loadDao().profiles().filter{it.exerciseId in setOf(survivorId,removedId)}
        // An exercise may assign unknown legacy history to only one reviewed
        // setup. Combining two different assumptions would relabel that history.
        if(profiles.filter{it.reviewedLegacy}.map{listOf(it.gym,it.equipment,it.basis,it.mode)}.distinct().size>1)
            blockers.add("Load setups assign legacy history to different equipment. Turn off one legacy assumption before merging.")
        profiles.groupBy{listOf(it.gym,it.equipment,it.basis,it.mode)}.values.filter{it.size>1}.forEach{group->
            if(group.map{it.copy(id="",exerciseId=0,selected=false)}.distinct().size>1)blockers.add("Load setups have different increments/units/effort settings. Review them before merging.")
        }
        if(sets.any{it.loadMode==LoadMode.UNKNOWN})warnings.add("Some recorded load modes are unknown. Confirm these names describe the same movement; no mode will be inferred.")
        if(dao.progression().count{it.exerciseId in setOf(survivorId,removedId)}>1) warnings.add("The surviving exercise's progression setting will be retained.")
        return ExerciseMergePreview(survivor,removed,entries.map{it.workoutId}.distinct().size,plans.map{it.planId}.distinct().size,sets.size,targets.size+planned.size,
            entries.groupBy{it.workoutId}.count{it.value.size==2},plans.groupBy{it.planId}.count{it.value.size==2},warnings,blockers.distinct(),signature(survivorId,removedId))
    }
    suspend fun merge(preview:ExerciseMergePreview)=db.withTransaction {
        val a=preview.survivor.id;val b=preview.removed.id
        val fresh=preview(a,b)
        require(fresh.fingerprint==preview.fingerprint) { "Exercise data changed. Review the merge again." }
        require(fresh.blockers.isEmpty()) { fresh.blockers.joinToString("\n") }
        val loadProfiles=db.loadDao().profiles();val hasSelectedSurvivor=loadProfiles.any{it.exerciseId==a && it.selected}
        loadProfiles.filter{it.exerciseId==b}.forEach{profile->
            val duplicate=loadProfiles.firstOrNull{it.exerciseId==a && it.gym==profile.gym && it.equipment==profile.equipment && it.basis==profile.basis && it.mode==profile.mode}
            if(duplicate==null)db.loadDao().putProfile(profile.copy(exerciseId=a,selected=profile.selected && !hasSelectedSurvivor))
            else if(profile.selected && !hasSelectedSurvivor)db.loadDao().putProfile(duplicate.copy(selected=true))
        }
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
        db.dao().entries().filter{it.exerciseId==b}.forEach { source ->
            val destination=db.dao().workoutEntries(source.workoutId).firstOrNull{it.exerciseId==a}
            if(destination==null)db.dao().updateEntry(source.copy(exerciseId=a)) else {
                val resultOffset=(db.dao().entrySets(destination.id).maxOfOrNull{it.position}?:-1)+1
                val targetOffset=db.planDao().runTargets().count{it.entryId==destination.id}
                db.planDao().runTargets().filter{it.entryId==source.id}.sortedBy{it.position}.forEachIndexed{index,t->dao.updateTarget(t.copy(entryId=destination.id,position=targetOffset+index))}
                db.dao().entrySets(source.id).forEachIndexed{index,s->db.dao().updateSet(s.copy(entryId=destination.id,position=resultOffset+index))}
                db.dao().updateEntry(destination.copy(planNote=mergedNote(destination.planNote,preview.survivor.name,source.planNote,preview.removed.name),supersetGroup=destination.supersetGroup.ifBlank{source.supersetGroup}))
                if(db.loadDao().locks().any{it.entryId==source.id})db.loadDao().putLock(SessionLoadLock(destination.id))
                db.dao().deleteEntry(source.id)
            }
            db.dao().workoutEntries(source.workoutId).forEachIndexed{index,e->db.dao().updateEntry(e.copy(position=index))}
        }
        db.planDao().exercises().filter{it.exerciseId==b}.forEach { source ->
            val destination=db.planDao().exercises().firstOrNull{it.planId==source.planId&&it.exerciseId==a}
            if(destination==null)dao.updatePlannedExercise(source.copy(exerciseId=a)) else {
                val offset=db.planDao().sets().count{it.plannedExerciseId==destination.id}
                db.planDao().sets().filter{it.plannedExerciseId==source.id}.sortedBy{it.position}.forEachIndexed{index,t->dao.updatePlannedSet(t.copy(plannedExerciseId=destination.id,position=offset+index))}
                dao.updatePlannedExercise(destination.copy(sets=offset+source.sets,note=mergedNote(destination.note,destination.sourceName,source.note,source.sourceName),supersetGroup=destination.supersetGroup.ifBlank{source.supersetGroup}))
                dao.deletePlannedExercise(source.id)
            }
            // Avoid transient collisions with the unique plan-position index.
            val ordered=db.planDao().exercises().filter{it.planId==source.planId}.sortedBy{it.position}
            ordered.forEachIndexed{index,e->dao.updatePlannedExercise(e.copy(position=-index-1))}
            ordered.forEachIndexed{index,e->dao.updatePlannedExercise(e.copy(position=index))}
        }
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
