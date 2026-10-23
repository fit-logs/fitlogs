package com.example.fitnotes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.fitnotes.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ProgramCatalogTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val directory get()=File(context.filesDir,"program-catalog")
    private val source=File("../../web/public/catalog")
    private val manifest get()=File(source,"manifest.json").readText()
    private val parsed get()=CatalogValidator.manifest(manifest)
    private val offline=CatalogTransport{_,_->throw IOException("Offline synthetic fixture")}
    @Before fun clean(){directory.deleteRecursively()}
    private suspend fun reject(block:suspend()->Unit){try{block();fail("Expected rejection")}catch(_:IllegalArgumentException){}catch(_:IOException){}}
    @Test fun everyPublicProgramUsesThePortableImportParserAndExplicitExerciseReview() {
        val m=parsed;assertEquals(8,m.entries.size);assertEquals(3,m.entries.count{it.starter})
        m.entries.forEach{e->val p=CatalogValidator.program(File(source,e.path).readText(),e,m)
            val draft=PortableProgramParser.draft(p,emptyList(),emptyList(),emptySet());assertEquals(e.days,draft.days.size)
            assertTrue(draft.days.flatMap{it.plan.exercises}.all{it.exerciseId==null && it.createName==null})
            assertTrue(draft.days.flatMap{it.plan.exercises}.flatMap{it.targets}.all{it.weight.isBlank() && it.rir.isNotBlank()})
        }
    }
    @Test fun firstLaunchLoadsThreeStartersWithoutAnyNetworkRequest()=runBlocking {
        val calls=mutableListOf<String>();val repo=ProgramCatalogRepository(context,CatalogTransport{path,_->calls+=path;throw IOException()})
        val s=repo.load();assertNull(s.refreshedAt);assertEquals(s.manifest.entries.filter{it.starter}.map{it.id}.toSet(),s.available)
        s.manifest.entries.filter{it.starter}.forEach{assertEquals(it.id,repo.program(it,s.manifest).id)};assertTrue(calls.isEmpty())
    }
    @Test fun downloadedProgramAndRefreshTimestampSurviveOfflineRestart()=runBlocking {
        val requests=mutableListOf<String>();val repo=ProgramCatalogRepository(context,CatalogTransport{path,_->requests+=path;File(source,path).readText()}){12345L}
        val s=repo.refresh();val e=s.manifest.entries.first{!it.starter};repo.program(e,s.manifest)
        assertEquals(listOf("manifest.json",e.path),requests)
        val offlineRepo=ProgramCatalogRepository(context,offline);val restarted=offlineRepo.load();assertEquals(12345L,restarted.refreshedAt);assertTrue(e.id in restarted.available);assertEquals(e.id,offlineRepo.program(e,restarted.manifest).id)
        reject{offlineRepo.refresh()};assertEquals(restarted,offlineRepo.load())
    }
    @Test fun invalidRefreshCannotReplaceTheLastValidatedManifest()=runBlocking {
        val repo=ProgramCatalogRepository(context,CatalogTransport{_,_->manifest}){1000L};repo.refresh();val before=File(directory,"manifest-cache.json").readText()
        val invalid=listOf("{",JSONObject(manifest).put("schemaVersion",2).toString(),JSONObject(manifest).apply{getJSONArray("programs").put(getJSONArray("programs").getJSONObject(0))}.toString(),JSONObject(manifest).apply{getJSONArray("programs").getJSONObject(0).put("path","../private.json")}.toString())
        invalid.forEach{raw->reject{ProgramCatalogRepository(context,CatalogTransport{_,_->raw}).refresh()};assertEquals(before,File(directory,"manifest-cache.json").readText())}
    }
    @Test fun unknownOrInterruptedCacheFormatsFallBackWithoutTouchingInstalledData()=runBlocking {
        val repo=ProgramCatalogRepository(context,CatalogTransport{_,_->manifest}){1000L};repo.refresh();val file=File(directory,"manifest-cache.json")
        // AtomicFile ignores/removes an uncommitted write and keeps its prior file.
        File(directory,"manifest-cache.json.new").writeText("partial");assertEquals(1000L,repo.load().refreshedAt)
        file.writeText(JSONObject(file.readText()).put("cacheVersion",2).toString());val fallback=ProgramCatalogRepository(context,offline).load();assertNull(fallback.refreshedAt);assertEquals(3,fallback.available.size)
    }
    @Test fun changedHashesAndUnknownExerciseReferencesAreRejectedBeforeCaching()=runBlocking {
        val m=parsed;val original=m.entries.first{!it.starter};val raw=File(source,original.path).readText()
        reject{CatalogValidator.program(raw+" ",original,m)}
        val wrong=JSONObject(raw).apply{getJSONArray("days").getJSONObject(0).getJSONArray("exercises").getJSONObject(0).put("canonicalId","not-a-catalog-exercise")}.toString()
        val e=original.copy(sha256=planFingerprint(wrong));val altered=m.copy(entries=m.entries.map{if(it.id==e.id)e else it})
        reject{ProgramCatalogRepository(context,CatalogTransport{_,_->wrong}).program(e,altered)};assertFalse(File(directory,e.sha256+".json").exists())
        val weighted=JSONObject(raw).apply{getJSONArray("days").getJSONObject(0).getJSONArray("exercises").getJSONObject(0).getJSONArray("sets").getJSONObject(0).put("weight","80").put("unit","kg")}.toString()
        reject{CatalogValidator.program(weighted,original.copy(sha256=planFingerprint(weighted)),m)}
    }
    @Test fun publicUpdatesAndDuplicateInstallsPreserveLocalEditsHistoryAndTargets()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,FitDatabase::class.java).build()
        try {
            parsed.exercises.values.forEachIndexed{i,name->db.dao().insertExercise(Exercise(i+1L,name,normaliseExerciseName(name)))}
            val e=parsed.entries.first{it.id=="full-body-express-2day"};val old=CatalogValidator.program(File(source,e.path).readText(),e,parsed)
            val draft=PortableProgramParser.draft(old,db.dao().exercises(),emptyList(),emptySet());val imports=ProgramImportRepository(db)
            val id=(imports.save(draft) as ProgramImportResult.Saved).id;imports.editIdentity(id,"My edited local copy","Keep this edit")
            val workouts=WorkoutRepository(db);val w=workouts.start();workouts.addExercise(w,db.dao().exercises().first().id);workouts.saveSet(db.dao().workoutEntries(w).single().id,"42.5","8")
            val before=workouts.backup();assertTrue(imports.save(draft) is ProgramImportResult.Duplicate)
            val next=JSONObject(File(source,e.path).readText()).put("programVersion","2").toString();val updatedEntry=e.copy(version="2",path="programs/${e.id}-v2.json",sha256=planFingerprint(next))
            val updated=JSONObject(manifest);val array=updated.getJSONArray("programs");for(i in 0 until array.length())if(array.getJSONObject(i).getString("id")==e.id)array.getJSONObject(i).put("programVersion","2").put("path",updatedEntry.path).put("sha256",updatedEntry.sha256)
            val repo=ProgramCatalogRepository(context,CatalogTransport{path,_->if(path=="manifest.json")updated.toString() else next});val fresh=repo.refresh();assertEquals("2",repo.program(fresh.manifest.entries.single{it.id==e.id},fresh.manifest).version)
            val after=workouts.backup();assertEquals(before.copy(exportedAt=after.exportedAt),after);assertEquals("My edited local copy",db.programDao().programs().single().title);assertEquals("1",db.programDao().programs().single().definitionVersion)
        } finally {db.close()}
    }
    @Test fun filtersRequireAllSelectedEquipmentAndComposeWithFrequencyGoalAndDuration(){
        val rows=parsed.entries;assertEquals(8,rows.count{CatalogFilter().matches(it)})
        assertEquals(listOf("dumbbell-home-3day"),rows.filter{CatalogFilter(equipment=setOf("dumbbells","bench")).matches(it)}.map{it.id})
        assertEquals(listOf("full-body-express-2day"),rows.filter{CatalogFilter(goal="general fitness",level="beginner",days=2,maxMinutes=35).matches(it)}.map{it.id})
        assertEquals(1,rows.count{CatalogFilter(query="DUMBBELL HOME").matches(it)});assertEquals(0,rows.count{CatalogFilter(days=6,maxMinutes=35).matches(it)})
    }
    private class Response(url:URL,val bytes:ByteArray,val status:Int=200,val mime:String="application/json",val declared:Long=bytes.size.toLong()):HttpURLConnection(url) {
        var closed=false
        override fun connect(){};override fun disconnect(){closed=true};override fun usingProxy()=false
        override fun getResponseCode()=status;override fun getContentType()=mime;override fun getContentLengthLong()=declared;override fun getInputStream()=bytes.inputStream()
    }
    @Test fun httpTransportSendsOnlyPublicGetsAndRejectsRedirectHtmlAndSizeFailures(){
        var last:Response?=null;val transport=PublicCatalogTransport{url->Response(url,"{}".toByteArray()).also{last=it}}
        assertEquals("{}",transport.get("manifest.json",10));assertEquals(CATALOG_URL+"manifest.json",last!!.url.toString());assertEquals("GET",last!!.requestMethod);assertFalse(last!!.instanceFollowRedirects);assertEquals(setOf("Accept","Cache-Control"),last!!.requestProperties.keys);assertTrue(last!!.closed)
        for(r in listOf(Response(URL(CATALOG_URL),byteArrayOf(),302),Response(URL(CATALOG_URL),byteArrayOf(),404),Response(URL(CATALOG_URL),"html".toByteArray(),mime="text/html"),Response(URL(CATALOG_URL),"12345678901".toByteArray(),declared=-1))){assertThrows(IllegalArgumentException::class.java){PublicCatalogTransport{r}.get("manifest.json",10)};assertTrue(r.closed)}
        for(path in listOf("https://other.example/data","../private","manifest.json?history=1","programs/../../private.json"))assertThrows(IllegalArgumentException::class.java){PublicCatalogTransport{error("must not connect")}.get(path,10)}
    }
}
