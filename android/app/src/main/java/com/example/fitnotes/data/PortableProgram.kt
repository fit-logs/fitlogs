package com.example.fitnotes.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

/** Portable definitions are prescriptions, never performed workouts. Unknown
 * optional fields survive normalization/editing; unsupported major schemas fail. */
data class PortableProgram(val json: String) {
    val document get() = JSONObject(json)
    val id get() = document.getString("id")
    val version get() = document.getString("programVersion")
    val name get() = document.getString("name")
    val weeks get() = document.getInt("weeks")
    val fingerprint get() = planFingerprint(json)
}
data class ProgramImportDay(val key: String, val plan: EditablePlan, val metadata: String)
data class ProgramImportDraft(val source: PortableProgram, val name: String, val description: String, val weeks: String,
    val days: List<ProgramImportDay>, val exerciseMetadata: Map<String,String>, val setMetadata: Map<String,String>) {
    fun validated(): Pair<PortableProgram,List<PlanPreview>> {
        val root=source.document.put("name",name.trim()).put("description",description).put("weeks",weeks.toIntOrNull() ?: 0)
        val previews=days.map { it.plan.validated() }
        root.put("daysPerWeek",days.size)
        root.put("days",JSONArray(days.mapIndexed { index,day ->
            val d=JSONObject(day.metadata).put("id",day.key).put("name",previews[index].spec.title).put("durationMinutes",previews[index].spec.durationMinutes).put("notes",previews[index].spec.note)
            d.put("exercises",JSONArray(day.plan.exercises.mapIndexed { eIndex,e ->
                val row=JSONObject(exerciseMetadata[e.key] ?: "{}").put("name",e.sourceName).put("notes",e.note)
                row.put("sets",JSONArray(e.targets.mapIndexed { position,set ->
                    val t=previews[index].resolutions[eIndex].prescription.setTargets[position]
                    JSONObject(setMetadata[set.key] ?: "{}").put("repsMin",t.repsMin ?: JSONObject.NULL).put("repsMax",t.repsMax ?: JSONObject.NULL)
                        .put("weight",t.weight.value ?: JSONObject.NULL).put("unit",t.weight.unit ?: JSONObject.NULL).put("restSeconds",t.restSeconds ?: JSONObject.NULL)
                        .put("loadMode",t.loadMode).put("isWarmUp",t.isWarmUp)
                }));row
            }));d
        }))
        return PortableProgramParser.parse(root.toString()) to previews
    }
}
object PortableProgramParser {
    const val MAX_INPUT=256000
    fun parse(raw:String):PortableProgram {
        require(raw.toByteArray(Charsets.UTF_8).size<=MAX_INPUT) { "Program input is limited to 256 KB." }
        val text=raw.replace("\r\n","\n").replace('\r','\n').trim().removePrefix("\uFEFF").trim()
        val cleaned=if(text.startsWith("```")) text.lines().drop(1).dropLastWhile{it.isBlank()}.let { if(it.lastOrNull()?.trim()=="```")it.dropLast(1).joinToString("\n") else throw IllegalArgumentException("Close the program JSON code fence.") } else text
        val body=if(cleaned.startsWith("FITNOTES_PROGRAM_")) {
            require(cleaned.startsWith("FITNOTES_PROGRAM_V1\n") && cleaned.trimEnd().endsWith("END_FITNOTES_PROGRAM")) { "Use FITNOTES_PROGRAM_V1 and END_FITNOTES_PROGRAM around one JSON object." }
            cleaned.substringAfter('\n').substringBeforeLast("END_FITNOTES_PROGRAM").trim()
        } else cleaned
        val root=if(body.startsWith("{")) {
            val validator=StrictJson(body)
            try { validator.check();JSONObject(body) } catch(e:Exception){val before=body.take(validator.failureOffset);val line=before.count{it=='\n'}+1;val column=before.substringAfterLast('\n').length+1;throw IllegalArgumentException("Program JSON line $line, column $column: ${e.message ?: "check syntax and duplicate keys"}")}
        } else parseText(body)
        normalize(root)
        return PortableProgram(canonical(root))
    }
    fun validUrl(value:String):Boolean = value.isBlank() || runCatching {
        val u=URI(value);value.length<=2048 && u.scheme in setOf("https","http") && !u.host.isNullOrBlank() && u.rawUserInfo==null && !value.any(Char::isISOControl)
    }.getOrDefault(false)
    private fun normalize(root:JSONObject) {
        fun text(o:JSONObject,key:String,max:Int,required:Boolean=false):String {
            val v=if(o.has(key) && !o.isNull(key))o.get(key) else ""
            require(v is String && v.length<=max && (!required || v.isNotBlank()) && v.none{it.isISOControl() && it !in "\n\r\t"}) { "$key must be readable text up to $max characters." }
            return v.trim()
        }
        fun integer(o:JSONObject,key:String,default:Int,range:IntRange):Int {
            if(!o.has(key) || o.isNull(key))return default
            val v=o.get(key);require((v is Int || v is Long) && (v as Number).toLong() in range.first.toLong()..range.last.toLong()) { "$key must be a whole number from ${range.first} to ${range.last}." };return v.toInt()
        }
        fun identifier(value:String,label:String) { require(value.length in 1..128 && Regex("[A-Za-z0-9][A-Za-z0-9._:/-]*").matches(value)) { "$label needs a stable readable ID (letters, numbers, . _ : / -)." } }
        if(!root.has("schemaVersion"))root.put("schemaVersion",1)
        require(integer(root,"schemaVersion",1,1..Int.MAX_VALUE)==1) { "Unsupported program schemaVersion. Use 1." }
        if(!root.has("name") && root.has("title"))root.put("name",root.get("title"))
        if(!root.has("days") && root.has("workouts"))root.put("days",root.get("workouts"))
        root.remove("title");root.remove("workouts")
        val name=text(root,"name",200,true);root.put("name",name)
        if(!root.has("programVersion"))root.put("programVersion","1")
        val version=root.get("programVersion");require(version is String || version is Int || version is Long) { "programVersion must be a string or integer." };root.put("programVersion",version.toString());identifier(version.toString(),"Program version")
        root.put("weeks",integer(root,"weeks",4,1..52));root.put("description",text(root,"description",2000))
        if(root.has("startDate"))require(validHistoryDate(text(root,"startDate",10))) { "startDate must be YYYY-MM-DD." }
        for(key in listOf("goals","equipment")) {
            val v=root.opt(key);if(v is String)root.put(key,JSONArray(listOf(v)))
            val values=root.optJSONArray(key) ?: if(!root.has(key))JSONArray() else throw IllegalArgumentException("$key must be a text list.")
            require(values.length()<=30 && (0 until values.length()).all{values.get(it) is String && values.getString(it).length<=200 && values.getString(it).none(Char::isISOControl)}) { "Check $key (up to 30 short text values)." };root.put(key,values)
        }
        for(key in listOf("level","progression","schedule"))if(root.has(key)) {
            val value=root.get(key);require(value is String || value is JSONObject || value is JSONArray) { "$key must be text or structured guidance." }
            require(value.toString().length<=4000) { "$key guidance is limited to 4,000 characters." }
        }
        val source=root.optJSONObject("source") ?: JSONObject().also{require(!root.has("source") || root.isNull("source")) { "source must be an object." }}
        for((old,key) in listOf("sourceUrl" to "url","author" to "author","attribution" to "attribution","sourceTitle" to "title"))if(root.has(old)){if(!source.has(key))source.put(key,root.get(old));root.remove(old)}
        for(key in listOf("url","title","author","attribution"))source.put(key,text(source,key,if(key=="url")2048 else 2000))
        require(validUrl(source.getString("url"))) { "source.url must be an http/https URL without embedded credentials." };root.put("source",source)
        val days=root.optJSONArray("days") ?: throw IllegalArgumentException("days must contain ordered workout days.")
        require(days.length() in 1..14) { "Include 1–14 workout days." }
        root.put("daysPerWeek",integer(root,"daysPerWeek",days.length(),1..14));require(root.getInt("daysPerWeek")==days.length()) { "daysPerWeek must match this ordered weekly day list." }
        val dayIds=mutableSetOf<String>()
        for(i in 0 until days.length())try {
            val d=days.getJSONObject(i)
            if(!d.has("name") && d.has("title"))d.put("name",d.get("title"));d.remove("title")
            if(!d.has("id"))d.put("id","day-${i+1}");val id=text(d,"id",128,true);identifier(id,"Day");d.put("id",id);require(dayIds.add(id)) { "Duplicate day ID $id." }
            if(d.has("date"))require(validHistoryDate(text(d,"date",10))) { "date must be YYYY-MM-DD." }
            d.put("name",text(d,"name",200,true));d.put("notes",text(d,"notes",2000));d.put("durationMinutes",integer(d,"durationMinutes",0,0..720))
            val exercises=d.optJSONArray("exercises") ?: throw IllegalArgumentException("exercises must be an ordered list.")
            require(exercises.length() in 1..50) { "Include 1–50 explicit exercises." }
            for(j in 0 until exercises.length())try {
                val e=exercises.getJSONObject(j)
                if(!e.has("name") && e.has("exercise"))e.put("name",e.get("exercise"));e.remove("exercise")
                e.put("name",InputRules.exerciseName(text(e,"name",200,true)))
                if(e.has("canonicalId") && !e.isNull("canonicalId"))identifier(text(e,"canonicalId",128,true),"Exercise")
                if(!e.has("sourceName"))e.put("sourceName",e.getString("name"));text(e,"sourceName",200)
                if(!e.has("notes") && e.has("note"))e.put("notes",e.get("note"));e.remove("note");e.put("notes",text(e,"notes",2000))
                val substitutions=e.optJSONArray("substitutions")
                require(!e.has("substitutions") || substitutions!=null && substitutions.length()<=20 && (0 until substitutions.length()).all{substitutions.get(it) is String && substitutions.getString(it).length<=200}) { "substitutions must be a short text list; they are guidance, never automatic replacements." }
                val sets=when(val v=e.opt("sets")) {
                    null,JSONObject.NULL -> JSONArray()
                    is JSONArray -> v
                    is Int,is Long -> JSONArray(List(integer(e,"sets",0,1..100)) { JSONObject(e.toString()).also { r -> listOf("sets","name","sourceName","canonicalId","substitutions","notes").forEach(r::remove) } })
                    else -> throw IllegalArgumentException("sets must be a count or ordered target list.")
                }
                require(sets.length()<=100) { "Use at most 100 target sets." }
                for(k in 0 until sets.length())try {
                    val s=sets.getJSONObject(k)
                    // An explicit set prescription wins as a whole. Exercise
                    // defaults must not replace a top/back-off set's own reps.
                    if(!s.has("reps") && !s.has("repsMin") && !s.has("repsMax")) {
                        if(e.has("repsMin"))s.put("repsMin",e.get("repsMin")) else if(e.has("repRangeMin"))s.put("repsMin",e.get("repRangeMin"))
                        if(e.has("repsMax"))s.put("repsMax",e.get("repsMax")) else if(e.has("repRangeMax"))s.put("repsMax",e.get("repRangeMax"))
                        if(!s.has("repsMin") && e.has("reps"))s.put("reps",e.get("reps"))
                    }
                    if(s.has("reps") && !s.isNull("reps")) {
                        val reps=s.get("reps").toString();val m=Regex("([0-9]+)(?:\\s*[-–]\\s*([0-9]+))?").matchEntire(reps) ?: throw IllegalArgumentException("reps must be a whole count or range such as 8-12.")
                        if(!s.has("repsMin"))s.put("repsMin",m.groupValues[1].toInt());if(!s.has("repsMax"))s.put("repsMax",m.groupValues[2].ifBlank{m.groupValues[1]}.toInt());s.remove("reps")
                    }
                    val low=if(s.isNull("repsMin") || !s.has("repsMin"))null else integer(s,"repsMin",0,1..1000)
                    val high=if(s.isNull("repsMax") || !s.has("repsMax"))low else integer(s,"repsMax",0,1..1000)
                    s.put("repsMin",low ?: JSONObject.NULL).put("repsMax",high ?: JSONObject.NULL)
                    for(key in listOf("restSeconds","rir","rpe","loadMode","isWarmUp"))if(!s.has(key) && e.has(key))s.put(key,e.get(key))
                    for((key,range) in listOf("rir" to 0.0..10.0,"rpe" to 1.0..10.0))if(s.has(key) && !s.isNull(key)){val value=s.get(key);require(value is Number && value.toDouble().isFinite() && value.toDouble() in range) { "$key is out of range." }}
                    if(!s.has("weight") && e.has("weight"))s.put("weight",e.get("weight"));if(!s.has("unit") && e.has("unit"))s.put("unit",e.get("unit"))
                    val unit=if(s.isNull("unit") || !s.has("unit"))null else text(s,"unit",20)
                    val weight=if(s.isNull("weight") || !s.has("weight"))null else s.get("weight").also{require(it is Number || it is String) { "weight must be a decimal string/number." }}.toString()
                    require(unit!="bodyweight" || weight==null) { "For added bodyweight load, use kg/lb with bodyweight loadMode; omit weight for plain bodyweight." }
                    require(weight==null || unit!=null) { "A weight needs explicit kg/lb units." }
                    val w=WorkoutPlanParser.parseWeight(when(unit){null->"";"bodyweight"->"bodyweight";else->"$weight $unit"})
                    val rest=if(s.isNull("restSeconds") || !s.has("restSeconds"))null else integer(s,"restSeconds",0,0..3600)
                    val mode=if(s.has("loadMode"))text(s,"loadMode",30) else if(w.unit=="bodyweight")LoadMode.BODYWEIGHT else LoadMode.UNKNOWN
                    val warm=if(s.has("isWarmUp"))s.get("isWarmUp").also{require(it is Boolean) { "isWarmUp must be boolean." }} as Boolean else false
                    WorkoutPlanParser.validateSet(PlanSetTarget(low,high,w,rest,mode,warm),e.getString("name"))
                    s.put("weight",w.value ?: JSONObject.NULL).put("unit",w.unit ?: JSONObject.NULL).put("restSeconds",rest ?: JSONObject.NULL).put("loadMode",mode).put("isWarmUp",warm)
                }catch(f:Exception){throw IllegalArgumentException("Set ${k+1}: ${f.message}")}
                e.put("sets",sets)
            }catch(f:Exception){throw IllegalArgumentException("Exercise ${j+1}: ${f.message}")}
        }catch(f:Exception){throw IllegalArgumentException("Day ${i+1}: ${f.message}")}
        if(!root.has("id"))root.put("id","private-"+planFingerprint(canonical(root)).take(24))
        val normalizedId=text(root,"id",128,true);identifier(normalizedId,"Program");root.put("id",normalizedId)
        require(canonical(root).toByteArray(Charsets.UTF_8).size<=MAX_INPUT) { "Expanded program exceeds 256 KB. Reduce repeated targets." }
    }
    fun draft(p:PortableProgram,catalogue:List<Exercise>,aliases:List<ExerciseAlias>,incompatible:Set<Long>):ProgramImportDraft {
        val root=p.document;val eMetadata=mutableMapOf<String,String>();val sMetadata=mutableMapOf<String,String>();val days=root.getJSONArray("days")
        val drafts=(0 until days.length()).map { index ->
            val day=days.getJSONObject(index);val rows=day.getJSONArray("exercises")
            val exercises=(0 until rows.length()).map { j ->
                val e=rows.getJSONObject(j);val key=UUID.randomUUID().toString();eMetadata[key]=e.toString()
                val sets=e.getJSONArray("sets");val targets=(0 until sets.length()).map { k -> val s=sets.getJSONObject(k)
                    val target=PlanSetTarget(if(s.isNull("repsMin"))null else s.getInt("repsMin"),if(s.isNull("repsMax"))null else s.getInt("repsMax"),PlanWeight(if(s.isNull("weight"))null else s.getString("weight"),if(s.isNull("unit"))null else s.getString("unit")),if(s.isNull("restSeconds"))null else s.getInt("restSeconds"),s.getString("loadMode"),s.getBoolean("isWarmUp")).editable()
                    sMetadata[target.key]=s.toString();target }
                val match=matchPlanExercise(e.getString("name"),catalogue,incompatible,aliasMap(aliases))
                EditablePlanExercise(key,e.getString("name"),targets,e.getString("notes"),match.candidates.singleOrNull()?.id?.takeIf{match.kind in setOf(ExerciseMatchKind.EXACT,ExerciseMatchKind.ALIAS)})
            }
            val edit=EditablePlan(day.getString("name"),"",day.getInt("durationMinutes").takeIf{it>0}?.toString().orEmpty(),day.getString("notes"),planFingerprint(p.json+index),exercises)
            ProgramImportDay(day.getString("id"),edit,day.toString())
        }
        return ProgramImportDraft(p,p.name,root.getString("description"),p.weeks.toString(),drafts,eMetadata,sMetadata)
    }
    private fun parseText(text:String):JSONObject {
        val root=JSONObject().put("name","Imported program").put("days",JSONArray());var day:JSONObject?=null
        val prescription=Regex("^(.+?)\\s*\\|\\s*([0-9]+)\\s*[x×]\\s*([0-9]+(?:\\s*[-–]\\s*[0-9]+)?)(.*)$")
        text.lines().forEachIndexed { i,line ->
            val value=line.trim();if(value.isEmpty())return@forEachIndexed
            try { when {
                value.startsWith("Program:",true)->root.put("name",value.substringAfter(':').trim())
                value.startsWith("Weeks:",true)->root.put("weeks",value.substringAfter(':').trim().toInt())
                Regex("^Day\\s+[^:]+:.+$",RegexOption.IGNORE_CASE).matches(value)-> { day=JSONObject().put("name",value.substringAfter(':').trim()).put("exercises",JSONArray());root.getJSONArray("days").put(day) }
                else -> {
                    val m=prescription.matchEntire(value) ?: throw IllegalArgumentException("Use Day 1: Name and Exercise | 3 x 8-12 | rest 90s | RIR 2. For other layouts, convert to program JSON and paste it.")
                    val e=JSONObject().put("name",m.groupValues[1].removePrefix("- ").trim()).put("sets",m.groupValues[2].toInt()).put("reps",m.groupValues[3].replace("–","-"))
                    m.groupValues[4].split('|').map{it.trim()}.filter{it.isNotBlank()}.forEach { option -> when {
                        Regex("rest [0-9]+s?",RegexOption.IGNORE_CASE).matches(option)->e.put("restSeconds",option.filter{it.isDigit()}.toInt())
                        Regex("RIR [0-9]+(?:\\.[0-9]+)?",RegexOption.IGNORE_CASE).matches(option)->e.put("rir",option.substringAfter(' ').toDouble())
                        Regex("RPE [0-9]+(?:\\.[0-9]+)?",RegexOption.IGNORE_CASE).matches(option)->e.put("rpe",option.substringAfter(' ').toDouble())
                        else->e.put("notes",(e.optString("notes")+" "+option).trim())
                    } };requireNotNull(day){"Add a Day 1: Name heading before exercises."}.getJSONArray("exercises").put(e)
                }
            }}catch(f:Exception){throw IllegalArgumentException("Line ${i+1}: ${f.message}")}
        };return root
    }
    fun canonical(value:Any?):String=when(value){null,JSONObject.NULL->"null";is JSONObject->value.keys().asSequence().toList().sorted().joinToString(",","{","}"){JSONObject.quote(it)+":"+canonical(value.get(it))};is JSONArray->(0 until value.length()).joinToString(",","[","]"){canonical(value.get(it))};is String->JSONObject.quote(value);else->value.toString()}
}
