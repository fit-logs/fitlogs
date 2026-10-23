package com.example.fitnotes.data

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// A transparent planning heuristic, not a measurement of strength or a claim
// that any rep/RIR formula predicts an individual's performance precisely.
data class LoadTarget(val id:Long,val position:Int,val min:Int?,val max:Int?,val mode:String,val warmUp:Boolean=false,val skipped:Boolean=false,val rir:Double?=null,val rpe:Double?=null)
data class LoadRequest(val exerciseId:Long,val name:String,val targets:List<LoadTarget>)
data class LoadHistorySet(val id:String,val kg:Double,val reps:Int,val mode:String,val warmUp:Boolean,val gym:String,val equipment:String,val basis:String,val rir:Double?=null,val rpe:Double?=null,val assumed:Boolean=false)
data class LoadHistorySession(val id:String,val date:String,val sets:List<LoadHistorySet>,val start:Long?=null,val end:Long?=null,val expected:Int?=null,val skipped:Boolean=false)
data class SuggestedLoad(val targetId:Long,val value:String,val unit:String,val reps:Int,val rir:Double)
data class LoadAdvice(val exerciseId:Long,val state:String,val confidence:String,val explanation:String,val loads:List<SuggestedLoad> = emptyList(),val retainedSets:Int=0,val excludedSets:Int=0)
object LoadAdviceEngine {
    private val kgPerLb=BigDecimal("0.45359237")
    private val math=MathContext.DECIMAL128
    private fun decimal(v:Double)=BigDecimal.valueOf(v)
    private fun fromKg(v:BigDecimal,unit:String)=if(unit=="lb")v.divide(kgPerLb,math) else v
    private fun rounded(v:BigDecimal,p:LoadProfile):BigDecimal=v.divide(BigDecimal(p.increment),0,RoundingMode.FLOOR).multiply(BigDecimal(p.increment)).stripTrailingZeros()
    fun suggest(request:LoadRequest,history:List<LoadHistorySession>,profile:LoadProfile?,today:LocalDate):LoadAdvice {
        fun manual(reason:String,kept:Int=0,dropped:Int=0)=LoadAdvice(request.exerciseId,"manual","Insufficient comparable evidence",reason,retainedSets=kept,excludedSets=dropped)
        if(profile==null)return manual("Confirm this exercise's gym, equipment, total/per-hand basis and increment. No starting weight is invented.")
        validateLoadProfile(profile)
        if(profile.mode!=LoadMode.EXTERNAL)return manual("${LoadMode.label(profile.mode)} needs a manual load/calibration choice. Assistance, body mass and external load are not interchangeable.")
        val targets=request.targets.filter{!it.warmUp && !it.skipped}.sortedBy{it.position}
        if(targets.isEmpty() || targets.size>10)return manual("Review the working-set prescription before choosing a load.")
        if(targets.any{it.mode !in setOf(LoadMode.EXTERNAL,LoadMode.UNKNOWN) || it.min==null || it.max==null || it.min !in 3..10 || it.max<it.min})return manual("Use explicit external working-set rep ranges; numerical suggestions are bounded to targets of 3–10 reps.")
        val targetReps=targets.map{if(profile.repChoice=="upper")it.max!! else it.min!!}
        if(targetReps.any{it !in 3..10})return manual("The selected rep goal is outside 3–10. Choose a manual calibration load.")
        val desired=targets.map{runCatching{reserve(it.rir,it.rpe) ?: profile.targetRir}.getOrNull()}
        if(desired.any{it==null || it !in 0.0..4.0})return manual("Review conflicting or unusually high effort targets before using a numeric suggestion.")
        var excluded=0
        val sessions=history.sortedWith(compareBy<LoadHistorySession>{it.date}.thenBy{it.start ?: 0L}.thenBy{it.id}).mapNotNull{s->
            val date=runCatching{LocalDate.parse(s.date)}.getOrNull()
            if(date==null || date>today || ChronoUnit.DAYS.between(date,today)>90){excluded+=s.sets.size;return@mapNotNull null}
            val rows=s.sets.distinctBy{it.id}.filter{r->
                val good=!r.warmUp && r.kg.isFinite() && r.kg>0 && r.reps in 3..10 && r.mode==profile.mode && r.gym==profile.gym && r.equipment==profile.equipment && r.basis==profile.basis && runCatching{reserve(r.rir,r.rpe)}.let{it.isSuccess && it.getOrNull()?.let{v->v<=4.0}!=false}
                if(!good)excluded++;good
            }
            if(rows.isEmpty())return@mapNotNull null
            val median=rows.map{it.kg}.sorted()[rows.size/2]
            val retained=rows.filter{val good=it.kg>=median*0.5 && it.kg<=median*1.5;if(!good)excluded++;good}
            if(retained.isEmpty())null else s.copy(sets=retained)
        }
        val kept=sessions.sumOf{it.sets.size}
        if(sessions.isEmpty())return manual("No comparable completed working sets. Plans, warm-ups, unknown setups/modes and unsaved drafts are excluded. Enter a manual start or calibration set.",kept,excluded)
        // Identical same-day signatures with absent/overlapping timing may be
        // duplicate imports. Hold advice; never merge/delete actual history.
        val possibleDuplicate=sessions.groupBy{it.date to it.sets.map{r->"${r.kg}:${r.reps}"}.sorted()}.values.any{group->group.size>1 && group.any{a->group.any{b->a.id!=b.id && (a.start==null || a.end==null || b.start==null || b.end==null || a.start<b.end && b.start<a.end)}}}
        if(possibleDuplicate)return manual("Possible duplicate comparable history on the same date. Review the source records; duplicates do not count as independent progression evidence.",kept,excluded)
        val dates=sessions.groupBy{it.date}.toSortedMap().values.map{it.last()}.takeLast(6)
        val latest=dates.last();val age=ChronoUnit.DAYS.between(LocalDate.parse(latest.date),today)
        if(age>42)return manual("Latest comparable session is ${latest.date} ($age days old). Recalibrate rather than reuse a precise stale load.",kept,excluded)
        if(dates.size<2)return manual("Only one comparable training date (${latest.date}). Record another comparable session or choose a manual calibration load.",kept,excluded)
        // Keep the common numerator intact: divide only once, so an exact
        // load boundary cannot become 79.999… and lose an equipment increment.
        fun capacity(s:LoadHistorySession)=s.sets.minOf{r->decimal(r.kg).multiply(BigDecimal("30").add(decimal((reserve(r.rir,r.rpe) ?: 0.0).coerceAtMost(4.0)+r.reps)),math)}
        val cap=capacity(latest)
        val previous=dates.dropLast(1).map{capacity(it).toDouble()}.sorted().let{it[it.size/2]}
        if(cap.toDouble()>previous*1.25 || cap.toDouble()<previous*0.75)return manual("Latest performance differs sharply from other comparable sessions. Review units, setup and fatigue before choosing a new numeric load.",kept,excluded)
        val assumed=dates.any{it.sets.any{r->r.assumed}};val missing=latest.sets.any{it.rir==null && it.rpe==null}
        val incomplete=latest.skipped || latest.expected?.let{latest.sets.size<it}==true || latest.sets.size<targets.size
        val uniform=latest.sets.maxOf{it.kg}<=latest.sets.minOf{it.kg}*1.15
        val success=dates.takeLast(2).all{s->!s.skipped && (s.expected==null || s.sets.size>=s.expected) && s.sets.size>=targets.size && kotlin.math.abs(s.sets.minOf{it.kg}-latest.sets.minOf{it.kg})<0.000001 && targets.indices.all{i->val row=s.sets[i];row.reps>=targets[i].max!! && reserve(row.rir,row.rpe)?.let{it>=maxOf(2.0,desired[i]!!)}==true && !row.assumed}}
        val plateau=dates.size>=3 && dates.takeLast(3).all{s->kotlin.math.abs(s.sets.minOf{it.kg}-latest.sets.minOf{it.kg})<0.000001 && s.sets.minOf{it.reps}>=latest.sets.minOf{it.reps}} && !success
        val baseline=fromKg(decimal(latest.sets.minOf{it.kg}),profile.unit)
        var state="hold"
        val loads=targets.mapIndexed { i,t->
            val denominator=BigDecimal("30").add(BigDecimal(targetReps[i])).add(decimal(desired[i]!!))
            var candidate=fromKg(cap.divide(denominator,math),profile.unit)
            if(missing && latest.sets.all{it.reps>=targetReps[i]} && !incomplete)candidate=baseline
            val step=BigDecimal(profile.increment)
            val canIncrease=success && uniform && !incomplete && age<=21 && !assumed && step<=baseline.multiply(BigDecimal("0.10"))
            candidate=candidate.min(if(canIncrease)baseline.add(step) else baseline)
            if(incomplete)candidate=candidate.multiply(BigDecimal("0.95"))
            if(plateau || age>21)candidate=candidate.min(baseline.multiply(BigDecimal("0.95")))
            val value=rounded(candidate,profile)
            if(value.signum()<=0 || value>BigDecimal("10000") || value<baseline.multiply(BigDecimal("0.80")))return manual("The conservative bound falls below 80% of the latest load or below one equipment step. Review/calibrate manually.",kept,excluded)
            if(value>baseline)state="progress" else if(value<baseline && state!="progress")state=if(plateau || age>21)"deload" else "reduce"
            SuggestedLoad(t.id,value.toPlainString(),profile.unit,targetReps[i],desired[i]!!)
        }
        val source=latest.sets.joinToString(", "){"${weightText(it.kg)} kg × ${it.reps}"}
        val reasons=buildList{
            add("Based on ${latest.date}: $source; ${dates.size} comparable training dates.")
            add("${profile.gym} · ${profile.equipment} · ${profile.basis.replace('_',' ')}. Rounded down to ${profile.increment} ${profile.unit}.")
            if(success && state=="progress")add("Both recent sessions met every upper rep target with reported headroom; at most one increment is proposed.")
            if(missing)add("RIR/RPE was not recorded: no increase. Rep translation uses logged reps only; missing effort is not saved as zero.")
            if(assumed)add("Includes legacy work sets under your explicit same-setup assumption; lower confidence.")
            if(incomplete)add("Fewer working sets or a skipped prescription: no increase and a conservative volume reduction.")
            if(plateau)add("Three-date plateau: optional 5% deload proposal; review recovery/programming.")
            if(age>21)add("More than 21 days since the last comparable session: reduced reuse load.")
            if(!uniform)add("Mixed working loads: no automatic progression increment.")
            add("${kept} actual sets retained; $excluded excluded. This planning heuristic is not a measured capacity or a guaranteed RIR.")
        }.joinToString("\n")
        return LoadAdvice(request.exerciseId,state,if(missing || assumed || incomplete || age>21)"Limited evidence" else "Comparable recent history",reasons,loads,kept,excluded)
    }
}
