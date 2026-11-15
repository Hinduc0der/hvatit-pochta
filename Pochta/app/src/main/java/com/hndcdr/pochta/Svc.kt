package com.hndcdr.pochta

import android.net.Uri

/** Почтовые сервисы. Порядок = порядок вкладок. */
enum class Svc(
    val key: String,
    val title: String,
    /** с чего открывать; сервис сам перекинет на мобильную/«touch»-версию или на вход */
    val home: String,
    /** домены, которые открываются внутри вкладки (почта, вход, статика). Всё прочее — во внешнем браузере */
    private val hosts: List<String>,
    val notifId: Int,
    /** что прятать CSS-ом (найдено на мобильных версиях 15.11.2026) */
    val css: String,
    /** что закрывать нажатием: селекторы кнопок-крестиков (если просто спрятать — может остаться блокировка прокрутки) */
    val closeButtons: List<String>,
    /** что удалять из DOM целиком (когда элемент несёт свой <style>, ломающий страницу) */
    val remove: List<String> = emptyList(),
) {
    YANDEX(
        "yandex", "Яндекс", "https://mail.yandex.ru/touch/",
        listOf("yandex.ru", "yandex.com", "yandex.net", "yandex.by", "yandex.kz", "ya.ru", "yastatic.net", "yandexcloud.net"),
        1,
        // .direct — места под Директ между письмами; BottomPageBanner и FloatingButton — «Открыть в приложении»
        // внизу (FloatingButton на 15.11.2026 единственная плавающая кнопка, ведёт в магазин приложений);
        // .sidebarPromo — «Приложение Яндекс Почта / Перейти» внизу бокового меню
        css = """.direct, .sidebarPromo, [class*="BottomPageBanner-m__banner"], [class*="FloatingButton-m__container"] { display: none !important; }""",
        // полноэкранное «Почта лучше в приложении»
        closeButtons = listOf("""[class*="PopupAggressivePromoApps"] button"""),
    ),
    MAILRU(
        "mailru", "Mail.ru", "https://e.mail.ru/inbox/",
        // вход в mail.ru теперь идёт через VK ID (id.vk.ru), ящик открывается на vk.mail.ru
        listOf("mail.ru", "imgsmail.ru", "vk.ru", "vk.com", "vk.me", "vkuser.net", "userapi.com", "ok.ru", "mycdn.me"),
        2,
        // .tp-1-overlay — полноэкранное «Почта в RuStore»; #banner188 и т.п. — рекламные места;
        // promo--open-in-app_* — «Приложение Почты / Скачать» в боковом меню (и прочие «открыть в приложении»)
        css = """.tp-1-overlay, div[id^="banner"], [id^="promo--open-in-app"], [class*="promo--open-in-app"] { display: none !important; }""",
        closeButtons = emptyList(),
        // внутри #tp-1-main лежит <style> «html, body { overflow: hidden !important }» — пока оверлей в DOM,
        // не прокручивается ничего, что опирается на обычную прокрутку (боковое меню). Удаляем целиком.
        remove = listOf("#tp-1-main", ".tp-1-overlay"),
    );

    fun owns(url: String): Boolean {
        val host = Uri.parse(url).host?.lowercase() ?: return false
        return hosts.any { host == it || host.endsWith(".$it") }
    }

    companion object {
        fun byKey(k: String?): Svc? = values().firstOrNull { it.key == k }
    }
}
