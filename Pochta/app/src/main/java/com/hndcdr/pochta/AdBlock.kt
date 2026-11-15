package com.hndcdr.pochta

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Блокировка рекламы в два слоя:
 *  1) сеть — запросы к рекламным/счётным доменам получают пустой ответ (shouldInterceptRequest);
 *  2) вид — CSS прячет то, что приходит с «своего» домена (Яндекс отдаёт часть рекламы с mail.yandex.ru
 *     под шифрованными путями, сетью её не отличить). Сюда же — правила, набранные «Скрыть элемент».
 */
object AdBlock {
    /** домен блокируется вместе со всеми поддоменами */
    private val hosts = setOf(
        // Яндекс: Директ, РСЯ, Метрика, Adfox
        "an.yandex.ru", "yabs.yandex.ru", "mc.yandex.ru", "mc.yandex.com", "adfox.yandex.ru", "ads.adfox.ru",
        "adfox.ru", "awaps.yandex.net", "bs.yandex.ru", "appmetrica.yandex.ru", "adsdk.yandex.ru",
        "mobile.yandexadexchange.net", "yandexadexchange.net", "ads.yandex.ru",
        // Mail.ru / VK: myTarget, счётчики, «рентген»
        "ad.mail.ru", "rs.mail.ru", "r3.mail.ru", "rb.mail.ru", "top-fwz1.mail.ru", "xray.mail.ru",
        "trg.mail.ru", "target.my.com", "ads.vk.com", "ad.vk.ru", "ad.vk.com", "mediator.mail.ru",
        "privacy-cs.mail.ru", "an.vk.ru",
        // общее
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
        "googletagmanager.com", "adriver.ru", "adhigh.net", "betweendigital.com", "mytarget.ru",
    )

    /** куски пути на своих доменах */
    private val paths = listOf(
        "yandex.ru/clck/",            // счётчик кликов
        "mail.yandex.ru/web-api/journal/",
        "yandex.ru/ads/", "yandex.ru/adfox/",
        "mail.ru/adp/",
    )

    val blocked = AtomicInteger()

    fun check(url: String): WebResourceResponse? {
        if (!isAd(url)) return null
        blocked.incrementAndGet()
        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
    }

    fun isAd(url: String): Boolean {
        val u = Uri.parse(url)
        val host = u.host?.lowercase() ?: return false
        var h = host
        while (true) {
            if (h in hosts) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) break   // дошли до «yandex.ru» — дальше не режем
            h = h.substring(dot + 1)
        }
        val hp = host + (u.path ?: "")
        return paths.any { hp.contains(it) }
    }

    /** Встроенные косметические правила. Осторожно: лишнее правило ломает интерфейс — лучше «Скрыть элемент». */
    private const val BASE_CSS = """
iframe[src*="an.yandex.ru"], iframe[src*="ad.mail.ru"], iframe[src*="adfox"],
[id^="adfox_"], [id^="yandex_rtb"], [data-ad], [data-testid*="advert"], [class*="AdvBanner"],
[data-testid="app-promo"], [class*="smart-banner"], [class*="SmartBanner"]
{ display: none !important; }
"""

    fun css(s: Svc, userRules: List<String>): String {
        val sb = StringBuilder(BASE_CSS).append(s.css).append('\n')
        for (r in userRules) sb.append(r).append(" { display: none !important; }\n")
        return sb.toString()
    }

    /**
     * JS: положить/обновить <style id="pochta-css"> и следить за DOM — промо-окна появляются не сразу,
     * их крестики нажимаем сами (не чаще раза в 300 мс).
     */
    fun injectJs(css: String, close: List<String>, remove: List<String>): String {
        val q = org.json.JSONObject.quote(css)
        val c = org.json.JSONArray(close).toString()
        val rm = org.json.JSONArray(remove).toString()
        return """(function(){var s=document.getElementById('pochta-css');
if(!s){s=document.createElement('style');s.id='pochta-css';(document.head||document.documentElement).appendChild(s);}
s.textContent=$q;
window.__pochtaClose=$c;window.__pochtaRemove=$rm;
window.__pochtaRemove.forEach(function(sel){document.querySelectorAll(sel).forEach(function(e){e.remove();});});
if(window.__pochtaObs||!window.MutationObserver||!document.body)return;
var t=0;function run(){t=0;
window.__pochtaRemove.forEach(function(sel){document.querySelectorAll(sel).forEach(function(e){e.remove();});});
window.__pochtaClose.forEach(function(sel){var b=document.querySelector(sel);if(b){try{b.click();}catch(e){}}});}
window.__pochtaObs=new MutationObserver(function(){if(!t)t=setTimeout(run,300);});
window.__pochtaObs.observe(document.body,{childList:true,subtree:true});run();})();"""
    }
}
