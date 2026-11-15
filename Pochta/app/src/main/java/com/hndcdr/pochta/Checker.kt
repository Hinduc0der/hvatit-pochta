package com.hndcdr.pochta

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebSettings

/** Проверка всех ящиков: сравнить с прошлым числом непрочитанных, при росте — уведомление */
object Checker {
    private const val JOB_ID = 1
    private const val CHANNEL = "mail"

    /** Снимок cookies и UA. Вызывать на главном потоке: CookieManager живёт там же, где WebView */
    class Snapshot(val cookies: Map<Svc, String?>, val ua: String)

    fun snapshot(ctx: Context): Snapshot {
        val cm = CookieManager.getInstance()
        return Snapshot(Svc.values().associateWith { cm.getCookie(Counter.cookieUrl(it)) }, userAgent(ctx))
    }

    /** UA обычного мобильного Chrome: «; wv» сервисы считывают как WebView и начинают звать в своё приложение */
    fun userAgent(ctx: Context): String =
        WebSettings.getDefaultUserAgent(ctx).replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")

    /**
     * Сетевая часть — НЕ на главном потоке. Возвращает ящики по сервисам (null — ошибка).
     * synchronized: Huawei запускал периодическую задачу дважды в одну секунду — без замка оба прогона
     * видели одно «было» и могли прислать два одинаковых уведомления.
     */
    @Synchronized
    fun run(ctx: Context, snap: Snapshot, why: String): Map<Svc, List<Counter.Box>?> {
        val st = Store(ctx)
        val out = HashMap<Svc, List<Counter.Box>?>()
        for (s in Svc.values()) {
            val r = Counter.fetch(s, snap.cookies[s], snap.ua)
            out[s] = r.boxes
            if (r.error != null) Log.w(ctx, "[$why] ${s.title}: ошибка — ${r.error}")
            val boxes = r.boxes ?: continue
            st.setBoxes(s, st.boxes(s) + boxes.map { it.id })
            for (b in boxes) {
                val key = "${s.key}|${b.id}"
                val prev = st.last(key)
                Log.w(ctx, "[$why] ${s.title} ${b.id}: непрочитанных ${b.unread} (было $prev)")
                if (prev >= 0 && b.unread > prev && st.notifyOn(s) && MainActivity.visibleTab != s) {
                    notify(ctx, s, b, b.unread - prev)
                } else if (b.unread == 0) {
                    cancel(ctx, s, b.id)
                }
                st.setLast(key, b.unread)
            }
        }
        return out
    }

    fun schedule(ctx: Context) {
        val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val ms = Store(ctx).intervalMin * 60_000L
        val existing = js.allPendingJobs.firstOrNull { it.id == JOB_ID }
        if (existing != null && existing.intervalMillis == ms) return
        val info = JobInfo.Builder(JOB_ID, ComponentName(ctx, CheckJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(ms)
            .setPersisted(true)
            .build()
        js.schedule(info)
        Log.w(ctx, "проверка запланирована раз в ${ms / 60000} мин")
    }

    private fun channel(ctx: Context, nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Новые письма", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    fun notify(ctx: Context, s: Svc, box: Counter.Box, added: Int) {
        val unread = box.unread
        val who = if (box.id.isEmpty()) s.title else "${s.title} · ${box.id}"
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        channel(ctx, nm)
        val open = Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_SVC, s.key)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = PendingIntent.getActivity(ctx, s.notifId, open, flags)
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL) else Notification.Builder(ctx)
        val n = b.setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(if (added == 1) "$who: новое письмо" else "$who: новых писем — $added")
            .setContentText("Непрочитанных: $unread")
            .setNumber(unread)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(tag(s, box.id), s.notifId, n)
    }

    private fun tag(s: Svc, id: String) = "${s.key}|$id"

    /** снять уведомление одного ящика (id) или всех ящиков сервиса (id = null) */
    fun cancel(ctx: Context, s: Svc, id: String? = null) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ids = if (id != null) setOf(id) else Store(ctx).boxes(s) + ""
        for (i in ids) nm.cancel(tag(s, i), s.notifId)
    }
}

/** Периодическая задача JobScheduler (не чаще раза в 15 мин — ограничение Android) */
class CheckJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val snap = try {
            Checker.snapshot(this)   // onStartJob вызывается на главном потоке
        } catch (e: Exception) {
            Log.w(this, "фон: нет доступа к cookies — ${e.message}")
            return false
        }
        Thread {
            try {
                Checker.run(this, snap, "фон")
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}
