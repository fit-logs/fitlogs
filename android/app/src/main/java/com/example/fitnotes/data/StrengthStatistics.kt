package com.example.fitnotes.data

import com.example.fitnotes.AppData
import com.example.fitnotes.ui.notebookSessions
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// Calculations below operate only on actual records; no target counts or invented body mass.
data class StrengthResult(val exerciseKey: String, val exerciseName: String, val weightKg: Double?, val reps: Int, val mode: String, val warmUp: Boolean = false)
data class TrainingObservation(val id: String, val date: String, val completed: Boolean, val started: Long?, val finished: Long?, val results: List<StrengthResult>, val activity: String = "strength")
data class ExerciseStatistics(val key: String, val name: String, val setCount: Int, val totalReps: Int, val externalVolume: Double,
    val addedBodyweightVolume: Double, val unknownModeVolume: Double, val heaviestExternal: Double?, val estimatedOneRepMax: Double?,
    val repsAtWeight: Map<Pair<String,Double>,Int>, val volumeByDate: Map<String,Double>, val volumeByModeDate: Map<String,Map<String,Double>>, val repsByDate: Map<String,Double>)
data class WeeklyTraining(val week: String, val workouts: Int, val sets: Int)
data class TrainingStatisticsResult(val exercises: List<ExerciseStatistics>, val weeks: List<WeeklyTraining>, val completedWorkouts: Int, val actualSets: Int)
fun estimateText(value:Double)=java.math.BigDecimal.valueOf(value).setScale(1,java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
fun estimatedOneRepMax(kg: Double?, reps: Int, mode: String, warmUp: Boolean): Double? =
    if (kg==null || kg<=0 || mode!=LoadMode.EXTERNAL || warmUp || reps !in 1..10) null else if(reps==1)kg else kg*(1+reps/30.0)
fun observations(data: AppData): List<TrainingObservation> = notebookSessions(data).map { session ->
    TrainingObservation(session.key,session.date,session.completed,session.startedMillis,session.completedMillis,
        session.exercises.flatMap { exercise -> exercise.sets.mapNotNull { set ->
            if(set.reps==null || set.reps<=0) null else StrengthResult(exercise.canonicalId?.let{"exercise:$it"} ?: "source:${session.imported?.workout?.importId}:${exercise.name}:${exercise.category}",
                exercise.name,set.weight,set.reps,set.local?.loadMode ?: LoadMode.UNKNOWN,set.local?.isWarmUp ?: false)
        } },session.activity)
}
object StrengthStatistics {
    fun calculate(observations: List<TrainingObservation>): TrainingStatisticsResult {
        val completed=observations.filter{it.completed}
        val exercises=completed.flatMap{session->session.results.map{session.date to it}}.groupBy{it.second.exerciseKey}.map { (key, rows) ->
            val results=rows.map{it.second}
            fun volume(mode:String)=results.filter{it.mode==mode}.sumOf{(it.weightKg?:0.0)*it.reps}
            ExerciseStatistics(key,results.first().exerciseName,results.size,results.sumOf{it.reps},volume(LoadMode.EXTERNAL),volume(LoadMode.BODYWEIGHT),volume(LoadMode.UNKNOWN),
                results.filter{it.mode==LoadMode.EXTERNAL}.mapNotNull{it.weightKg}.maxOrNull(),results.mapNotNull{estimatedOneRepMax(it.weightKg,it.reps,it.mode,it.warmUp)}.maxOrNull(),
                results.filter{it.weightKg!=null}.groupBy{it.mode to it.weightKg!!}.mapValues{(_,sets)->sets.maxOf{it.reps}},
                rows.filter{it.second.mode==LoadMode.EXTERNAL && it.second.weightKg!=null}.groupBy{it.first}.toSortedMap().mapValues{(_,sets)->sets.sumOf{it.second.weightKg!!*it.second.reps}},
                results.map{it.mode}.distinct().associateWith{mode->rows.filter{it.second.mode==mode&&it.second.weightKg!=null}.groupBy{it.first}.toSortedMap().mapValues{(_,sets)->sets.sumOf{it.second.weightKg!!*it.second.reps}}},
                rows.groupBy{it.first}.toSortedMap().mapValues{(_,sets)->sets.sumOf{it.second.reps}.toDouble()})
        }.sortedBy{it.name.lowercase(Locale.ROOT)}
        val weeks=completed.groupBy{weekStart(it.date)}.toSortedMap().map{(week,sessions)->WeeklyTraining(week,sessions.size,sessions.sumOf{it.results.size})}
        return TrainingStatisticsResult(exercises,weeks,completed.size,completed.sumOf{it.results.size})
    }
    fun earlier(candidate: TrainingObservation,current: TrainingObservation):Boolean = candidate.completed && candidate.id!=current.id &&
        (if(candidate.finished!=null&&current.started!=null)candidate.finished<current.started else candidate.date<current.date)
    fun summary(current:TrainingObservation,all:List<TrainingObservation>):String = buildString {
        val stats=calculate(listOf(current.copy(completed=true)))
        appendLine("${current.results.size} actual strength sets · ${current.results.sumOf{it.reps}} reps")
        stats.exercises.forEach { exercise ->
            appendLine(exercise.name)
            if(exercise.externalVolume>0)appendLine("  External-load volume: ${weightText(exercise.externalVolume)} kg·reps")
            if(exercise.addedBodyweightVolume>0)appendLine("  Added-load volume: ${weightText(exercise.addedBodyweightVolume)} kg·reps; body mass excluded")
            if(exercise.unknownModeVolume>0)appendLine("  Recorded-load volume: ${weightText(exercise.unknownModeVolume)} kg·reps; load mode unknown")
            exercise.estimatedOneRepMax?.let{appendLine("  Estimated 1RM: ${estimateText(it)} kg (Epley, external load, 1–10 reps)")}
            val candidates=all.filter{earlier(it,current)&&it.results.any{r->r.exerciseKey==exercise.key}}
            val modes=current.results.filter{it.exerciseKey==exercise.key}.map{it.mode}.toSet()
            var compared=false
            for(mode in modes) {
                val latest=candidates.filter{it.results.any{r->r.exerciseKey==exercise.key&&r.mode==mode}}.maxWithOrNull(compareBy<TrainingObservation>{it.date}.thenBy{it.finished?:0}) ?: continue
                compared=true
                val previousResults=latest.results.filter{it.exerciseKey==exercise.key&&it.mode==mode}
                val currentResults=current.results.filter{it.exerciseKey==exercise.key&&it.mode==mode}
                if(mode==LoadMode.REPS_ONLY || mode==LoadMode.ASSISTANCE) {
                    val delta=currentResults.sumOf{it.reps}-previousResults.sumOf{it.reps}
                    appendLine("  Previous ${LoadMode.label(mode).lowercase()} session: ${latest.date}; actual rep change $delta.${if(mode==LoadMode.ASSISTANCE)" Assistance is not external-load volume; compare recorded assistance separately." else ""}")
                } else {
                    val before=previousResults.sumOf{(it.weightKg?:0.0)*it.reps}
                    val now=currentResults.sumOf{(it.weightKg?:0.0)*it.reps}
                    appendLine("  Previous ${LoadMode.label(mode).lowercase()} session: ${latest.date}; recorded-load volume change ${weightText(now-before)} kg·reps (body mass excluded).")
                }
            }
            if(!compared)appendLine("  No previous comparable session; first observation is not a record.")
            val oldBest=candidates.flatMap{it.results}.filter{it.exerciseKey==exercise.key}.mapNotNull{estimatedOneRepMax(it.weightKg,it.reps,it.mode,it.warmUp)}.maxOrNull()
            if(oldBest!=null && exercise.estimatedOneRepMax!=null && exercise.estimatedOneRepMax>oldBest)appendLine("  New estimated 1RM record compared with earlier sessions.")
        }
    }.trim()
    fun context(observations:List<TrainingObservation>):String {
        val stats=calculate(observations)
        return buildString {
            appendLine("${stats.completedWorkouts} completed sessions; ${stats.actualSets} actual strength sets.")
            appendLine("Volume uses recorded kg × reps. External, added-bodyweight and unknown-mode loads remain separate. Body mass is never inferred; estimates use external working sets of 1–10 reps only.")
            stats.exercises.forEach { e -> appendLine("${e.name}: ${e.setCount} sets, ${e.totalReps} reps; external ${weightText(e.externalVolume)}, added-bodyweight ${weightText(e.addedBodyweightVolume)}, unknown-mode ${weightText(e.unknownModeVolume)} kg·reps${e.estimatedOneRepMax?.let{"; estimated 1RM ${estimateText(it)} kg"}.orEmpty()}") }
        }.trim()
    }
    private fun weekStart(date:String):String {
        val format=SimpleDateFormat("yyyy-MM-dd",Locale.ROOT)
        val calendar=Calendar.getInstance().apply{time=format.parse(date)!!;firstDayOfWeek=Calendar.MONDAY}
        calendar.add(Calendar.DAY_OF_MONTH,-((calendar.get(Calendar.DAY_OF_WEEK)+5)%7))
        return format.format(calendar.time)
    }
}

data class ProgressionSuggestion(val exerciseId: Long, val targets: List<PlanSetTarget>, val explanation: String, val canIncrease: Boolean)
fun doubleProgression(exerciseId:Long, targets:List<WorkoutSetTarget>, results:List<WorkoutSet>, config:ExerciseProgression?):ProgressionSuggestion? {
    if(config?.enabled!=true) return null
    val working=targets.sortedBy{it.position}.filter{!it.isWarmUp}
    fun hold(reason:String)=ProgressionSuggestion(exerciseId,targets.sortedBy{it.position}.map{it.target},reason,false)
    if(working.isEmpty())return hold("Hold: no prescribed working sets.")
    if(working.any{it.skipped})return hold("Hold: a prescribed working set was skipped.")
    if(working.any{t->results.none{it.targetId==t.id}})return hold("Hold: not all ${working.size} prescribed working sets were logged.")
    val ready=working.all { t ->
        val actual=results.single{it.targetId==t.id}; val kg=targetWeightKg(t.target).toDoubleOrNull()
        !actual.isWarmUp && t.repsMax!=null && actual.reps>=t.repsMax && t.loadMode in setOf(LoadMode.EXTERNAL,LoadMode.BODYWEIGHT) && actual.loadMode==t.loadMode && kg!=null && actual.weightKg+1e-9>=kg
    }
    if(!ready)return hold("Hold: every prescribed working set must meet its upper rep target and prescribed load in a compatible load mode.")
    if(working.any { it.weightUnit !in setOf(config.unit,"bodyweight") }) return hold("Hold: configure the increase in the prescription unit. Mixed kg/lb targets require manual review; loads are never rounded silently.")
    val increment=java.math.BigDecimal(config.increment)
    val next=try { targets.sortedBy{it.position}.map { t ->
        if(t.isWarmUp)t.target else {
            val unit=if(t.weightUnit=="bodyweight")config.unit else t.weightUnit!!
            val delta=increment
            val value=java.math.BigDecimal(t.weightValue?:"0").add(delta).stripTrailingZeros().toPlainString()
            t.target.copy(weight=PlanWeight(value,unit)).also{WorkoutPlanParser.validateSet(it)}
        }
    }
    } catch (_:IllegalArgumentException) { return hold("Hold: the configured increase exceeds the supported prescription limits. Review loads manually.") }
    return ProgressionSuggestion(exerciseId,next,"Completed all ${working.size} prescribed working sets at their upper rep targets. Suggest +${config.increment} ${config.unit}; begin again at each lower rep target. Apply explicitly to a reusable plan.",true)
}
