package com.example.fitnotes.data

import org.json.JSONArray
import org.json.JSONObject

internal object TrainingBackupJson {
    val rootKeys = arrayOf("exerciseAliases", "exerciseMappings", "exerciseRedirects", "exerciseProgression", "trainingPreferences", "restTimer")
    fun add(root: JSONObject, b: Backup) {
        fun rows(name: String, values: List<JSONObject>) { root.put(name, JSONArray(values)) }
        rows("exerciseAliases", b.exerciseAliases.map { JSONObject().put("id", it.id).put("exerciseId", it.exerciseId).put("name", it.name).put("nameKey", it.nameKey) })
        rows("exerciseMappings", b.exerciseMappings.map { JSONObject().put("importId", it.importId).put("sourceName", it.sourceName).put("category", it.category).put("exerciseId", it.exerciseId) })
        rows("exerciseRedirects", b.exerciseRedirects.map { JSONObject().put("oldId", it.oldId).put("exerciseId", it.exerciseId) })
        rows("exerciseProgression", b.exerciseProgression.map { JSONObject().put("exerciseId", it.exerciseId).put("enabled", it.enabled).put("increment", it.increment).put("unit", it.unit) })
        root.put("trainingPreferences", b.trainingPreferences?.let { JSONObject().put("id", it.id).put("automaticRest", it.automaticRest).put("restAlerts", it.restAlerts).put("favouriteKeys", it.favouriteKeys) } ?: JSONObject.NULL)
        root.put("restTimer", b.restTimer?.let { JSONObject().put("id", it.id).put("workoutId", it.workoutId).put("token", it.token).put("sourceTargetId", it.sourceTargetId ?: JSONObject.NULL)
            .put("remainingMillis", it.remainingMillis).put("anchorElapsed", it.anchorElapsed).put("bootCount", it.bootCount).put("running", it.running).put("alerted", it.alerted) } ?: JSONObject.NULL)
    }
    fun aliases(root: JSONObject) = objects(root, "exerciseAliases").map { o ->
        keys(o, "id", "exerciseId", "name", "nameKey"); ExerciseAlias(long(o,"id"), long(o,"exerciseId"), text(o,"name"), text(o,"nameKey"))
    }
    fun mappings(root: JSONObject) = objects(root, "exerciseMappings").map { o ->
        keys(o,"importId","sourceName","category","exerciseId"); ExerciseSourceMapping(text(o,"importId"),text(o,"sourceName"),text(o,"category"),long(o,"exerciseId"))
    }
    fun redirects(root: JSONObject) = objects(root, "exerciseRedirects").map { o ->
        keys(o,"oldId","exerciseId"); ExerciseRedirect(long(o,"oldId"),long(o,"exerciseId"))
    }
    fun progression(root: JSONObject) = objects(root, "exerciseProgression").map { o ->
        keys(o,"exerciseId","enabled","increment","unit"); ExerciseProgression(long(o,"exerciseId"),boolean(o,"enabled"),text(o,"increment"),text(o,"unit"))
    }
    fun preferences(root: JSONObject): TrainingPreferences? = if (root.isNull("trainingPreferences")) null else root.getJSONObject("trainingPreferences").let { o ->
        keys(o,"id","automaticRest","restAlerts","favouriteKeys"); TrainingPreferences(int(o,"id"),boolean(o,"automaticRest"),boolean(o,"restAlerts"),text(o,"favouriteKeys"))
    }
    fun timer(root: JSONObject): RestTimerState? = if (root.isNull("restTimer")) null else root.getJSONObject("restTimer").let { o ->
        keys(o,"id","workoutId","token","sourceTargetId","remainingMillis","anchorElapsed","bootCount","running","alerted")
        RestTimerState(int(o,"id"),long(o,"workoutId"),text(o,"token"),if(o.isNull("sourceTargetId")) null else long(o,"sourceTargetId"),long(o,"remainingMillis"),long(o,"anchorElapsed"),int(o,"bootCount"),boolean(o,"running"),boolean(o,"alerted"))
    }
    private fun keys(o: JSONObject, vararg fields: String) { require(o.keys().asSequence().toSet() == fields.toSet()) { "Missing or unknown training backup fields." } }
    private fun text(o: JSONObject, key: String) = o.get(key) as? String ?: throw IllegalArgumentException("$key must be text.")
    private fun long(o: JSONObject, key: String): Long { val v=o.get(key); require(v is Int || v is Long) { "$key must be a whole number." }; return (v as Number).toLong() }
    private fun int(o: JSONObject,key:String)=long(o,key).also{require(it in Int.MIN_VALUE..Int.MAX_VALUE)}.toInt()
    private fun boolean(o:JSONObject,key:String)=o.get(key) as? Boolean ?: throw IllegalArgumentException("$key must be boolean.")
    private fun objects(o:JSONObject,key:String):List<JSONObject> { val a=o.getJSONArray(key);require(a.length()<=1000000);return (0 until a.length()).map{a.getJSONObject(it)} }
}

fun validateTrainingBackup(b: Backup) {
    if (b.version < 7) {
        require(b.exerciseAliases.isEmpty() && b.exerciseMappings.isEmpty() && b.exerciseRedirects.isEmpty() && b.exerciseProgression.isEmpty() && b.trainingPreferences==null && b.restTimer==null &&
            b.sets.all { it.targetId==null && it.completedAt==null && it.loadMode==LoadMode.UNKNOWN && !it.isWarmUp && it.weightSpecified } &&
            b.entries.all { it.supersetGroup.isEmpty() } && b.plannedExercises.all { it.supersetGroup.isEmpty() } &&
            b.plannedSets.all { it.loadMode==LoadMode.target(PlanWeight(it.weightValue,it.weightUnit)) && !it.isWarmUp } &&
            b.runTargets.all { it.loadMode==LoadMode.target(PlanWeight(it.weightValue,it.weightUnit)) && !it.isWarmUp && !it.skipped }) { "These training fields require backup version 7." }
        return
    }
    val exercises=b.exercises.map{it.id}.toSet(); val sources=b.imports.map{it.id}.toSet(); val targets=b.runTargets.associateBy{it.id}
    require(b.sets.mapNotNull{it.targetId}.distinct().size == b.sets.count{it.targetId!=null}) { "More than one result fulfils a target." }
    b.sets.forEach { s ->
        require(s.loadMode in LoadMode.values && (s.completedAt==null || s.completedAt>0)) { "Invalid actual load mode or completion time." }
        require(s.weightSpecified || s.weightKg==0.0) { "Unspecified loads cannot hide a numeric value." }
        require(s.loadMode!=LoadMode.REPS_ONLY || s.weightKg==0.0) { "Reps-only results must not contain a load." }
        s.targetId?.let { require(targets[it]?.entryId==s.entryId && targets[it]?.skipped==false) { "Invalid target/result relation." } }
    }
    require(b.exerciseAliases.map{it.id}.distinct().size==b.exerciseAliases.size && b.exerciseAliases.map{it.exerciseId to it.nameKey}.distinct().size==b.exerciseAliases.size) { "Duplicate aliases." }
    b.exerciseAliases.forEach { require(it.id>0 && it.exerciseId in exercises && InputRules.exerciseName(it.name)==it.name && normaliseExerciseName(it.name)==it.nameKey) { "Invalid alias." } }
    require(b.exerciseMappings.map{Triple(it.importId,it.sourceName,it.category)}.distinct().size==b.exerciseMappings.size) { "Duplicate source mappings." }
    b.exerciseMappings.forEach { require(it.exerciseId in exercises && it.importId in sources && it.sourceName.isNotBlank() && it.sourceName.length<=500 && it.category.length<=2000) { "Invalid source mapping." } }
    require(b.exerciseRedirects.map{it.oldId}.distinct().size==b.exerciseRedirects.size) { "Duplicate redirects." }
    b.exerciseRedirects.forEach { require(it.oldId>0 && it.oldId !in exercises && it.exerciseId in exercises) { "Invalid canonical redirect." } }
    require(b.exerciseProgression.map{it.exerciseId}.distinct().size==b.exerciseProgression.size) { "Duplicate progression settings." }
    b.exerciseProgression.forEach { require(it.exerciseId in exercises && java.math.BigDecimal(WorkoutPlanParser.parseWeight("${it.increment} ${it.unit}").value)>java.math.BigDecimal.ZERO) { "Invalid progression increase." } }
    (b.entries.map{it.supersetGroup}+b.plannedExercises.map{it.supersetGroup}).forEach { require(it.length<=40 && it.none(Char::isISOControl)) { "Invalid superset group." } }
    b.trainingPreferences?.let { require(it.id==1);val names=JSONArray(it.favouriteKeys);require(names.length()<=20000);for(index in 0 until names.length())require(names.get(index) is String && names.getString(index).length<=100) }
    b.restTimer?.let { timer ->
        require(timer.id==1 && b.workouts.any { it.id==timer.workoutId && !it.isComplete } && timer.token.length in 1..128 && timer.remainingMillis in 0..7200000 && timer.anchorElapsed>=0) { "Invalid rest timer." }
        timer.sourceTargetId?.let { id -> require(targets[id]?.entryId in b.entries.filter{it.workoutId==timer.workoutId}.map{it.id}) { "Invalid rest source." } }
    }
}
