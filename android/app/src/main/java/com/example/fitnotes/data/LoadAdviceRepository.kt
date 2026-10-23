package com.example.fitnotes.data

import androidx.room.withTransaction
import java.time.LocalDate
import java.util.Collections
import java.util.UUID

/** Keys and units are explicit. Legacy grouping is a user preference, never a
 * rewrite of imported weights, load modes, warm-up flags or recorded effort. */
fun comparableLoadHistory(b:Backup,exerciseId:Long,p:LoadProfile?):List<LoadHistorySession> {
    val context=b.setLoadContexts.associateBy{it.setId};val entries=b.entries.filter{it.exerciseId==exerciseId};val workouts=b.workouts.associateBy{it.id}
    val sets=b.sets.groupBy{it.entryId};val targetByEntry=b.runTargets.groupBy{it.entryId}
    fun row(id:String,kg:Double,reps:Int,mode:String,warm:Boolean,c:SetLoadContext?):LoadHistorySet {
        val unknown=c==null || c.gym.isBlank() || mode==LoadMode.UNKNOWN
        val assumed=unknown && p?.reviewedLegacy==true
        return LoadHistorySet(id,kg,reps,if(mode==LoadMode.UNKNOWN && assumed)p!!.mode else mode,warm,
            if(c?.gym?.isNotBlank()==true)c.gym else if(assumed)p!!.gym else "",
            if(c?.equipment?.isNotBlank()==true)c.equipment else if(assumed)p!!.equipment else "",
            if(c?.basis!=null && c.basis!="unknown")c.basis else if(assumed)p!!.basis else "unknown",c?.rir,c?.rpe,assumed)
    }
    val local=entries.mapNotNull{e->
        val w=workouts[e.workoutId] ?: return@mapNotNull null
        if(!w.isComplete || w.historicalDraft)return@mapNotNull null
        val actual=sets[e.id].orEmpty().filter{it.weightSpecified && it.reps>0}.sortedBy{it.position}.map{row("local:${it.id}",it.weightKg,it.reps,it.loadMode,it.isWarmUp,context[it.id])}
        if(actual.isEmpty())return@mapNotNull null
        val prescribed=targetByEntry[e.id].orEmpty().filter{!it.isWarmUp}
        LoadHistorySession("local:${w.id}:${e.id}",localWorkoutDate(w),actual,w.knownStart,w.completedAt,prescribed.size.takeIf{it>0},prescribed.any{it.skipped})
    }
    val imports=b.imports.associateBy{it.id};val importedWorkouts=b.importedWorkouts.associateBy{it.id};val aliases=aliasMap(b.exerciseAliases)
    val names=b.exercises.groupBy{normaliseExerciseName(it.name)}.mapValues{(_,v)->v.map{it.id}.toSet()}
    val mappings=b.exerciseMappings.associate{Triple(it.importId,it.sourceName,it.category) to it.exerciseId}
    val imported=b.importedSets.filter{s->
        if(s.weightKg==null || s.reps==null || s.reps<=0)return@filter false
        val w=importedWorkouts[s.workoutId] ?: return@filter false
        if(w.importId !in imports)return@filter false
        val key=normaliseExerciseName(s.exercise)
        val mapped=mappings[Triple(w.importId,s.exercise,s.category)] ?: (names[key].orEmpty()+aliases[key].orEmpty()).singleOrNull()
        mapped==exerciseId
    }.groupBy{it.workoutId}.map{(id,values)->
        val w=importedWorkouts.getValue(id)
        fun instant(raw:String?)=raw?.let{runCatching{java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli()}.getOrNull()}
        LoadHistorySession("import:$id",w.date,values.sortedBy{it.sourceOrder}.distinctBy{it.id}.map{row("import:${it.id}",it.weightKg!!,it.reps!!,LoadMode.UNKNOWN,false,null)},instant(w.recordedStart),instant(w.recordedEnd))
    }
    return (local+imported).sortedWith(compareBy<LoadHistorySession>{it.date}.thenBy{it.id})
}
data class LoadAdviceSubject(val kind:String,val key:String,val exerciseId:Long,val name:String,val request:LoadRequest,val signature:String)
data class ReviewedLoadAdvice(val subject:LoadAdviceSubject,val advice:LoadAdvice,val profile:LoadProfile?,val fingerprint:String,val locked:Boolean,val hasActual:Boolean)

class LoadAdviceRepository(private val db:FitDatabase,private val today:()->LocalDate=LocalDate::now) {
    private val cache=Collections.synchronizedMap(object:LinkedHashMap<String,LoadAdvice>(32,0.75f,true){override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,LoadAdvice>?)=size>32})
    suspend fun saveProfile(value:LoadProfile)=db.withTransaction {
        validateLoadProfile(value);require(db.dao().exercise(value.exerciseId)!=null){"Exercise no longer exists."}
        val profiles=db.loadDao().profiles()
        require(!value.reviewedLegacy || profiles.none{it.exerciseId==value.exerciseId && it.reviewedLegacy && listOf(it.gym,it.equipment,it.basis,it.mode)!=listOf(value.gym,value.equipment,value.basis,value.mode)}) { "Reviewed legacy records already belong to another setup. Turn off that assumption before assigning a different gym/equipment/basis." }
        val existing=profiles.firstOrNull{it.exerciseId==value.exerciseId && it.gym==value.gym && it.equipment==value.equipment && it.basis==value.basis && it.mode==value.mode}
        db.loadDao().deselect(value.exerciseId)
        db.loadDao().putProfile(value.copy(id=existing?.id ?: UUID.randomUUID().toString(),selected=true))
    }
    suspend fun selectProfile(id:String)=db.withTransaction {val p=requireNotNull(db.loadDao().profiles().firstOrNull{it.id==id});db.loadDao().deselect(p.exerciseId);db.loadDao().putProfile(p.copy(selected=true))}
    suspend fun lock(entryId:Long,locked:Boolean)=db.withTransaction {require(db.dao().entry(entryId)!=null);if(locked)db.loadDao().putLock(SessionLoadLock(entryId)) else db.loadDao().unlock(entryId)}
    suspend fun plan(id:String):List<ReviewedLoadAdvice> = db.withTransaction {
        val plan=requireNotNull(db.planDao().detail(id)){"Template/day no longer exists."};val b=WorkoutRepository(db).backup()
        plan.ordered.map{e->
            val targets=e.sets.sortedBy{it.position};val request=LoadRequest(e.exercise.id,e.exercise.name,targets.map{LoadTarget(it.id,it.position,it.repsMin,it.repsMax,it.loadMode,it.isWarmUp,rir=it.rir,rpe=it.rpe)})
            review(LoadAdviceSubject("plan",id,e.exercise.id,e.exercise.name,request,targets.toString()),b)
        }
    }
    suspend fun entry(id:Long):ReviewedLoadAdvice = db.withTransaction {
        val e=requireNotNull(db.dao().entry(id));val exercise=requireNotNull(db.dao().exercise(e.exerciseId));val targets=db.planDao().runTargets().filter{it.entryId==id}.sortedBy{it.position}
        val request=LoadRequest(e.exerciseId,exercise.name,targets.map{LoadTarget(it.id,it.position,it.repsMin,it.repsMax,it.loadMode,it.isWarmUp,it.skipped,it.rir,it.rpe)})
        review(LoadAdviceSubject("entry",id.toString(),exercise.id,exercise.name,request,targets.toString()),WorkoutRepository(db).backup())
    }
    private fun review(subject:LoadAdviceSubject,b:Backup):ReviewedLoadAdvice {
        val p=b.loadProfiles.firstOrNull{it.exerciseId==subject.exerciseId && it.selected};val history=comparableLoadHistory(b,subject.exerciseId,p);val date=today()
        val fingerprint=planFingerprint(subject.toString()+p.toString()+history.toString()+date)
        val advice=cache[fingerprint] ?: LoadAdviceEngine.suggest(subject.request,history,p,date).also{cache[fingerprint]=it}
        val entryId=subject.key.toLongOrNull().takeIf{subject.kind=="entry"}
        val hasActual=entryId?.let{id->b.sets.any{it.entryId==id}} ?: false
        return ReviewedLoadAdvice(subject,advice,p,fingerprint,entryId?.let{id->b.sessionLoadLocks.any{it.entryId==id}} ?: false,hasActual)
    }
    suspend fun use(reviewed:ReviewedLoadAdvice,lockAfter:Boolean=true)=db.withTransaction {
        val fresh=if(reviewed.subject.kind=="plan")plan(reviewed.subject.key).single{it.subject.exerciseId==reviewed.subject.exerciseId} else entry(reviewed.subject.key.toLong())
        require(fresh.fingerprint==reviewed.fingerprint){"History, setup or targets changed. Review a fresh suggestion."}
        require(fresh.advice.loads.isNotEmpty()){ "Choose a manual start/calibration load." }
        write(fresh,fresh.advice.loads.map{it.targetId to PlanWeight(it.value,it.unit)}.toMap(),fresh.profile!!.mode,lockAfter)
    }
    suspend fun change(reviewed:ReviewedLoadAdvice,weight:String,mode:String,lockAfter:Boolean)=db.withTransaction {
        val fresh=if(reviewed.subject.kind=="plan")plan(reviewed.subject.key).single{it.subject.exerciseId==reviewed.subject.exerciseId} else entry(reviewed.subject.key.toLong())
        require(fresh.fingerprint==reviewed.fingerprint){"Targets changed. Reopen the weight review."}
        val w=WorkoutPlanParser.parseWeight(weight);require(w.unit!=null){"Enter an explicit kg/lb weight or bodyweight."}
        val rows=fresh.subject.request.targets.filter{!it.warmUp && !it.skipped};require(rows.isNotEmpty()){ "Add target sets before choosing target weights." }
        write(fresh,rows.associate{it.id to w},mode,lockAfter)
    }
    private suspend fun write(review:ReviewedLoadAdvice,weights:Map<Long,PlanWeight>,mode:String,lockAfter:Boolean){
        require(mode in LoadMode.values && mode!=LoadMode.UNKNOWN){"Confirm the load mode for this target change."}
        if(review.subject.kind=="entry") {
            val id=review.subject.key.toLong();val e=requireNotNull(db.dao().entry(id));val w=requireNotNull(db.dao().workout(e.workoutId))
            require(!w.isComplete && !w.historicalDraft && db.dao().entrySets(id).isEmpty()){ "Logging has started or this session is completed. Existing targets stay unchanged; override actual weights in the logger." }
            require(!review.locked){"Targets are locked for this session. Unlock explicitly before changing them."}
            db.planDao().runTargets().filter{it.entryId==id}.forEach{row->weights[row.id]?.let{weight->val t=row.target.copy(weight=weight,loadMode=mode);WorkoutPlanParser.validateSet(t);db.trainingDao().updateTarget(row.copy(weightValue=weight.value,weightUnit=weight.unit,loadMode=mode))}}
            if(lockAfter)db.loadDao().putLock(SessionLoadLock(id))
        } else {
            val plan=requireNotNull(db.planDao().detail(review.subject.key));val exercise=plan.ordered.single{it.exercise.id==review.subject.exerciseId}
            exercise.sets.forEach{row->weights[row.id]?.let{weight->val t=row.target.copy(weight=weight,loadMode=mode);WorkoutPlanParser.validateSet(t);db.trainingDao().updatePlannedSet(row.copy(weightValue=weight.value,weightUnit=weight.unit,loadMode=mode))}}
            val first=requireNotNull(db.planDao().detail(plan.plan.id)).ordered.single{it.exercise.id==review.subject.exerciseId}.sets.minByOrNull{it.position}
            if(first!=null)db.trainingDao().updatePlannedExercise(exercise.target.copy(weightValue=first.weightValue,weightUnit=first.weightUnit))
        }
    }
}
