package com.example.tabbrowser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.ScriptHandler
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** Cada modo define User-Agent, Client Hints, ancho de viewport y señales JS que ve la web. */
enum class Mode(val label: String, val viewportWidth: Int?) {
    MOBILE("Móvil", null),
    TABLET("Tablet", 800),
    DESKTOP("Escritorio", 1280)
}

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var modeBtn: Button

    private val homeUrl = "https://duckduckgo.com"
    private var mode = Mode.DESKTOP
    private var scriptHandler: ScriptHandler? = null

    private val baseUa: String by lazy {
        WebSettings.getDefaultUserAgent(this)
            .replace("; wv", "")
            .replace("Version/4.0 ", "")
    }

    private val chromeFull: String by lazy {
        Regex("Chrome/([\\d.]+)").find(baseUa)?.groupValues?.get(1) ?: "120.0.0.0"
    }
    private val chromeMajor: String get() = chromeFull.substringBefore(".")

    private fun userAgentFor(m: Mode): String = when (m) {
        Mode.MOBILE -> baseUa
        Mode.TABLET -> baseUa.replace(" Mobile", "")
        Mode.DESKTOP ->
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/$chromeFull Safari/537.36"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        urlBar = findViewById(R.id.urlBar)
        progress = findViewById(R.id.progress)
        modeBtn = findViewById(R.id.modeBtn)

        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        mode = Mode.values().getOrElse(prefs.getInt("mode", Mode.DESKTOP.ordinal)) { Mode.DESKTOP }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
                if (!urlBar.hasFocus()) urlBar.setText(url)
            }
            override fun onPageCommitVisible(view: WebView, url: String) = forceViewport()
            override fun onPageFinished(view: WebView, url: String) {
                progress.visibility = View.INVISIBLE
                forceViewport()
                if (!urlBar.hasFocus()) urlBar.setText(url)
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
            }
        }

        urlBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                load(urlBar.text.toString())
                urlBar.clearFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(urlBar.windowToken, 0)
                true
            } else false
        }

        findViewById<Button>(R.id.backBtn).setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        findViewById<Button>(R.id.forwardBtn).setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        findViewById<Button>(R.id.reloadBtn).setOnClickListener { webView.reload() }
        findViewById<Button>(R.id.homeBtn).setOnClickListener { load(homeUrl) }
        modeBtn.setOnClickListener { showModeMenu() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        applyMode(reload = false)
        load(intent?.data?.toString() ?: homeUrl)
    }

    private fun showModeMenu() {
        val menu = PopupMenu(this, modeBtn)
        Mode.values().forEach { menu.menu.add(0, it.ordinal, it.ordinal, it.label) }
        menu.setOnMenuItemClickListener { item ->
            mode = Mode.values()[item.itemId]
            getSharedPreferences("prefs", MODE_PRIVATE).edit().putInt("mode", mode.ordinal).apply()
            applyMode(reload = true)
            true
        }
        menu.show()
    }

    private fun applyMode(reload: Boolean) {
        val s = webView.settings
        s.userAgentString = userAgentFor(mode)
        s.loadWithOverviewMode = mode != Mode.MOBILE
        modeBtn.text = mode.label

        applyClientHints()
        installSpoofScript()

        if (reload) webView.reload()
    }

    /** Client Hints (Sec-CH-UA-*, navigator.userAgentData) coherentes con el modo. */
    private fun applyClientHints() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
        val brands = listOf(
            UserAgentMetadata.BrandVersion.Builder()
                .setBrand("Chromium").setMajorVersion(chromeMajor).setFullVersion(chromeFull).build(),
            UserAgentMetadata.BrandVersion.Builder()
                .setBrand("Google Chrome").setMajorVersion(chromeMajor).setFullVersion(chromeFull).build()
        )
        val b = UserAgentMetadata.Builder()
            .setBrandVersionList(brands)
            .setFullVersion(chromeFull)
            .setModel("")
        when (mode) {
            Mode.DESKTOP -> b.setMobile(false).setPlatform("Windows").setPlatformVersion("10.0.0")
                .setArchitecture("x86").setBitness(64)
            Mode.TABLET -> b.setMobile(false).setPlatform("Android").setPlatformVersion("13.0.0")
            Mode.MOBILE -> b.setMobile(true).setPlatform("Android").setPlatformVersion("13.0.0")
        }
        WebSettingsCompat.setUserAgentMetadata(webView.settings, b.build())
    }

    /** Script que corre ANTES que el JS de la página y disfraza señales de "móvil". */
    private fun installSpoofScript() {
        scriptHandler?.remove()
        scriptHandler = null
        if (mode == Mode.MOBILE) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        scriptHandler = WebViewCompat.addDocumentStartJavaScript(webView, spoofScript(), setOf("*"))
    }

    private fun spoofScript(): String {
        val desktop = mode == Mode.DESKTOP
        val w = mode.viewportWidth ?: 1280
        val h = w * 9 / 16
        return """
        (function(){
          try {
            var DESKTOP = $desktop;
            var def = function(o,p,v){ try{ Object.defineProperty(o,p,{get:function(){return v},configurable:true}); }catch(e){} };
            var nav = Navigator.prototype;
            var brands = [{brand:'Chromium',version:'$chromeMajor'},{brand:'Google Chrome',version:'$chromeMajor'}];
            var plat = DESKTOP ? 'Windows' : 'Android';
            var uad = {
              brands: brands, mobile: false, platform: plat,
              getHighEntropyValues: function(){ return Promise.resolve({
                brands: brands, mobile:false, platform: plat,
                platformVersion: DESKTOP ? '10.0.0' : '13.0.0',
                architecture: DESKTOP ? 'x86' : '', bitness: DESKTOP ? '64' : '',
                model:'', uaFullVersion:'$chromeFull'
              }); },
              toJSON: function(){ return {brands:brands, mobile:false, platform:plat}; }
            };
            def(nav,'userAgentData',uad);

            if (DESKTOP) {
              def(nav,'platform','Win32');
              def(nav,'maxTouchPoints',0);
              try{ delete window.ontouchstart; delete window.ontouchend; delete window.TouchEvent; }catch(e){}
              def(Screen.prototype,'width',$w);
              def(Screen.prototype,'height',$h);
              def(Screen.prototype,'availWidth',$w);
              def(Screen.prototype,'availHeight',$h);
              var mm = window.matchMedia.bind(window);
              window.matchMedia = function(q){
                var s = String(q)
                  .replace(/\(\s*(any-)?pointer\s*:\s*(coarse|none)\s*\)/g,'(min-width:99999px)')
                  .replace(/\(\s*(any-)?hover\s*:\s*none\s*\)/g,'(min-width:99999px)')
                  .replace(/\(\s*(any-)?pointer\s*:\s*fine\s*\)/g,'(min-width:0px)')
                  .replace(/\(\s*(any-)?hover\s*:\s*hover\s*\)/g,'(min-width:0px)');
                return mm(s);
              };
            }
          } catch(e) {}
        })();
        """.trimIndent()
    }

    /** Fuerza el ancho de viewport del modo elegido. */
    private fun forceViewport() {
        val w = mode.viewportWidth ?: return
        webView.evaluateJavascript(
            """
            (function(){
              var m=document.querySelector('meta[name=viewport]');
              if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}
              m.content='width=$w, maximum-scale=5, user-scalable=yes';
            })();
            """.trimIndent(), null
        )
    }

    private fun load(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        val url = when {
            text.startsWith("http://") || text.startsWith("https://") -> text
            !text.contains(" ") && text.contains(".") -> "https://$text"
            else -> "https://duckduckgo.com/?q=" + Uri.encode(text)
        }
        webView.loadUrl(url)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
