package com.example.fitnotes.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal
import kotlin.math.abs

@Entity(tableName="load_profiles",foreignKeys=[ForeignKey(entity=Exercise::class,parentColumns=["id"],childColumns=["exerciseId"],onDelete=ForeignKey.CASCADE)],indices=[Index(value=["exerciseId","gym","equipment","basis","mode"],unique=true)])
data class LoadProfile(@PrimaryKey val id:String,val exerciseId:Long,val gym:String,val equipment:String,val basis:String,val mode:String,
    val unit:String="kg",val increment:String="2.5",val targetRir:Double=2.0,val repChoice:String="lower",val selected:Boolean=true,val reviewedLegacy:Boolean=false)
@Entity(tableName="set_load_contexts",foreignKeys=[ForeignKey(entity=WorkoutSet::class,parentColumns=["id"],childColumns=["setId"],onDelete=ForeignKey.CASCADE)])
data class SetLoadContext(@PrimaryKey val setId:Long,val gym:String="",val equipment:String="",val basis:String="unknown",val rir:Double?=null,val rpe:Double?=null)
@Entity(tableName="session_load_locks",foreignKeys=[ForeignKey(entity=WorkoutExercise::class,parentColumns=["id"],childColumns=["entryId"],onDelete=ForeignKey.CASCADE)])
data class SessionLoadLock(@PrimaryKey val entryId:Long)
@Dao interface LoadAdviceDao {
    @Query("SELECT * FROM load_profiles ORDER BY exerciseId,gym,equipment,id") suspend fun profiles():List<LoadProfile>
    @Query("SELECT * FROM load_profiles ORDER BY exerciseId,gym,equipment,id") fun observeProfiles():Flow<List<LoadProfile>>
    @Query("SELECT * FROM load_profiles WHERE exerciseId=:exerciseId AND selected=1 LIMIT 1") suspend fun selected(exerciseId:Long):LoadProfile?
    @Query("UPDATE load_profiles SET selected=0 WHERE exerciseId=:exerciseId") suspend fun deselect(exerciseId:Long)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putProfile(value:LoadProfile)
    @Query("SELECT * FROM set_load_contexts ORDER BY setId") suspend fun contexts():List<SetLoadContext>
    @Query("SELECT * FROM set_load_contexts ORDER BY setId") fun observeContexts():Flow<List<SetLoadContext>>
    @Query("SELECT * FROM set_load_contexts WHERE setId=:id") suspend fun context(id:Long):SetLoadContext?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putContext(value:SetLoadContext)
    @Query("SELECT * FROM session_load_locks ORDER BY entryId") suspend fun locks():List<SessionLoadLock>
    @Query("SELECT * FROM session_load_locks ORDER BY entryId") fun observeLocks():Flow<List<SessionLoadLock>>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putLock(value:SessionLoadLock)
    @Query("DELETE FROM session_load_locks WHERE entryId=:id") suspend fun unlock(id:Long)
    @Query("DELETE FROM load_profiles") suspend fun clearProfiles()
}
val LOAD_ADVICE_MIGRATION_14_15=object:Migration(14,15){
    override fun migrate(db:SupportSQLiteDatabase){
        for(table in listOf("planned_sets","workout_set_targets")){db.execSQL("ALTER TABLE $table ADD COLUMN rir REAL");db.execSQL("ALTER TABLE $table ADD COLUMN rpe REAL")}
        db.execSQL("CREATE TABLE load_profiles (id TEXT NOT NULL PRIMARY KEY,exerciseId INTEGER NOT NULL,gym TEXT NOT NULL,equipment TEXT NOT NULL,basis TEXT NOT NULL,mode TEXT NOT NULL,unit TEXT NOT NULL,increment TEXT NOT NULL,targetRir REAL NOT NULL,repChoice TEXT NOT NULL,selected INTEGER NOT NULL,reviewedLegacy INTEGER NOT NULL,FOREIGN KEY(exerciseId) REFERENCES exercises(id) ON DELETE CASCADE)")
        db.execSQL("CREATE UNIQUE INDEX index_load_profiles_exerciseId_gym_equipment_basis_mode ON load_profiles(exerciseId,gym,equipment,basis,mode)")
        db.execSQL("CREATE TABLE set_load_contexts (setId INTEGER NOT NULL PRIMARY KEY,gym TEXT NOT NULL,equipment TEXT NOT NULL,basis TEXT NOT NULL,rir REAL,rpe REAL,FOREIGN KEY(setId) REFERENCES workout_sets(id) ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE session_load_locks (entryId INTEGER NOT NULL PRIMARY KEY,FOREIGN KEY(entryId) REFERENCES workout_exercises(id) ON DELETE CASCADE)")
    }
}
fun validateEffort(rir:Double?,rpe:Double?) {
    require(rir==null || rir.isFinite() && rir in 0.0..10.0){"RIR must be between 0 and 10."}
    require(rpe==null || rpe.isFinite() && rpe in 1.0..10.0){"RPE must be between 1 and 10 on the RIR-based scale."}
    require(rir==null || rpe==null || abs(rir-(10-rpe))<=0.5){"RIR and RPE disagree. Correct them or leave one blank."}
}
fun reserve(rir:Double?,rpe:Double?):Double? { validateEffort(rir,rpe);return rir ?: rpe?.let{10-it} }
fun validateLoadProfile(p:LoadProfile) {
    require(p.id.isNotBlank() && p.id.length<=128 && p.exerciseId>0){"Invalid load setup identity."}
    require(listOf(p.gym,p.equipment).all{it.isNotBlank() && it.length<=100 && it.none(Char::isISOControl)}){"Name the gym and specific equipment/variant (up to 100 characters)."}
    require(p.basis in setOf("total","per_hand","stack") && p.mode in LoadMode.values && p.mode!=LoadMode.UNKNOWN){"Confirm total/per-hand/stack basis and load mode."}
    require(p.unit in setOf("kg","lb") && runCatching{BigDecimal(p.increment)}.getOrNull()?.let{it>=BigDecimal("0.001") && it<=BigDecimal("500")}==true){"Choose kg/lb and an increment from 0.001 to 500."}
    require(p.targetRir.isFinite() && p.targetRir in 0.0..4.0 && p.repChoice in setOf("lower","upper")){"Choose target RIR 0–4 and lower/upper rep target."}
}
/** Context is observed/user-declared metadata. Editing an older result never
 * assigns today's gym/setup to it; missing historical effort stays missing. */
suspend fun captureSetLoadContext(db:FitDatabase,id:Long,exerciseId:Long,editing:Boolean,rir:String?,rpe:String?) {
    val old=db.loadDao().context(id);val profile=if(!editing)db.loadDao().selected(exerciseId) else null
    if(old==null && profile==null && rir==null && rpe==null)return
    val r=if(rir==null)old?.rir else optionalNonNegative(rir);val e=if(rpe==null)old?.rpe else optionalNonNegative(rpe)
    validateEffort(r,e)
    val context=(old ?: SetLoadContext(id,profile?.gym.orEmpty(),profile?.equipment.orEmpty(),profile?.basis ?: "unknown")).copy(rir=r,rpe=e)
    if(context.gym.isNotEmpty() || context.rir!=null || context.rpe!=null || old!=null)db.loadDao().putContext(context)
}
