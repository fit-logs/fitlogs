package com.example.fitnotes.data

import org.json.JSONArray
import org.json.JSONObject

internal object LoadAdviceBackupJson {
    val keys=arrayOf("loadProfiles","setLoadContexts","sessionLoadLocks")
    fun add(root:JSONObject,b:Backup){
        root.put("loadProfiles",JSONArray(b.loadProfiles.map{p->JSONObject().put("id",p.id).put("exerciseId",p.exerciseId).put("gym",p.gym).put("equipment",p.equipment).put("basis",p.basis).put("mode",p.mode).put("unit",p.unit).put("increment",p.increment).put("targetRir",p.targetRir).put("repChoice",p.repChoice).put("selected",p.selected).put("reviewedLegacy",p.reviewedLegacy)}))
        root.put("setLoadContexts",JSONArray(b.setLoadContexts.map{c->JSONObject().put("setId",c.setId).put("gym",c.gym).put("equipment",c.equipment).put("basis",c.basis).put("rir",c.rir ?: JSONObject.NULL).put("rpe",c.rpe ?: JSONObject.NULL)}))
        root.put("sessionLoadLocks",JSONArray(b.sessionLoadLocks.map{JSONObject().put("entryId",it.entryId)}))
    }
    private fun rows(root:JSONObject,key:String,fields:Set<String>):List<JSONObject>{val a=root.getJSONArray(key);require(a.length()<=1000000);return (0 until a.length()).map{a.getJSONObject(it).also{o->require(o.keys().asSequence().toSet()==fields){"Invalid load-advice backup fields."}}}}
    private fun text(o:JSONObject,k:String)=o.get(k) as? String ?: throw IllegalArgumentException("$k must be text.")
    private fun long(o:JSONObject,k:String):Long{val v=o.get(k);require(v is Int || v is Long);return (v as Number).toLong()}
    private fun number(o:JSONObject,k:String):Double{val v=o.get(k);require(v is Number);return v.toDouble()}
    private fun optional(o:JSONObject,k:String)=if(o.isNull(k))null else number(o,k)
    private fun bool(o:JSONObject,k:String)=o.get(k) as? Boolean ?: throw IllegalArgumentException("$k must be boolean.")
    fun profiles(root:JSONObject)=rows(root,"loadProfiles",setOf("id","exerciseId","gym","equipment","basis","mode","unit","increment","targetRir","repChoice","selected","reviewedLegacy")).map{LoadProfile(text(it,"id"),long(it,"exerciseId"),text(it,"gym"),text(it,"equipment"),text(it,"basis"),text(it,"mode"),text(it,"unit"),text(it,"increment"),number(it,"targetRir"),text(it,"repChoice"),bool(it,"selected"),bool(it,"reviewedLegacy"))}
    fun contexts(root:JSONObject)=rows(root,"setLoadContexts",setOf("setId","gym","equipment","basis","rir","rpe")).map{SetLoadContext(long(it,"setId"),text(it,"gym"),text(it,"equipment"),text(it,"basis"),optional(it,"rir"),optional(it,"rpe"))}
    fun locks(root:JSONObject)=rows(root,"sessionLoadLocks",setOf("entryId")).map{SessionLoadLock(long(it,"entryId"))}
    fun validate(b:Backup){
        if(b.version<13){require(b.loadProfiles.isEmpty() && b.setLoadContexts.isEmpty() && b.sessionLoadLocks.isEmpty()){ "Load setup/effort metadata needs backup version 13." };return}
        val exerciseIds=b.exercises.map{it.id}.toSet();val sets=b.sets.map{it.id}.toSet();val entries=b.entries.map{it.id}.toSet()
        require(b.loadProfiles.map{it.id}.distinct().size==b.loadProfiles.size && b.loadProfiles.all{it.exerciseId in exerciseIds}){"Invalid load profiles."}
        require(b.loadProfiles.map{listOf(it.exerciseId,it.gym,it.equipment,it.basis,it.mode)}.distinct().size==b.loadProfiles.size && b.loadProfiles.filter{it.selected}.map{it.exerciseId}.distinct().size==b.loadProfiles.count{it.selected}){"Duplicate load setup or selected profile."}
        require(b.loadProfiles.filter{it.reviewedLegacy}.map{it.exerciseId}.distinct().size==b.loadProfiles.count{it.reviewedLegacy}) { "Legacy work sets cannot be assigned to multiple setups." }
        b.loadProfiles.forEach(::validateLoadProfile)
        require(b.setLoadContexts.map{it.setId}.distinct().size==b.setLoadContexts.size && b.setLoadContexts.all{it.setId in sets}){"Invalid actual set context reference."}
        b.setLoadContexts.forEach{c->validateEffort(c.rir,c.rpe);require(listOf(c.gym,c.equipment).all{it.length<=100 && it.none(Char::isISOControl)} && (c.gym.isBlank() && c.equipment.isBlank() && c.basis=="unknown" || c.gym.isNotBlank() && c.equipment.isNotBlank() && c.basis in setOf("total","per_hand","stack"))){"Invalid recorded setup context."}}
        require(b.sessionLoadLocks.map{it.entryId}.distinct().size==b.sessionLoadLocks.size && b.sessionLoadLocks.all{it.entryId in entries}){"Invalid session load lock."}
    }
}
