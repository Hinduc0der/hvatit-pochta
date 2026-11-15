package com.hndcdr.pochta

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        const val EXTRA_SVC = "svc"
        private const val REQ_FILE = 10

        /** какая вкладка сейчас на экране (null — приложение свёрнуто): по ней не шлём уведомления */
        @Volatile var visibleTab: Svc? = null
    }

    private lateinit var st: Store
    private val webs = HashMap<Svc, WebView>()
    private val tabs = HashMap<Svc, TextView>()
    private val counts = HashMap<Svc, Int>()
    private lateinit var progress: ProgressBar
    private lateinit var pickBar: LinearLayout
    private var cur = Svc.YANDEX
    private var fileCb: ValueCallback<Array<Uri>>? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        st = Store(this)
        if (Build.VERSION.SDK_INT >= 19 && (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)   // chrome://inspect с ПК — для подбора правил
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // верхняя полоса: вкладки + меню
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#EEEEEE"))
        }
        for (s in Svc.values()) {
            val t = TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 16f
                setPadding(dp(8), dp(12), dp(8), dp(12))
                setOnClickListener { select(s) }
            }
            tabs[s] = t
            bar.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val menuBtn = TextView(this).apply {
            text = "⋮"
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(6), dp(16), dp(6))
            setOnClickListener { showMenu(it) }
        }
        bar.addView(menuBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(bar)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))

        val frame = FrameLayout(this)
        CookieManager.getInstance().setAcceptCookie(true)
        for (s in Svc.values()) {
            val w = makeWeb(s)
            webs[s] = w
            frame.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        // панель режима «Скрыть элемент»
        pickBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#DD222222"))
            visibility = View.GONE
            val hint = TextView(context).apply {
                text = "Коснитесь блока"
                setTextColor(Color.WHITE)
                setPadding(dp(12), 0, dp(4), 0)
                gravity = Gravity.CENTER_VERTICAL
            }
            addView(hint, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(btn("Шире") { js(cur, "window.__pickUp&&__pickUp()") })
            addView(btn("Скрыть") { pickDone(true) })
            addView(btn("Отмена") { pickDone(false) })
        }
        frame.addView(pickBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52), Gravity.BOTTOM))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        for (s in Svc.values()) webs[s]!!.loadUrl(s.home)
        select(Svc.byKey(intent.getStringExtra(EXTRA_SVC)) ?: st.tab)

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        Checker.schedule(this)
    }

    private fun btn(label: String, on: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { on() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Svc.byKey(intent.getStringExtra(EXTRA_SVC))?.let { select(it); webs[it]!!.reload() }
    }

    private fun select(s: Svc) {
        cur = s
        st.tab = s
        visibleTab = s
        for ((k, w) in webs) w.visibility = if (k == s) View.VISIBLE else View.GONE
        Checker.cancel(this, s)
        if (pickBar.visibility == View.VISIBLE) pickDone(false)
        refreshTabs()
        progress.progress = webs[s]!!.progress
        progress.visibility = if (webs[s]!!.progress in 1..99) View.VISIBLE else View.INVISIBLE
    }

    private fun refreshTabs() {
        for (s in Svc.values()) {
            val t = tabs[s]!!
            val n = counts[s] ?: 0
            t.text = if (n > 0) "${s.title} · $n" else s.title
            t.setTypeface(null, if (s == cur) Typeface.BOLD else Typeface.NORMAL)
            t.setBackgroundColor(if (s == cur) Color.WHITE else Color.TRANSPARENT)
        }
    }

    // ---------- WebView ----------

    private fun makeWeb(s: Svc): WebView {
        val w = WebView(this)
        with(w.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = Checker.userAgent(this@MainActivity)
        }
        // вход в mail.ru идёт через id.vk.ru — без сторонних cookies не залогиниться
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.addJavascriptInterface(PickerBridge(s), "PochtaPicker")

        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                AdBlock.check(request.url.toString())

            @Deprecated("API < 21")
            override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? = AdBlock.check(url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.isForMainFrame && route(s, view, request.url.toString())

            @Deprecated("API < 24")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = route(s, view, url)

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = injectCss(s)
            override fun onPageCommitVisible(view: WebView, url: String) = injectCss(s)
            override fun onPageFinished(view: WebView, url: String) {
                injectCss(s)
                CookieManager.getInstance().flush()
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) {
                if (s != cur) return
                progress.progress = p
                progress.visibility = if (p in 1..99) View.VISIBLE else View.INVISIBLE
                if (p > 30) injectCss(s)
            }

            override fun onReceivedTitle(view: WebView, title: String?) = onTitle(s, title)

            override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                val i = params.createIntent()
                if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                return try {
                    startActivityForResult(i, REQ_FILE); true
                } catch (e: ActivityNotFoundException) {
                    fileCb = null; false
                }
            }

            // target=_blank / window.open: своё — в этой же вкладке, чужое — во внешний браузер
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val tmp = WebView(this@MainActivity)
                var done = false
                fun take(url: String) {
                    if (done || url == "about:blank" || url.isEmpty()) return
                    done = true
                    if (s.owns(url)) view.loadUrl(url) else openOutside(url)
                    tmp.post { tmp.destroy() }
                }
                tmp.webViewClient = object : WebViewClient() {
                    @Deprecated("")
                    override fun shouldOverrideUrlLoading(v: WebView, url: String): Boolean { take(url); return true }
                    override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) { take(url); v.stopLoading() }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = tmp
                resultMsg.sendToTarget()
                return true
            }
        }

        w.setDownloadListener { url, ua, cd, mime, _ -> download(url, ua, cd, mime) }
        return w
    }

    /** true — ссылку забрали (открыли снаружи) */
    private fun route(s: Svc, view: WebView, url: String): Boolean {
        val scheme = Uri.parse(url).scheme?.lowercase()
        if (scheme == "http" || scheme == "https") {
            if (s.owns(url)) return false
            openOutside(url); return true
        }
        if (scheme == "about" || scheme == "javascript" || scheme == "data" || scheme == "blob") return false
        openOutside(url)   // mailto:, tel:, intent: и т.п.
        return true
    }

    private fun openOutside(url: String) {
        try {
            val i = if (url.startsWith("intent:")) Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            else Intent(Intent.ACTION_VIEW, Uri.parse(url))
            i.addCategory(Intent.CATEGORY_BROWSABLE)
            i.component = null
            i.selector = null
            startActivity(i)
        } catch (e: Exception) {
            toast("Нечем открыть: $url")
        }
    }

    private fun injectCss(s: Svc) {
        val rules = st.rules(s).toMutableList()
        // детские ящики в переключателе Mail.ru помечены классом multiauth__item_child (15.11.2026)
        if (s == Svc.MAILRU && st.hideChildren) rules += ".multiauth__item_child"
        js(s, AdBlock.injectJs(AdBlock.css(s, rules), s.closeButtons, s.remove))
    }

    private fun js(s: Svc, code: String) = webs[s]?.evaluateJavascript(code, null)

    /** «26 · Входящие — Яндекс Почта», «(3) Входящие - VK Почта» → число непрочитанных */
    private fun onTitle(s: Svc, title: String?) {
        val m = Regex("""^\(?(\d+)\)?\s*[·(\-–—|]""").find(title ?: "") ?: Regex("""^\((\d+)\)""").find(title ?: "")
        val n = m?.groupValues?.get(1)?.toIntOrNull()
        if (n == null) {
            // в заголовке нет числа: либо 0, либо открыт не список писем — меняем только для «Входящие»
            if (title?.startsWith("Входящие") == true) setCount(s, 0)
            return
        }
        setCount(s, n)
    }

    private fun setCount(s: Svc, n: Int) {
        counts[s] = n
        refreshTabs()
    }

    // ---------- загрузка вложений ----------

    private fun download(url: String, ua: String, cd: String?, mime: String?) {
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            toast("Такую загрузку прототип пока не умеет (blob:)")
            Log.w(this, "загрузка blob/data не поддержана: ${url.take(60)}")
            return
        }
        if (Build.VERSION.SDK_INT in 23..28 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 2)
            toast("Разрешите доступ к памяти и нажмите ещё раз")
            return
        }
        try {
            val name = URLUtil.guessFileName(url, cd, mime)
            val r = DownloadManager.Request(Uri.parse(url))
                .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                .addRequestHeader("User-Agent", ua)
                .setMimeType(mime)
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(r)
            toast("Загрузка: $name")
        } catch (e: Exception) {
            toast("Не удалось скачать: ${e.message}")
        }
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_FILE) return super.onActivityResult(requestCode, resultCode, data)
        val cb = fileCb ?: return
        fileCb = null
        if (resultCode != RESULT_OK || data == null) { cb.onReceiveValue(null); return }
        val clip = data.clipData
        val uris = if (clip != null) Array(clip.itemCount) { clip.getItemAt(it).uri }
        else data.data?.let { arrayOf(it) }
        cb.onReceiveValue(uris)
    }

    // ---------- «Скрыть элемент» ----------

    inner class PickerBridge(private val s: Svc) {
        @JavascriptInterface
        fun add(selector: String) {
            runOnUiThread {
                if (selector.isBlank()) return@runOnUiThread
                st.addRule(s, selector)
                Log.w(this@MainActivity, "${s.title}: скрыто правило  $selector")
                injectCss(s)
                toast("Скрыто")
            }
        }
    }

    private fun pickStart() {
        pickBar.visibility = View.VISIBLE
        js(cur, PICKER_JS)
    }

    private fun pickDone(ok: Boolean) {
        pickBar.visibility = View.GONE
        js(cur, "window.__pickDone&&__pickDone(${ok})")
    }

    // ---------- меню ----------

    private fun showMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        val m = pm.menu
        m.add(0, 1, 0, "Проверить почту сейчас")
        m.add(0, 2, 0, "Обновить страницу")
        m.add(0, 3, 0, "Скрыть элемент…")
        m.add(0, 4, 0, "Вернуть скрытые (${cur.title}: ${st.rules(cur).size})")
        m.add(0, 5, 0, "Уведомления ${cur.title}").apply { isCheckable = true; isChecked = st.notifyOn(cur) }
        m.add(0, 6, 0, "Интервал проверки: ${st.intervalMin} мин")
        if (cur == Svc.MAILRU) m.add(0, 8, 0, "Скрывать детские ящики").apply { isCheckable = true; isChecked = st.hideChildren }
        m.add(0, 7, 0, "Журнал")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> checkNow()
                2 -> webs[cur]!!.reload()
                3 -> pickStart()
                4 -> { st.clearRules(cur); webs[cur]!!.reload() }
                5 -> st.setNotifyOn(cur, !st.notifyOn(cur))
                6 -> chooseInterval()
                7 -> startActivity(Intent(this, LogActivity::class.java))
                8 -> { st.hideChildren = !st.hideChildren; injectCss(Svc.MAILRU) }
            }
            true
        }
        pm.show()
    }

    /** quiet — без всплывающих сообщений (при возврате в приложение) */
    private fun checkNow(quiet: Boolean = false) {
        CookieManager.getInstance().flush()
        val snap = Checker.snapshot(this)
        if (!quiet) toast("Проверяю…")
        Thread {
            val r = Checker.run(this, snap, if (quiet) "открытие" else "вручную")
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                for ((s, boxes) in r) if (boxes != null) counts[s] = boxes.sumOf { it.unread }
                refreshTabs()
                if (!quiet) toast(Svc.values().joinToString("\n") { s ->
                    val b = r[s]
                    when {
                        b == null -> "${s.title}: ошибка (см. журнал)"
                        b.size == 1 -> "${s.title}: ${b[0].unread}"
                        else -> "${s.title}: " + b.joinToString(", ") { "${it.id} — ${it.unread}" }
                    }
                })
            }
        }.start()
    }

    private fun chooseInterval() {
        val opts = intArrayOf(15, 30, 60, 180)
        val labels = opts.map { "$it мин" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Проверять почту")
            .setSingleChoiceItems(labels, opts.indexOf(st.intervalMin).coerceAtLeast(0)) { d, i ->
                st.intervalMin = opts[i]
                Checker.schedule(this)
                d.dismiss()
            }
            .show()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    // ---------- жизненный цикл ----------

    override fun onResume() {
        super.onResume()
        visibleTab = cur
        Checker.cancel(this, cur)
        for (w in webs.values) w.onResume()
        checkNow(quiet = true)
    }

    override fun onPause() {
        visibleTab = null
        CookieManager.getInstance().flush()
        for (w in webs.values) w.onPause()
        super.onPause()
    }

    @Deprecated("Activity API")
    override fun onBackPressed() {
        if (pickBar.visibility == View.VISIBLE) { pickDone(false); return }
        val w = webs[cur]!!
        if (w.canGoBack()) w.goBack() else moveTaskToBack(true)   // не убиваем: вход и страница сохранятся
    }

    override fun onDestroy() {
        for (w in webs.values) w.destroy()
        super.onDestroy()
    }
}

/** Выбор элемента касанием: красная рамка, «Шире» — родитель, «Скрыть» — отдать селектор в PochtaPicker.add */
private const val PICKER_JS = """
(function(){
 if(window.__pick) return; window.__pick=1;
 var box=document.createElement('div');
 box.style.cssText='position:fixed;z-index:2147483647;pointer-events:none;border:2px solid #f00;background:rgba(255,0,0,.25);display:none';
 document.documentElement.appendChild(box);
 var cur=null;
 function esc(x){return window.CSS&&CSS.escape?CSS.escape(x):x.replace(/([^\w-])/g,'\\$1');}
 function bad(x){return /\d{4,}/.test(x)||x.length>40;}
 function part(el){
  var s=el.tagName.toLowerCase();
  if(el.id&&!bad(el.id)) return s+'#'+esc(el.id);
  var cl=[].filter.call(el.classList,function(c){return !bad(c)}).slice(0,3);
  cl.forEach(function(c){s+='.'+esc(c)});
  var dt=el.getAttribute('data-testid'); if(dt&&!bad(dt)) s+='[data-testid="'+dt+'"]';
  return s;
 }
 function sel(el){
  var s=part(el),e=el,i=0;
  while(i<4&&document.querySelectorAll(s).length>1&&e.parentElement&&e.parentElement!==document.body){
   e=e.parentElement; s=part(e)+' > '+s; i++;
  }
  return s;
 }
 function show(el){
  cur=el; var r=el.getBoundingClientRect();
  box.style.display='block'; box.style.left=r.left+'px'; box.style.top=r.top+'px';
  box.style.width=r.width+'px'; box.style.height=r.height+'px';
 }
 function onTouch(e){
  var t=e.touches?e.touches[0]:e;
  var el=document.elementFromPoint(t.clientX,t.clientY);
  if(el&&el!==box) show(el);
  e.preventDefault(); e.stopPropagation();
 }
 function eat(e){e.preventDefault();e.stopPropagation();}
 document.addEventListener('touchstart',onTouch,{capture:true,passive:false});
 document.addEventListener('click',eat,true);
 window.__pickUp=function(){
  if(cur&&cur.parentElement&&cur.parentElement!==document.body&&cur.parentElement!==document.documentElement){show(cur.parentElement);}
 };
 window.__pickDone=function(ok){
  document.removeEventListener('touchstart',onTouch,{capture:true});
  document.removeEventListener('click',eat,true);
  box.remove(); window.__pick=0;
  if(ok&&cur) PochtaPicker.add(sel(cur));
 };
})();
"""
