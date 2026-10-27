package com.example.fitnotes.data

import org.json.JSONArray
import org.json.JSONObject

private fun canonicalJson(value: Any?): String = when(value) {
    is JSONObject -> value.keys().asSequence().sorted().joinToString(prefix="{",postfix="}") { JSONObject.quote(it)+":"+canonicalJson(value.get(it)) }
    is JSONArray -> (0 until value.length()).joinToString(prefix="[",postfix="]"){canonicalJson(value.get(it))}
    else -> value.toString()
}
/** Source primary key plus verbatim source payload, never title/date or kg/reps alone. */
fun nativeSetIdentity(set: ImportedSet): String? {
    if(set.sourceRecordId.startsWith("local:"))return null
    val raw=runCatching{JSONObject(set.rawValues)}.getOrNull() ?: return null
    if(!raw.has("_id") || !raw.has("exercise_id") || !raw.has("date"))return null
    if(raw.opt("_id").toString()!=set.sourceRecordId)return null
    return set.sourceRecordId+":"+canonicalJson(raw)
}
private fun nativeBodyIdentity(body: BodyMeasurement):String? {
    val raw=runCatching{JSONObject(body.rawValues)}.getOrNull() ?: return null
    if(!raw.has("_id")||!raw.has("measurement_id"))return null
    return body.sourceRecordId+":"+canonicalJson(raw)
}
data class HistoryImportDelta(val workouts:List<ImportedWorkout>,val sets:List<ImportedSet>,val body:List<BodyMeasurement>,val updates:List<ImportedWorkout>,val reused:Int)
fun reconcileHistory(plan:ImportPlan,imports:List<HistoryImport>,workouts:List<ImportedWorkout>,sets:List<ImportedSet>,body:List<BodyMeasurement>):HistoryImportDelta {
    if(!plan.document.source.contains("native backup"))return HistoryImportDelta(plan.workouts,plan.sets,plan.body,emptyList(),0)
    val nativeImports=imports.filter{it.source.contains("native backup")}.map{it.id}.toSet()
    val nativeParents=workouts.filter{it.importId in nativeImports}.associateBy{it.id}
    val index=sets.filter{it.workoutId in nativeParents}.mapNotNull{s->nativeSetIdentity(s)?.let{it to s}}.groupBy({it.first},{it.second})
    val nativeRecords=sets.filter{it.workoutId in nativeParents && nativeSetIdentity(it)!=null}.groupBy{it.sourceRecordId}
    plan.sets.forEach { incoming ->
        val key=nativeSetIdentity(incoming)
        require(key==null || nativeRecords[incoming.sourceRecordId].orEmpty().isEmpty() || index[key].orEmpty().isNotEmpty()) {
            "This backup contains changed or conflicting source records. Keep the current history and review the source backup before importing."
        }
    }
    val additions=mutableListOf<ImportedSet>();val newParents=mutableListOf<ImportedWorkout>();val updates=mutableListOf<ImportedWorkout>();var reused=0
    plan.workouts.forEach { source ->
        val incoming=plan.sets.filter{it.workoutId==source.id}
        val matches=incoming.associateWith{nativeSetIdentity(it)?.let{key->index[key]}.orEmpty()}
        val parentIds=matches.values.flatten().map{it.workoutId}.distinct()
        require(parentIds.size<=1){"Some source records already appear in separate workouts. Review and merge those imports in Settings before importing this backup."}
        val parent=parentIds.singleOrNull()?.let{nativeParents.getValue(it)}
        incoming.forEach { set -> if(matches.getValue(set).isNotEmpty())reused++ else additions.add(set.copy(workoutId=parent?.id ?: source.id,
            sourceOrder=if(parent==null)set.sourceOrder else (sets.filter{it.workoutId==parent.id}.maxOfOrNull{it.sourceOrder} ?: -1)+1+additions.count{it.workoutId==parent.id})) }
        if(parent==null)newParents.add(source)
        else {
            // Source payloads identify continuity. Keep user edits and all distinct notes.
            val notes=listOf(parent.notes,source.notes).filter{it.isNotBlank()}.distinct().joinToString("\n\n")
            updates.add(parent.copy(notes=notes,recordedStart=parent.recordedStart ?: source.recordedStart,recordedEnd=parent.recordedEnd ?: source.recordedEnd))
        }
    }
    val knownBody=body.filter{it.importId in nativeImports}.mapNotNull(::nativeBodyIdentity).toSet()
    val freshBody=plan.body.filter{nativeBodyIdentity(it) !in knownBody}
    return HistoryImportDelta(newParents,additions,freshBody,updates,reused)
}

data class HistoryMergePreview(val first:ImportedWorkoutDetail,val second:ImportedWorkoutDetail,val repeated:Int,val retained:Int,val fingerprint:String)
fun historyMergePreview(first:ImportedWorkoutDetail,second:ImportedWorkoutDetail):HistoryMergePreview {
    require(first.workout.id!=second.workout.id)
    val firstKeys=first.sets.mapNotNull{nativeSetIdentity(it)?.let{key->key to it}}.toMap()
    val repeated=second.sets.count{set->nativeSetIdentity(set)?.let{key->firstKeys[key]?.let{sameActualSet(it,set)}}==true}
    require(repeated>0){"These workouts have no identical source records. Keep both sessions."}
    return HistoryMergePreview(first,second,repeated,first.sets.size+second.sets.size-repeated,planFingerprint(first.toString()+second.toString()))
}
fun sameActualSet(a:ImportedSet,b:ImportedSet)=a.exercise==b.exercise&&a.category==b.category&&a.weightKg==b.weightKg&&a.reps==b.reps&&a.distance==b.distance&&a.distanceUnit==b.distanceUnit&&a.durationSeconds==b.durationSeconds&&a.comment==b.comment
fun historyMergeCandidates(workouts:List<ImportedWorkoutDetail>,imports:List<HistoryImport>):List<HistoryMergePreview> {
    val nativeIds=imports.filter{it.source.contains("native backup")}.map{it.id}.toSet()
    val candidates=workouts.filter{it.workout.importId in nativeIds}
    val pairs=mutableSetOf<Pair<String,String>>()
    candidates.flatMap{s->s.sets.mapNotNull{nativeSetIdentity(it)?.let{key->key to s.workout.id}}}.groupBy({it.first},{it.second}).values.forEach{ids->
        val sorted=ids.distinct().sorted();sorted.forEachIndexed{i,a->sorted.drop(i+1).forEach{b->pairs.add(a to b)}}
    }
    val byId=candidates.associateBy{it.workout.id}
    return pairs.mapNotNull{(a,b)->runCatching{historyMergePreview(byId.getValue(a),byId.getValue(b))}.getOrNull()}
}
