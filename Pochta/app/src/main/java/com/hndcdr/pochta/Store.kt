package com.hndcdr.pochta

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Настройки и состояние (SharedPreferences) */
class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("pochta", Context.MODE_PRIVATE)

    /** последнее известное число непрочитанных в ящике; -1 — ещё не знаем. key = «сервис|адрес» */
    fun last(key: String): Int = p.getInt("last_$key", -1)
    fun setLast(key: String, n: Int) = p.edit().putInt("last_$key", n).apply()

    /** ящики сервиса, по которым могли висеть уведомления (чтобы снять их все разом) */
    fun boxes(s: Svc): Set<String> = p.getStringSet("boxes_${s.key}", emptySet())!!
    fun setBoxes(s: Svc, ids: Set<String>) = p.edit().putStringSet("boxes_${s.key}", ids).apply()

    /** вход в сервис хоть раз работал (иначе «вход слетел» не с чего было бы слетать) */
    fun wasLoggedIn(s: Svc): Boolean = p.getBoolean("auth_ok_${s.key}", false)
    fun setLoggedIn(s: Svc) = p.edit().putBoolean("auth_ok_${s.key}", true).putBoolean("auth_warned_${s.key}", false).apply()
    /** уведомление «войдите заново» уже показано — не повторять на каждой проверке */
    fun authWarned(s: Svc): Boolean = p.getBoolean("auth_warned_${s.key}", false)
    fun setAuthWarned(s: Svc) = p.edit().putBoolean("auth_warned_${s.key}", true).apply()

    fun notifyOn(s: Svc): Boolean = p.getBoolean("notify_${s.key}", true)
    fun setNotifyOn(s: Svc, on: Boolean) = p.edit().putBoolean("notify_${s.key}", on).apply()

    /** Mail.ru: прятать в переключателе ящиков детские (привязанные к основным) */
    var hideChildren: Boolean
        get() = p.getBoolean("hide_children", true)
        set(v) = p.edit().putBoolean("hide_children", v).apply()

    var intervalMin: Int
        get() = p.getInt("interval", 15)
        set(v) = p.edit().putInt("interval", v).apply()

    var tab: Svc
        get() = Svc.byKey(p.getString("tab", null)) ?: Svc.YANDEX
        set(v) = p.edit().putString("tab", v.key).apply()

    /** правила «Скрыть элемент», по одному CSS-селектору в строке */
    fun rules(s: Svc): List<String> =
        p.getString("rules_${s.key}", "")!!.split('\n').filter { it.isNotBlank() }

    fun addRule(s: Svc, sel: String) {
        val r = rules(s).toMutableList()
        if (sel !in r) r += sel
        p.edit().putString("rules_${s.key}", r.joinToString("\n")).apply()
    }

    fun clearRules(s: Svc) = p.edit().remove("rules_${s.key}").apply()
}

/** Журнал в файл — для прототипа: видно, что отвечают счётчики и не разлогинило ли */
object Log {
    private val fmt = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US)

    @Synchronized
    fun w(ctx: Context, msg: String) {
        android.util.Log.i("Pochta", msg)
        val f = file(ctx)
        try {
            f.appendText("${fmt.format(Date())}  $msg\n")
            if (f.length() > 64 * 1024) {
                val lines = f.readLines()
                f.writeText(lines.takeLast(300).joinToString("\n", postfix = "\n"))
            }
        } catch (_: Exception) {
        }
    }

    fun read(ctx: Context): String = try { file(ctx).readText() } catch (_: Exception) { "" }

    fun clear(ctx: Context) { file(ctx).delete() }

    private fun file(ctx: Context) = File(ctx.filesDir, "log.txt")
}
