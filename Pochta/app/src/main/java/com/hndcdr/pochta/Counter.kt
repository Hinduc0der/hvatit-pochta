package com.hndcdr.pochta

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Число непрочитанных — теми же запросами, что делает сама веб-почта, с cookies из WebView.
 * Это внутренние API, не документированы: если сломаются — почта во вкладке работает, отвалятся только уведомления.
 *
 *  Яндекс:  POST mail.yandex.ru/web-api/models/liza1?_m=app-badge-counter
 *           → models[0].data.counters.unread   (проверено 15.11.2026, ckey не нужен)
 *  Mail.ru: GET  portal.mail.ru/NaviData?mac=1 → data.email, data.mail_cnt (строка), data.list — все ящики сессии.
 *           Для другого ящика сессии — &Login=<адрес>.
 */
object Counter {
    /** ящик: id — адрес (или "" если сервис его не сообщает) */
    class Box(val id: String, val unread: Int)
    class Result(val boxes: List<Box>?, val error: String?)

    /** адрес, cookies которого нужны запросу (берутся из CookieManager на главном потоке) */
    fun cookieUrl(s: Svc) = when (s) {
        Svc.YANDEX -> "https://mail.yandex.ru/"
        // общие cookies .mail.ru + собственные touch.mail.ru (нужны API папок); portal их тоже примет
        Svc.MAILRU -> "https://touch.mail.ru/"
    }

    fun fetch(s: Svc, cookies: String?, ua: String): Result {
        if (cookies.isNullOrBlank()) return Result(null, "нет cookies — не выполнен вход")
        return try {
            when (s) {
                Svc.YANDEX -> yandex(cookies, ua)
                Svc.MAILRU -> mailru(cookies, ua)
            }
        } catch (e: Exception) {
            Result(null, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun yandex(cookies: String, ua: String): Result {
        val body = """{"models":[{"name":"app-badge-counter","params":{}}]}"""
        val (code, text) = http(
            "https://mail.yandex.ru/web-api/models/liza1?_m=app-badge-counter", cookies, ua, body,
            mapOf("Origin" to "https://mail.yandex.ru", "Referer" to "https://mail.yandex.ru/touch/"),
        )
        if (code != 200) return Result(null, "HTTP $code ${text.take(150)}")
        val m = JSONObject(text).optJSONArray("models")?.optJSONObject(0)
            ?: return Result(null, "нет models: ${text.take(150)}")
        if (m.optString("status") != "ok") return Result(null, "status=${m.optString("status")} ${m.toString().take(150)}")
        val c = m.optJSONObject("data")?.optJSONObject("counters")
            ?: return Result(null, "нет counters: ${m.toString().take(150)}")
        return Result(listOf(Box("", c.getInt("unread"))), null)
    }

    private class Navi(val email: String, val cnt: Int, val list: List<String>)

    private fun navi(cookies: String, ua: String, login: String?): Navi {
        val q = if (login == null) "" else "&Login=" + URLEncoder.encode(login, "UTF-8")
        val (code, text) = http(
            "https://portal.mail.ru/NaviData?mac=1$q&_=${System.currentTimeMillis()}", cookies, ua, null,
            mapOf("Referer" to "https://touch.mail.ru/"),
        )
        if (code != 200) throw IllegalStateException("HTTP $code ${text.take(150)}")
        val j = JSONObject(text)
        if (j.optString("status") != "ok") throw IllegalStateException("status=${j.optString("status")} — вероятно, выход из ящика")
        val d = j.optJSONObject("data") ?: throw IllegalStateException("нет data")
        val cnt = d.optString("mail_cnt", "")
        if (cnt.isEmpty()) throw IllegalStateException("нет mail_cnt — вероятно, выход из ящика")
        val list = ArrayList<String>()
        when (val l = d.opt("list")) {
            is JSONArray -> for (i in 0 until l.length()) item(l.get(i))?.let { list += it }
            is JSONObject -> for (k in l.keys()) item(l.get(k))?.let { list += it }
        }
        return Navi(d.optString("email"), cnt.toIntOrNull() ?: 0, list)
    }

    private fun item(v: Any?): String? = when (v) {
        is String -> v.takeIf { '@' in it }
        is JSONObject -> v.optString("email").takeIf { '@' in it }
        else -> null
    }

    /**
     * NaviData даёт список ящиков, но mail_cnt — сумма непрочитанных по ВСЕМ папкам (соцсети, рассылки, чеки…):
     * 2301 против 107 во «Входящих». Поэтому число берём из API мобильной версии — только папка type=inbox:
     *   POST touch.mail.ru/api/v1/tokens?email=E          → body.token
     *   GET  touch.mail.ru/api/v1/folders?email=E&token=T → body[] {type, messages_unread}
     * Не вышло — откатываемся на mail_cnt (лучше лишнее уведомление, чем никакого).
     */
    private fun mailru(cookies: String, ua: String): Result {
        val main = navi(cookies, ua, null)
        val emails = linkedSetOf(main.email)
        emails += main.list
        val boxes = ArrayList<Box>()
        val errs = ArrayList<String>()
        for (e in emails) {
            try {
                boxes += Box(e, inbox(cookies, ua, e))
            } catch (x: Exception) {
                errs += "$e: папки не получены (${x.message}), беру общее число"
                try {
                    val n = if (e.equals(main.email, true)) main else navi(cookies, ua, e)
                    if (n.email.equals(e, true)) boxes += Box(e, n.cnt)
                    else errs += "$e: ответ NaviData пришёл для ${n.email}"
                } catch (y: Exception) {
                    errs += "$e: ${y.message}"
                }
            }
        }
        return Result(boxes.takeIf { it.isNotEmpty() }, errs.takeIf { it.isNotEmpty() }?.joinToString("; "))
    }

    private fun inbox(cookies: String, ua: String, email: String): Int {
        val enc = URLEncoder.encode(email, "UTF-8")
        val hdr = mapOf("Origin" to "https://touch.mail.ru", "Referer" to "https://touch.mail.ru/cgi-bin/msglist")
        val (c1, t1) = http("https://touch.mail.ru/api/v1/tokens?email=$enc", cookies, ua, "", hdr)
        val token = JSONObject(t1).optJSONObject("body")?.optString("token")
        if (c1 != 200 || token.isNullOrEmpty()) throw IllegalStateException("tokens: HTTP $c1 ${t1.take(100)}")
        val (c2, t2) = http(
            "https://touch.mail.ru/api/v1/folders?htmlencoded=false&email=$enc&token=" + URLEncoder.encode(token, "UTF-8"),
            cookies, ua, null, hdr,
        )
        val j = JSONObject(t2)
        if (c2 != 200 || j.optInt("status") != 200) throw IllegalStateException("folders: HTTP $c2 ${t2.take(100)}")
        val arr = j.optJSONArray("body") ?: j.optJSONObject("body")?.optJSONArray("folders")
            ?: throw IllegalStateException("folders: нет списка")
        for (i in 0 until arr.length()) {
            val f = arr.getJSONObject(i)
            if (f.optString("type") == "inbox") return f.optInt("messages_unread")
        }
        throw IllegalStateException("folders: нет папки inbox")
    }

    private fun http(url: String, cookies: String, ua: String, body: String?, extra: Map<String, String>): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000
            c.readTimeout = 15000
            c.instanceFollowRedirects = false   // редирект = прислали на страницу входа
            c.setRequestProperty("Cookie", cookies)
            c.setRequestProperty("User-Agent", ua)
            c.setRequestProperty("Accept", "application/json, text/plain, */*")
            for ((k, v) in extra) c.setRequestProperty(k, v)
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return code to text
        } finally {
            c.disconnect()
        }
    }
}
