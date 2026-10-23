package com.example.fitnotes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.fitnotes.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PortableProgramTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db:FitDatabase
    private val workouts get()=WorkoutRepository(db)
    private val importer get()=ProgramImportRepository(db)
    private val fixture get()=context.assets.open("examples/four-day.json").bufferedReader().use{it.readText()}
    private val catalogue=listOf(Exercise(1,"Bench Press","bench press"),Exercise(2,"Squat","squat"),Exercise(3,"Barbell Row","barbell row"),Exercise(4,"Seated Cable Row","seated cable row"))
    private val aliases=listOf(ExerciseAlias(1,1,"Flat bench","flat bench"),ExerciseAlias(2,3,"Row","row"),ExerciseAlias(3,4,"Row","row"))
    @Before fun setup()=runBlocking {db=Room.inMemoryDatabaseBuilder(context,FitDatabase::class.java).build();db.dao().insertExercises(catalogue);aliases.forEach{db.trainingDao().putAlias(it)};Unit}
    @After fun close(){db.close()}
    private fun parse(text:String=fixture)=PortableProgramParser.parse(text)
    private fun draft(text:String=fixture)=PortableProgramParser.draft(parse(text),catalogue,aliases,emptySet())
    private fun resolved(d:ProgramImportDraft=draft())=d.copy(days=d.days.map{day->day.copy(plan=day.plan.copy(exercises=day.plan.exercises.map{e->when(e.sourceName){"Row"->e.copy(exerciseId=3);"Custom carry"->e.copy(createName="Custom carry");else->e}}))})
    private fun reject(block:()->Unit):String{try{block();fail("Expected rejection")}catch(e:IllegalArgumentException){return e.message.orEmpty()};return ""}
    private suspend fun rejectSave(block:suspend()->Unit){try{block();fail("Expected rejection")}catch(_:IllegalArgumentException){}catch(_:android.database.sqlite.SQLiteException){}}
    @Test fun canonicalFourDayFixtureKeepsOrderEffortSourceAndExtensionsWithoutInventingWeights() {
        val p=parse();val root=p.document;val days=root.getJSONArray("days")
        assertEquals(4,days.length());assertEquals("upper-a",days.getJSONObject(0).getString("id"));assertEquals("lower-b",days.getJSONObject(3).getString("id"))
        val sets=days.getJSONObject(0).getJSONArray("exercises").getJSONObject(0).getJSONArray("sets")
        assertEquals(3,sets.length());assertEquals(8,sets.getJSONObject(0).getInt("repsMin"));assertEquals(12,sets.getJSONObject(0).getInt("repsMax"));assertEquals(2,sets.getJSONObject(0).getInt("rir"));assertEquals(90,sets.getJSONObject(0).getInt("restSeconds"));assertTrue(sets.getJSONObject(0).isNull("weight"))
        assertEquals("https://example.org/program",root.getJSONObject("source").getString("url"));assertTrue(root.getJSONObject("extensionExample").getBoolean("kept"))
        assertEquals(p,parse(p.json));assertEquals(p.fingerprint,parse(JSONObject(p.json).toString(2)).fingerprint)
    }
    @Test fun knownAliasMatchesButAmbiguityUnknownAndMovementVariantsRequireExplicitResolution() {
        val d=draft();assertEquals(1L,d.days[0].plan.exercises[0].exerciseId);assertNull(d.days[0].plan.exercises[1].exerciseId);assertNull(d.days[3].plan.exercises[0].exerciseId)
        val changed=JSONObject(fixture);changed.getJSONArray("days").getJSONObject(0).getJSONArray("exercises").getJSONObject(0).put("name","Incline Bench Press")
        assertNull(draft(changed.toString()).days[0].plan.exercises[0].exerciseId)
        val incompatible=PortableProgramParser.draft(parse(),catalogue,aliases,setOf(1));assertNull(incompatible.days[0].plan.exercises[0].exerciseId)
    }
    @Test fun supportedTextAndCommonJsonAliasesNormalizeAndRepeatDeterministically() {
        val text="Program: Text program\nWeeks: 3\nDay 1: Upper\nBench Press | 3 x 8-12 | rest 90s | RIR 2\nDay 2: Lower\nSquat | 2 x 6 | RPE 8"
        val p=parse(text);assertEquals(p,parse(text.replace("\n","\r\n")));assertEquals(2,p.document.getJSONArray("days").length())
        val json=JSONObject().put("title","Common input").put("workouts",JSONArray().put(JSONObject().put("title","Upper").put("exercises",JSONArray().put(JSONObject().put("exercise","Bench Press").put("sets",2).put("reps","8-10")))))
        val normalized=parse(json.toString());assertEquals("Common input",normalized.name);assertEquals(normalized,parse(normalized.json))
        assertTrue(reject{parse("Program: A\nUnsupported table text")}.contains("Line 2"))
    }
    @Test fun perSetRepsWinOverExerciseDefaultsWhileExplicitLoadModeAndWarmupSurvive() {
        val root=JSONObject(fixture);val e=root.getJSONArray("days").getJSONObject(0).getJSONArray("exercises").getJSONObject(0)
        e.remove("reps");e.put("repRangeMin",8).put("repRangeMax",12).put("loadMode","external").put("isWarmUp",true)
        e.put("sets",JSONArray().put(JSONObject().put("reps",5)).put(JSONObject().put("repsMin",6).put("repsMax",7).put("isWarmUp",false)).put(JSONObject()))
        val rows=parse(root.toString()).document.getJSONArray("days").getJSONObject(0).getJSONArray("exercises").getJSONObject(0).getJSONArray("sets")
        assertEquals(5,rows.getJSONObject(0).getInt("repsMin"));assertEquals(5,rows.getJSONObject(0).getInt("repsMax"));assertEquals(6,rows.getJSONObject(1).getInt("repsMin"));assertEquals(7,rows.getJSONObject(1).getInt("repsMax"));assertEquals(8,rows.getJSONObject(2).getInt("repsMin"));assertEquals(12,rows.getJSONObject(2).getInt("repsMax"))
        assertEquals("external",rows.getJSONObject(0).getString("loadMode"));assertTrue(rows.getJSONObject(0).getBoolean("isWarmUp"));assertFalse(rows.getJSONObject(1).getBoolean("isWarmUp"))
    }
    @Test fun malformedAndOversizedJsonReportLocationAndRejectDuplicateFieldsUnsupportedSchemaAndRanges() {
        assertTrue(reject{parse("{\n\"name\":\"A\",\n\"name\":\"B\"}")}.contains("line 3"))
        reject{parse("x".repeat(PortableProgramParser.MAX_INPUT+1))}
        reject{parse(JSONObject(fixture).put("schemaVersion",2).toString())}
        reject{parse(JSONObject(fixture).put("weeks",2.5).toString())}
        reject{parse(JSONObject(fixture).put("startDate","2026-02-30").toString())}
        val root=JSONObject(fixture);root.getJSONArray("days").getJSONObject(0).getJSONArray("exercises").getJSONObject(0).put("rir",11)
        assertTrue(reject{parse(root.toString())}.contains("Day 1: Exercise 1: Set 1"))
        val bad=JSONObject(fixture);bad.getJSONArray("days").getJSONObject(0).getJSONArray("exercises").getJSONObject(0).put("reps","12-8");reject{parse(bad.toString())}
    }
    @Test fun urlsAreProvenanceOnlyAndUnsafeSchemesCredentialsOrBadUnitsAreRejected() {
        for(url in listOf("javascript:alert(1)","file:///tmp/a","https://user:secret@example.org/a","https://")) {
            val root=JSONObject(fixture);root.getJSONObject("source").put("url",url);reject{parse(root.toString())}
        }
        assertTrue(PortableProgramParser.validUrl("https://example.org/program?q=4"));assertFalse(PortableProgramParser.validUrl("data:text/plain,test"))
        val root=JSONObject(fixture);val e=root.getJSONArray("days").getJSONObject(0).getJSONArray("exercises").getJSONObject(0);e.put("weight","100.25");reject{parse(root.toString())};e.put("unit","lb")
        val p=parse(root.toString());val d=PortableProgramParser.draft(p,catalogue,aliases,emptySet());assertEquals("100.25 lb",d.days.first().plan.exercises.first().targets.first().weight)
        e.remove("loadMode");val unqualified=parse(root.toString()).document.getJSONArray("days").getJSONObject(0).getJSONArray("exercises").getJSONObject(0).getJSONArray("sets").getJSONObject(0)
        assertEquals(LoadMode.UNKNOWN,unqualified.getString("loadMode"))
    }
    @Test fun editingReorderingAndRemovingTargetsKeepTheirOwnUnknownMetadataAndNeverShiftEffort() {
        val root=JSONObject(fixture);val row=root.getJSONArray("days").getJSONObject(0).getJSONArray("exercises").getJSONObject(0)
        row.put("sets",JSONArray().put(JSONObject().put("reps",8).put("rir",1).put("custom","first")).put(JSONObject().put("reps",10).put("rir",3).put("custom","second")))
        val d=draft(root.toString());val first=d.days[0];val edit=first.plan.exercises.first();val changed=d.copy(name="Edited name",days=listOf(d.days[1],first.copy(plan=first.plan.copy(exercises=listOf(edit.copy(targets=edit.targets.drop(1)))+first.plan.exercises.drop(1))))+d.days.drop(2))
        val p=changed.validated().first;val days=p.document.getJSONArray("days");assertEquals("lower-a",days.getJSONObject(0).getString("id"));val target=days.getJSONObject(1).getJSONArray("exercises").getJSONObject(0).getJSONArray("sets").getJSONObject(0)
        assertEquals(3,target.getInt("rir"));assertEquals("second",target.getString("custom"));assertTrue(p.document.getJSONObject("extensionExample").getBoolean("kept"))
    }
    @Test fun previewAndRejectedUnresolvedSaveLeaveCatalogueAndAllTrainingUntouched()=runBlocking {
        val before=workouts.backup();val pending=draft();val afterPreview=workouts.backup();assertEquals(before.copy(exportedAt=afterPreview.exportedAt),afterPreview)
        rejectSave{importer.save(pending)};val after=workouts.backup();assertEquals(before.copy(exportedAt=after.exportedAt),after)
    }
    @Test fun fourDaysSaveAsOneEditableProgramWithNoPerformedSessionsOrWeights()=runBlocking {
        val result=importer.save(resolved()) as ProgramImportResult.Saved;val p=db.programDao().detail(result.id)!!
        assertEquals(4,p.days.size);assertEquals("paused",p.program.status);assertEquals("example-four-day",p.program.definitionId)
        assertEquals(4,db.planDao().plans().size);assertTrue(db.dao().workouts().isEmpty());assertTrue(db.dao().sets().isEmpty())
        assertTrue(p.days.flatMap{it.plan.ordered}.flatMap{it.sets}.all{it.weightValue==null});assertTrue(p.ordered.first().plan.ordered.first().target.note.contains("RIR 2"))
        assertEquals(5,db.dao().exercises().size);assertEquals("Custom carry",db.dao().exercises().last().name)
        TrainingProgramRepository(db).activate(result.id);assertEquals("active",db.programDao().detail(result.id)!!.program.status)
    }
    @Test fun stableIdentityDuplicatesWarnWithoutOverwritingLocalEditsAndDeliberateCopyIsSeparate()=runBlocking {
        val d=resolved();val first=(importer.save(d) as ProgramImportResult.Saved).id
        importer.editIdentity(first,"My edited program","My private description");val own=db.programDao().detail(first)!!.ordered.first().day.planId
        WorkoutPlanRepository(db).rename(own,"My edited day")
        // Resolve the explicitly-created exercise against the now-existing local row.
        val again=resolved().copy(days=resolved().days.map{day->day.copy(plan=day.plan.copy(exercises=day.plan.exercises.map{if(it.createName!=null)it.copy(exerciseId=5,createName=null)else it}))})
        assertEquals(ProgramImportResult.Duplicate(first),importer.save(again));assertEquals("My edited program",db.programDao().detail(first)!!.program.title);assertEquals("My edited day",db.programDao().detail(first)!!.ordered.first().plan.plan.title)
        val copy=importer.save(again,true) as ProgramImportResult.Saved;assertNotEquals(first,copy.id);assertEquals(2,db.programDao().programs().size);assertTrue(db.dao().sets().isEmpty())
    }
    @Test fun failureOnAFinalDayRollsBackProgramTemplatesAndNewExerciseCreation()=runBlocking {
        val before=workouts.backup();db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_import BEFORE INSERT ON planned_exercises WHEN NEW.sourceName='Custom carry' BEGIN SELECT RAISE(ABORT,'synthetic final day failure'); END")
        rejectSave{importer.save(resolved())};val after=workouts.backup();assertEquals(before.copy(exportedAt=after.exportedAt),after)
    }
    @Test fun backupRestoreRetainsDefinitionAttributionLocalEditsAndLeavesActualAnalysisUnpolluted()=runBlocking {
        val id=(importer.save(resolved()) as ProgramImportResult.Saved).id;importer.editIdentity(id,"Local title","Local description")
        val before=workouts.backup();assertEquals(13,before.version);val decoded=BackupCodec.decode(BackupCodec.encode(before));assertEquals(before,decoded)
        workouts.restore(decoded);val after=workouts.backup();assertEquals(before.copy(exportedAt=after.exportedAt),after)
        val metadata=JSONObject(after.programs.single().definitionJson);assertEquals("Local description",metadata.getString("description"));assertEquals("https://example.org/program",metadata.getJSONObject("source").getString("url"))
        val analysis=JSONObject(BackupCodec.analysis(after));assertFalse(analysis.has("programs"));assertEquals(0,analysis.getJSONArray("sets").length());assertEquals(0,analysis.getJSONArray("workouts").length())
        reject{BackupCodec.encode(before.copy(version=11))};Unit
    }
    @Test fun versionThirteenMigrationAddsEmptyMetadataWithoutChangingExistingProgramOrTemplate()=runBlocking {
        db.close();val name="portable-program-migration.db";context.deleteDatabase(name)
        val schema=JSONObject(File("schemas/com.example.fitnotes.data.FitDatabase/13.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name,Context.MODE_PRIVATE,null).use{old->
            val entities=schema.getJSONArray("entities");for(i in 0 until entities.length()){val e=entities.getJSONObject(i);old.execSQL(e.getString("createSql").replace("$"+"{TABLE_NAME}",e.getString("tableName")));val indices=e.optJSONArray("indices")?:JSONArray();for(j in 0 until indices.length())old.execSQL(indices.getJSONObject(j).getString("createSql").replace("$"+"{TABLE_NAME}",e.getString("tableName")))}
            old.execSQL("CREATE TABLE room_master_table(id INTEGER PRIMARY KEY,identity_hash TEXT)");old.execSQL("INSERT INTO room_master_table VALUES(42,?)",arrayOf(schema.getString("identityHash")))
            old.execSQL("INSERT INTO training_programs VALUES('keep','Keep program',4,'paused',0,'',1000)");old.execSQL("INSERT INTO planned_workouts(id,title,scheduledDate,durationMinutes,notes,createdAt,sourceFingerprint,archived,pinned) VALUES('template','Keep template','',30,'Keep notes',1000,'${"a".repeat(64)}',1,1)");old.version=13
        }
        db=Room.databaseBuilder(context,FitDatabase::class.java,name).addMigrations(PROGRAM_IMPORT_MIGRATION_13_14, LOAD_ADVICE_MIGRATION_14_15).build();val p=db.programDao().programs().single();assertEquals("Keep program",p.title);assertEquals("",p.definitionJson);assertTrue(db.planDao().detail("template")!!.plan.archived);assertTrue(db.planDao().detail("template")!!.plan.pinned)
        db.close();context.deleteDatabase(name);Unit
    }
}
