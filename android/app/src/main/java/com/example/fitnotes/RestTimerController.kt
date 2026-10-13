package com.example.fitnotes

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.room.withTransaction
import com.example.fitnotes.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class AndroidRestClock(private val context: Context) : RestClock {
    override fun elapsed() = SystemClock.elapsedRealtime()
    override fun bootCount() = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1).let{if(it>=0)it else fallbackIdentity}
    companion object { private val fallbackIdentity=-(UUID.randomUUID().hashCode().and(Int.MAX_VALUE).coerceAtLeast(2)) }
}
class RestTimerController(private val context: Context, private val db: FitDatabase, val clock: RestClock = AndroidRestClock(context)) {
    private val dao = db.trainingDao()
    suspend fun start(workoutId: Long, seconds: Int, targetId: Long? = null) {
        require(seconds in 1..7200) { "Rest must be 1–7,200 seconds." }
        db.withTransaction {
            require(db.dao().active()?.id == workoutId) { "Rest belongs to an active workout." }
            dao.putTimer(RestTimerState(workoutId = workoutId, token = UUID.randomUUID().toString(), sourceTargetId = targetId,
                remainingMillis = seconds * 1000L, anchorElapsed = clock.elapsed(), bootCount = clock.bootCount(), running = true))
        }
        sync()
    }
    suspend fun afterCompletion(targetId: Long) {
        if (dao.preferences()?.automaticRest == false) return
        val target = dao.target(targetId) ?: return
        val workoutId = db.dao().entry(target.entryId)?.workoutId ?: return
        if (db.dao().active()?.id != workoutId) return
        val run = db.planDao().runDetail(workoutId) ?: return
        val entryIds = run.exercises.map { it.entry.id }.toSet()
        val results = db.dao().sets().filter { it.entryId in entryIds }
        if (shouldRestAfter(run, target, results)) target.restSeconds?.takeIf { it > 0 }?.let { start(workoutId, it, targetId) }
    }
    suspend fun action(token: String, action: String) {
        var alertToken:String?=null
        db.withTransaction {
            val timer = dao.timer()?.takeIf { it.token == token } ?: return@withTransaction
            if (db.dao().active()?.id != timer.workoutId) { dao.clearTimer(); return@withTransaction }
            val recovered = timer.recovered(clock)
            when (action) {
                "pause" -> dao.putTimer(recovered.pause(clock))
                "resume" -> dao.putTimer(recovered.resume(clock))
                "extend" -> dao.putTimer(recovered.extend(clock).let { if(recovered.alerted)it.copy(token=UUID.randomUUID().toString()) else it })
                "skip" -> dao.clearTimer()
                "expire" -> if (!recovered.alerted && recovered.running && recovered.remaining(clock) == 0L)
                    { dao.putTimer(recovered.copy(remainingMillis = 0, running = false, alerted = true, anchorElapsed = clock.elapsed()));alertToken=token }
            }
        }
        sync(alertToken)
    }
    suspend fun sync(alertToken:String?=null) {
        syncMutex.withLock {
        val timer = db.withTransaction {
            val saved = dao.timer() ?: return@withTransaction null
            if (db.dao().active()?.id != saved.workoutId) { dao.clearTimer(); return@withTransaction null }
            val recovered = saved.recovered(clock)
            if (recovered != saved) dao.putTimer(recovered)
            recovered
        }
        val preferences = context.getSharedPreferences("rest-notifications",Context.MODE_PRIVATE)
        val previousToken=preferences.getString("scheduled-token",null)
        if(previousToken!=null && (timer==null || timer.token!=previousToken || !timer.running)) {
            val stale=PendingIntent.getBroadcast(context,0,Intent(context,RestTimerReceiver::class.java).setData(Uri.parse("fitnotes-rest://$previousToken/expire")),PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            if(stale!=null) { (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(stale);stale.cancel() }
            preferences.edit().remove("scheduled-token").apply()
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (timer == null) { manager.cancel(NOTIFICATION_ID); return }
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val expiration = actionIntent(timer, "expire")
        alarm.cancel(expiration)
        if (timer.running) { alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, clock.elapsed() + timer.remaining(clock), expiration); preferences.edit().putString("scheduled-token",timer.token).apply() }
        val shouldAlert=timer.alerted && alertToken==timer.token && preferences.getString("last-alert-token",null)!=timer.token
        if(shouldAlert)preferences.edit().putString("last-alert-token",timer.token).apply()
        if (dao.preferences()?.restAlerts != true || !NotificationManagerCompat.from(context).areNotificationsEnabled()) { manager.cancel(NOTIFICATION_ID); return }
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel("rest-progress", "Rest controls", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel("rest-ready", "Rest finished", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if(shouldAlert) manager.cancel(NOTIFICATION_ID)
        val notification = NotificationCompat.Builder(context, if (timer.alerted) "rest-ready" else "rest-progress")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle(if (timer.alerted) "Rest finished" else if (timer.running) "Rest in progress" else "Rest paused")
            .setContentText(if (timer.alerted) "Ready for the next set" else "Open FitNotes for the countdown")
            .setContentIntent(open).setShowWhen(false).setOnlyAlertOnce(true).setSilent(timer.alerted && !shouldAlert).setOngoing(timer.running)
        if (!timer.alerted) {
            notification.addAction(0, if (timer.running) "Pause" else "Resume", actionIntent(timer, if (timer.running) "pause" else "resume"))
            notification.addAction(0, "+30 s", actionIntent(timer, "extend"))
        }
        notification.addAction(0, "Skip", actionIntent(timer, "skip"))
        try { manager.notify(NOTIFICATION_ID, notification.build()) } catch (_: SecurityException) { /* Permission may be revoked while updating. */ }
        }
    }
    private fun actionIntent(timer: RestTimerState, action: String): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, RestTimerReceiver::class.java).setData(Uri.parse("fitnotes-rest://${timer.token}/$action"))
            .putExtra("token", timer.token).putExtra("action", action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    companion object { const val NOTIFICATION_ID = 7421; private val syncMutex = Mutex() }
}
class RestTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val db = FitDatabase.open(context)
            try {
                val controller = RestTimerController(context, db)
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) controller.sync()
                else controller.action(intent.getStringExtra("token").orEmpty(), intent.getStringExtra("action").orEmpty())
            } finally { db.close(); pending.finish() }
        }
    }
}
