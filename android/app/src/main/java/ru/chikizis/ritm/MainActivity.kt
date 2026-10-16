package ru.chikizis.ritm

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    lateinit var web: WebView
    private lateinit var root: FrameLayout
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingSave: String? = null

    private val loader by lazy {
        WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
    }

    private val pickFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val cb = fileCallback; fileCallback = null
        if (cb == null) return@registerForActivityResult
        if (res.resultCode != Activity.RESULT_OK || res.data == null) { cb.onReceiveValue(null); return@registerForActivityResult }
        val d = res.data!!
        val uris = mutableListOf<Uri>()
        d.clipData?.let { c: ClipData -> for (i in 0 until c.itemCount) uris += c.getItemAt(i).uri }
        if (uris.isEmpty()) d.data?.let { uris += it }
        cb.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
    }

    private val saveDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val data = pendingSave; pendingSave = null
        var ok = false
        if (uri != null && data != null) {
            ok = try { contentResolver.openOutputStream(uri)?.use { it.write(data.toByteArray()) }; true } catch (e: Exception) { false }
        }
        js("window.__ritmSaved&&__ritmSaved(${if (ok) "true" else "false"})")
    }

    private fun soundPicked(r: Pair<String, String>?) {
        if (r == null) { js("window.__ritmSoundPicked&&__ritmSoundPicked(null,'file')"); return }
        js("window.__ritmSoundPicked&&__ritmSoundPicked(${JSONObject.quote(r.first)},${JSONObject.quote(r.second)})")
    }

    private val pickRingtone = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        @Suppress("DEPRECATION")
        val uri: Uri? = res.data?.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        if (res.resultCode != Activity.RESULT_OK || uri == null) return@registerForActivityResult
        Thread {
            val ok = Sounds.playable(this, uri)
            runOnUiThread {
                if (ok) soundPicked(Sounds.ringtone(this, uri))
                else js("window.__ritmSoundPicked&&__ritmSoundPicked(null,'ringtone')")
            }
        }.start()
    }

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        Thread {
            val r = Sounds.importFile(this, uri)
            runOnUiThread { soundPicked(r) }
        }.start()
    }

    fun pickSound(kind: String) = runOnUiThread {
        try {
            if (kind == "file") pickAudio.launch(arrayOf("audio/*"))
            else pickRingtone.launch(Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE,
                    android.media.RingtoneManager.TYPE_ALARM or android.media.RingtoneManager.TYPE_RINGTONE)
                putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, "Мелодия будильника")
                putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            })
        } catch (e: Exception) { soundPicked(null) }
    }

    override fun onPause() {
        super.onPause()
        Sounds.stopPreview()
    }

    val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        js("window.__ritmResume&&__ritmResume()")
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifs.ensureChannels(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        root = FrameLayout(this)
        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION") databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = true
            textZoom = 100
            setSupportZoom(false)
        }
        web.setBackgroundColor(Color.TRANSPARENT)
        web.addJavascriptInterface(Bridge(this), "RitmNative")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == "appassets.androidplatform.net") return false
                try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = cb
                val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*", "application/pdf", "text/*", "application/json"))
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                }
                return try { pickFiles.launch(Intent.createChooser(i, "Выбери файл")); true }
                catch (e: Exception) { fileCallback = null; false }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.__ritmBack?String(__ritmBack()):'false'") { r ->
                    if (r?.contains("true") != true) moveTaskToBack(true)
                }
            }
        })

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl(startUrl(intent))

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun startUrl(i: Intent?): String {
        val q = mutableListOf<String>()
        i?.getStringExtra("tab")?.let { q += "tab=" + Uri.encode(it) }
        i?.getStringExtra("add")?.let { q += "add=" + Uri.encode(it) }
        return "https://appassets.androidplatform.net/assets/www/index.html" + (if (q.isEmpty()) "" else "?" + q.joinToString("&"))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tab = intent.getStringExtra("tab") ?: return
        val add = intent.getStringExtra("add") ?: ""
        js("window.__ritmOpen&&__ritmOpen(${JSONObject.quote(tab)},${JSONObject.quote(add)})")
    }

    override fun onResume() {
        super.onResume()
        js("window.__ritmResume&&__ritmResume()")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    fun js(code: String) = runOnUiThread { if (::web.isInitialized) web.evaluateJavascript(code, null) }

    fun saveFile(name: String, data: String) = runOnUiThread {
        pendingSave = data
        try { saveDoc.launch(name) } catch (e: Exception) { pendingSave = null; js("window.__ritmSaved&&__ritmSaved(false)") }
    }

    fun keepScreenOn(on: Boolean) = runOnUiThread {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun setBars(color: Int, dark: Boolean) = runOnUiThread {
        root.setBackgroundColor(color)
        window.decorView.setBackgroundColor(color)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}
