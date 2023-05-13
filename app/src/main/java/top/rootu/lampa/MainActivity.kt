package top.rootu.lampa

import android.annotation.SuppressLint
import android.app.Activity
import android.content.*
import android.content.DialogInterface.BUTTON_POSITIVE
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Build.VERSION
import android.os.Bundle
import android.os.Parcelable
import android.speech.RecognizerIntent
import android.text.InputType
import android.util.Log
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.UiThread
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch
import net.gotev.speech.*
import net.gotev.speech.ui.SpeechProgressView
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.*
import org.mozilla.geckoview.GeckoSession.ContentDelegate.ContextElement
import top.rootu.lampa.helpers.Helpers
import top.rootu.lampa.helpers.PermHelpers.hasMicPermissions
import top.rootu.lampa.helpers.PermHelpers.verifyMicPermissions
import top.rootu.lampa.net.HttpHelper
import java.util.*
import java.util.regex.Pattern
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine


class MainActivity : AppCompatActivity() {
    private var browser: GeckoView? = null
    private var browserInit = false
    private var mDecorView: View? = null
    private var mProgressView: LinearProgressIndicator? = null
    private var mFullScreen = false
    private var mCanGoBack = false
    private var mCanGoForward = false
    private var mSettings: SharedPreferences? = null
    private lateinit var resultLauncher: ActivityResultLauncher<Intent?>
    private lateinit var speechLauncher: ActivityResultLauncher<Intent?>
    private lateinit var session: GeckoSession

    companion object {
        private const val LOGTAG = "APP_MAIN"
        const val RESULT_ERROR = 4
        const val APP_PREFERENCES = "settings"
        const val APP_URL = "url"
        const val APP_PLAYER = "player"
        const val APP_LANG = "lang"
        var LAMPA_URL: String? = ""
        var SELECTED_PLAYER: String? = ""
        var playerTimeCode: String = "continue"
        var playerFileView: JSONObject? = null
        var playerAutoNext: Boolean = true
        var internalTorrserve: Boolean = false
        var torrserverPreload: Boolean = false
        var playJSONArray: JSONArray = JSONArray()
        var playIndex = 0
        var playVideoUrl: String = ""
        private const val IP4_DIG = "([01]?\\d?\\d|2[0-4]\\d|25[0-5])"
        private const val IP4_REGEX = "(${IP4_DIG}\\.){3}${IP4_DIG}"
        private const val IP6_DIG = "[0-9A-Fa-f]{1,4}"
        private const val IP6_REGEX =
            "((${IP6_DIG}:){7}${IP6_DIG}|(${IP6_DIG}:){1,7}:|:(:${IP6_DIG}){1,7}|(${IP6_DIG}::?){1,6}${IP6_DIG})"
        private const val URL_REGEX =
            "^https?://(\\[${IP6_REGEX}]|${IP4_REGEX}|([-A-Za-z\\d]+\\.)+[-A-Za-z]{2,})(:\\d+)?(/.*)?$"
        private val URL_PATTERN = Pattern.compile(URL_REGEX)
        lateinit var runtime: GeckoRuntime

        @UiThread
        fun initialize(context: Context) {
            if (!this::runtime.isInitialized) {
                val runtimeSettings = GeckoRuntimeSettings.Builder()
                if (BuildConfig.DEBUG) {
                    runtimeSettings.remoteDebuggingEnabled(true)
                    runtimeSettings.consoleOutput(true)
                }
                runtimeSettings.apply {
                    aboutConfigEnabled(true)
                    javaScriptEnabled(true)
                    preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_DARK)
                }
                runtime = GeckoRuntime.create(context, runtimeSettings.build())
            }
        }

        suspend fun clearCache() {
            suspendCoroutine { cont ->
                runtime.storageController.clearData(StorageController.ClearFlags.ALL_CACHES).then({
                    cont.resume(null)
                    GeckoResult.fromValue(null)
                }, {
                    it.printStackTrace()
                    cont.resumeWithException(it)
                    GeckoResult.fromValue(null)
                })
            }
        }
    }

    private val onBackPressedCallback: OnBackPressedCallback =
        object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (BuildConfig.DEBUG) Log.d(
                    LOGTAG,
                    "handleOnBackPressed() FullScreen: $mFullScreen CanGoBack: $mCanGoBack"
                )
                if (mFullScreen) { //  && session != null
                    session.exitFullScreen()
                    return
                }
                if (mCanGoBack) { //  && session != null
                    session.goBack()
                    return
                }
            }
        }

    private val lampaNavigationDelegate: GeckoSession.NavigationDelegate =
        object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                mCanGoBack = canGoBack
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                mCanGoForward = canGoForward
            }
        }

    private val lampaProgressDelegate: GeckoSession.ProgressDelegate =
        object : GeckoSession.ProgressDelegate {
            override fun onPageStop(session: GeckoSession, success: Boolean) = Unit
            override fun onSecurityChange(
                session: GeckoSession,
                securityInfo: GeckoSession.ProgressDelegate.SecurityInformation
            ) = Unit

            override fun onPageStart(session: GeckoSession, url: String) = Unit
            override fun onProgressChange(session: GeckoSession, progress: Int) {
                if (BuildConfig.DEBUG) Log.d(LOGTAG, "onProgressChange $progress")
                mProgressView?.progress = progress
                if (progress in 1..99) {
                    mProgressView?.visibility = View.VISIBLE
                } else {
                    mProgressView?.visibility = View.GONE
                }
            }
        }

    private val lampaContentDelegate: GeckoSession.ContentDelegate =
        object : GeckoSession.ContentDelegate {
            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                if (BuildConfig.DEBUG) Log.d(LOGTAG, "onFullScreen: $fullScreen")
                window.setFlags(
                    if (fullScreen) WindowManager.LayoutParams.FLAG_FULLSCREEN else 0,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
                )
                mFullScreen = fullScreen
                if (fullScreen) {
                    supportActionBar?.hide()
                } else {
                    supportActionBar?.show()
                }
            }

            override fun onFocusRequest(session: GeckoSession) {
                if (BuildConfig.DEBUG) Log.d(LOGTAG, "Content requesting focus")
            }

            override fun onContextMenu(
                session: GeckoSession, screenX: Int, screenY: Int, element: ContextElement
            ) {
                if (BuildConfig.DEBUG) Log.d(
                    LOGTAG,
                    "onContextMenu screenX="
                            + screenX
                            + " screenY="
                            + screenY
                            + " type="
                            + element.type
                            + " linkUri="
                            + element.linkUri
                            + " title="
                            + element.title
                            + " alt="
                            + element.altText
                            + " srcUri="
                            + element.srcUri
                )
            }

            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                if (BuildConfig.DEBUG) Log.d(LOGTAG, "onExternalResponse: $response")
            }

            override fun onCrash(session: GeckoSession) {
                Log.e(LOGTAG, "Crashed, reopening session")
                session.open(runtime)
            }

            override fun onKill(session: GeckoSession) {
                if (BuildConfig.DEBUG) Log.d(LOGTAG, "onKill($session)")
//            val tabSession: TabSession = mTabSessionManager.getSession(session) ?: return
//            if (tabSession !== mTabSessionManager.getCurrentSession()) {
//                Log.e(LOGTAG, "Background session killed")
//                return
//            }
//            if (isForeground()) {
//                throw IllegalStateException("Foreground content process unexpectedly killed by OS!")
//            }
//            Log.e(LOGTAG, "Current session killed, reopening")
//            tabSession.open(runtime)
//            tabSession.loadUri(tabSession.getUri())
            }

            override fun onFirstComposite(session: GeckoSession) = Unit

            override fun onWebAppManifest(session: GeckoSession, manifest: JSONObject) {
                if (BuildConfig.DEBUG) Log.d(LOGTAG, "onWebAppManifest: $manifest")
                //TODO: sync theme etc
                val backColor = try {
                    Color.parseColor(manifest.getString("background_color")) // "theme_color"
                } catch (_: Exception) {
                    ContextCompat.getColor(baseContext, R.color.lampa_back) // 0xFF1D1F20.toInt()
                }
                browser?.coverUntilFirstPaint(backColor)
                val dm = when (manifest.getString("display_mode")) {
                    "fullscreen" -> GeckoSessionSettings.DISPLAY_MODE_FULLSCREEN
                    "standalone" -> GeckoSessionSettings.DISPLAY_MODE_STANDALONE
                    else -> {GeckoSessionSettings.DISPLAY_MODE_BROWSER}
                }
                session.settings.displayMode = dm
            }

            override fun onMetaViewportFitChange(session: GeckoSession, viewportFit: String) {
                if (VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    return
                }
                val layoutParams: WindowManager.LayoutParams = window.attributes
                if ((viewportFit == "cover")) {
                    layoutParams.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                } else if ((viewportFit == "contain")) {
                    layoutParams.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
                } else {
                    layoutParams.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
                window.attributes = layoutParams
            }

//        override fun onShowDynamicToolbar(session: GeckoSession) {
//            val toolbar: View = findViewById<View>(android.R.id.toolbar)
//            if (toolbar != null) {
//                toolbar.translationY = 0f
//                browser?.setVerticalClipping(0)
//            }
//        }

            override fun onCookieBannerDetected(session: GeckoSession) {
                Log.d("BELL", "A cookie banner was detected on this website")
            }

            override fun onCookieBannerHandled(session: GeckoSession) {
                Log.d("BELL", "A cookie banner was handled on this website")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        mDecorView = window.decorView
        hideSystemUI()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        mSettings = getSharedPreferences(APP_PREFERENCES, MODE_PRIVATE)
        LAMPA_URL = mSettings?.getString(APP_URL, LAMPA_URL)
        SELECTED_PLAYER = mSettings?.getString(APP_PLAYER, SELECTED_PLAYER)

        val lang = mSettings?.getString(APP_LANG, Locale.getDefault().language)
        Helpers.setLocale(this, lang)

        resultLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val data: Intent? = result.data
            val videoUrl: String = data?.data.toString()
            Log.i(LOGTAG, "Returned video url: $videoUrl")
            val resultCode = result.resultCode
            when (resultCode) { // just for debug
                RESULT_OK -> Log.i(LOGTAG, "OK: ${data?.toUri(0)}") // -1
                RESULT_CANCELED -> Log.i(LOGTAG, "Canceled: ${data?.toUri(0)}") // 0
                RESULT_FIRST_USER -> Log.i(LOGTAG, "FU: ${data?.toUri(0)}") // 1
                RESULT_ERROR -> Log.e(LOGTAG, "Error: ${data?.toUri(0)}") // 4
                else -> Log.w(LOGTAG, "Undefined result code ($resultCode): ${data?.toUri(0)}")
            }
            data?.let {
                if (it.action.equals("com.mxtech.intent.result.VIEW")) { // MX / Just
                    when (resultCode) {
                        RESULT_OK -> {
                            when (val endBy = it.getStringExtra("end_by")) {
                                "playback_completion" -> {
                                    Log.i(LOGTAG, "Playback completed")
                                    resultPlayer(videoUrl, 0, 0, true)
                                }

                                "user" -> {
                                    val pos = it.getIntExtra("position", 0)
                                    val dur = it.getIntExtra("duration", 0)
                                    if (pos > 0 && dur > 0) {
                                        Log.i(
                                            LOGTAG,
                                            "Playback stopped [position=$pos, duration=$dur]"
                                        )
                                        resultPlayer(videoUrl, pos, dur, false)
                                    } else {
                                        Log.e(
                                            LOGTAG,
                                            "Invalid state [position=$pos, duration=$dur]"
                                        )
                                    }
                                }

                                else -> {
                                    Log.e(LOGTAG, "Invalid state [endBy=$endBy]")
                                }
                            }
                        }

                        RESULT_CANCELED -> {
                            Log.i(LOGTAG, "Playback stopped by user")
                        }

                        RESULT_FIRST_USER -> {
                            Log.e(LOGTAG, "Playback stopped by unknown error")
                        }

                        else -> {
                            Log.e(LOGTAG, "Invalid state [resultCode=$resultCode]")
                        }
                    }
                } else if (it.action.equals("org.videolan.vlc.player.result")) { // VLC
                    when (resultCode) {
                        RESULT_OK -> {
                            val pos = it.getLongExtra("extra_position", 0L)
                            val dur = it.getLongExtra("extra_duration", 0L)
                            if (pos > 0L) {
                                Log.i(LOGTAG, "Playback stopped [position=$pos, duration=$dur]")
                                resultPlayer(videoUrl, pos.toInt(), dur.toInt(), false)
                            } else {
                                if (dur == 0L && pos == 0L) {
                                    Log.i(LOGTAG, "Playback completed")
                                    resultPlayer(videoUrl, 0, 0, true)
                                }
                            }
                        }

                        else -> {
                            Log.e(LOGTAG, "Invalid state [resultCode=$resultCode]")
                        }
                    }
                } else if (it.action.equals("is.xyz.mpv.MPVActivity.result")) { // MPV
                    when (resultCode) {
                        RESULT_OK -> {
                            val pos = it.getIntExtra("position", 0)
                            val dur = it.getIntExtra("duration", 0)
                            if (dur > 0) {
                                Log.i(LOGTAG, "Playback stopped [position=$pos, duration=$dur]")
                                resultPlayer(videoUrl, pos, dur, false)
                            } else if (dur == 0 && pos == 0) {
                                Log.i(LOGTAG, "Playback completed")
                                resultPlayer(videoUrl, 0, 0, true)
                            }
                        }

                        else -> {
                            Log.e(LOGTAG, "Invalid state [resultCode=$resultCode]")
                        }
                    }
                } else { // ViMu
                    when (resultCode) {
                        RESULT_FIRST_USER -> {
                            Log.i(LOGTAG, "Playback completed")
                            resultPlayer(videoUrl, 0, 0, true)
                        }

                        RESULT_CANCELED -> {
                            val pos = it.getIntExtra("position", 0)
                            val dur = it.getIntExtra("duration", 0)
                            if (pos > 0 && dur > 0) {
                                Log.i(LOGTAG, "Playback stopped [position=$pos, duration=$dur]")
                                resultPlayer(videoUrl, pos, dur, false)
                            }
                        }

                        RESULT_ERROR -> {
                            Log.e(LOGTAG, "Playback error")
                        }

                        else -> {
                            Log.e(LOGTAG, "Invalid state [resultCode=$resultCode]")
                        }
                    }
                }
            }
        }

        speechLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                // There are no request codes
                val data: Intent? = result.data
                val spokenText: String? =
                    data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.let { results ->
                        results[0]
                    }
                // Do something with spokenText.
                if (spokenText != null) {
//                    runVoidJsFunc("window.voiceResult", "'" + spokenText.replace("'", "\\'") + "'")
                }
            }
        }

        setContentView(R.layout.activity_main)

        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

        mProgressView = findViewById(R.id.progress)

        if (!browserInit) {
            initialize(this)

            val isTvBox = Helpers.isTvBox(this)
            val sessionSettings = GeckoSessionSettings.Builder()
            sessionSettings.apply {
                userAgentOverride("lampa_client")
                viewportMode(
                    if (isTvBox) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                    else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
                )
                userAgentMode(
                    if (isTvBox) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                    else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
                )
                displayMode(GeckoSessionSettings.DISPLAY_MODE_STANDALONE)
            }
            session = GeckoSession(sessionSettings.build())

            session.navigationDelegate = lampaNavigationDelegate
            session.progressDelegate = lampaProgressDelegate
            session.contentDelegate = lampaContentDelegate

            browserInit = true
            onGeckoInitCompleted()
        }
    }

    private fun onGeckoInitCompleted() {
        // Do anything with the embedding API
        if (browser == null) {
            browser = findViewById(R.id.geckoview)
            //browser?.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            //browser?.setViewBackend(GeckoView.BACKEND_SURFACE_VIEW)
            browser?.setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
            browser?.coverUntilFirstPaint(ContextCompat.getColor(baseContext, R.color.lampa_back))
            session.open(runtime)
            browser?.setSession(session)

            // hide loader
            //mProgressView?.visibility = View.GONE

//            browser?.setLayerType(View.LAYER_TYPE_NONE, null)
//
//            browser?.setResourceClient(object : XWalkResourceClient(browser) {
//                override fun onLoadFinished(view: XWalkView, url: String) {
//                    super.onLoadFinished(view, url)
//                    if (view.visibility != View.VISIBLE) {
//                        view.visibility = View.VISIBLE
//                        progressBar.visibility = View.GONE
//                        println("LAMPA onLoadFinished $url")
//                        runVoidJsFunc(
//                            "Lampa.Storage.listener.add",
//                            "'change'," +
//                                    "function(o){AndroidJS.StorageChange(JSON.stringify(o))}"
//                        )
//                        runJsStorageChangeField("player_timecode")
//                        runJsStorageChangeField("playlist_next")
//                        runJsStorageChangeField("torrserver_preload")
//                        runJsStorageChangeField("internal_torrclient")
//                        runJsStorageChangeField("language")
//                    }
//                }
//            })
        }

        //HttpHelper.userAgent = session.userAgent

        //browser?.setBackgroundColor(ContextCompat.getColor(baseContext, R.color.lampa_back))
        //browser?.addJavascriptInterface(AndroidJS(this, browser!!), "AndroidJS")

        if (LAMPA_URL.isNullOrEmpty()) {
            showUrlInputDialog()
        } else {
            //session.loadUri("about:buildconfig")
            session.loadUri(LAMPA_URL!!)
        }
    }

    private fun showUrlInputDialog() {
        val mainActivity = this
        val builder = AlertDialog.Builder(mainActivity)
        builder.setTitle(R.string.input_url_title)

        // Set up the input
        val input = EditText(this)
        input.setSingleLine()
        // Specify the type of input expected; this, for example, sets the input as a password,
        // and will mask the text
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.setText(if (LAMPA_URL.isNullOrEmpty()) "http://lampa.mx" else LAMPA_URL)
        val margin = resources.getDimensionPixelSize(R.dimen.dialog_margin)
        val container = FrameLayout(this)
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.leftMargin = margin
        params.rightMargin = margin
        input.layoutParams = params
        container.addView(input)
        builder.setView(container)

        // Set up the buttons
        builder.setPositiveButton(R.string.save) { _: DialogInterface?, _: Int ->
            LAMPA_URL = input.text.toString()
            if (URL_PATTERN.matcher(LAMPA_URL!!).matches()) {
                println("URL '$LAMPA_URL' is valid")
                if (mSettings?.getString(APP_URL, "") != LAMPA_URL) {
                    val editor = mSettings?.edit()
                    editor?.putString(APP_URL, LAMPA_URL)
                    editor?.apply()
                    session.loadUri(LAMPA_URL!!)
                    App.toast(R.string.change_url_press_back)
                }
            } else {
                println("URL '$LAMPA_URL' is invalid")
                App.toast(R.string.invalid_url)
                showUrlInputDialog()
            }
            hideSystemUI()
        }
        builder.setNegativeButton(R.string.cancel) { dialog: DialogInterface, _: Int ->
            dialog.cancel()
            if (LAMPA_URL.isNullOrEmpty() && mSettings?.getString(APP_URL, LAMPA_URL)
                    .isNullOrEmpty()
            ) {
                appExit()
            } else {
                LAMPA_URL = mSettings?.getString(APP_URL, LAMPA_URL)
                hideSystemUI()
            }
        }
        builder.setNeutralButton(R.string.exit) { dialog: DialogInterface, _: Int ->
            dialog.cancel()
            appExit()
        }
        builder.show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU
            || keyCode == KeyEvent.KEYCODE_TV_CONTENTS_MENU
            || keyCode == KeyEvent.KEYCODE_TV_MEDIA_CONTEXT_MENU
        ) {
            println("Menu key pressed")
            showUrlInputDialog()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            println("Back button long pressed")
            showUrlInputDialog()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        if (browserInit && browser != null) {
//            browser?.pauseTimers()
//            browser?.onHide()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (browserInit && browser != null) {
            browser?.session?.close()
        }
        try {
            Speech.getInstance()?.shutdown()
        } catch (_: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()

        if (browserInit && browser != null) {
//            browser?.resumeTimers()
//            browser?.onShow()
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUI() {
        mDecorView?.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LOW_PROFILE)
    }

    fun appExit() {
        lifecycleScope.launch {
            clearCache()
        }
        finishAffinity()
    }

    fun setLang(lang: String) {
        Helpers.setLocale(this, lang)
        val editor = mSettings?.edit()
        editor?.putString(APP_LANG, lang)
        editor?.apply()
    }

    private fun setPlayerPackage(packageName: String) {
        SELECTED_PLAYER = packageName.lowercase(Locale.getDefault())
        val editor = mSettings?.edit()
        editor?.putString(APP_PLAYER, SELECTED_PLAYER)
        editor?.apply()
    }

    private fun resultPlayer(
        endedVideoUrl: String,
        pos: Int = 0,
        dur: Int = 0,
        ended: Boolean = false
    ) {
        val videoUrl =
            if (endedVideoUrl == "" || endedVideoUrl == "null") playVideoUrl
            else endedVideoUrl
        if (videoUrl == "") return
        for (i in 0 until playJSONArray.length()) {
            val io = playJSONArray.getJSONObject(i)
            if (!io.has("timeline") || !io.has("url")) break
            val timeline = io.optJSONObject("timeline")
            val hash = timeline?.optString("hash", "0")
            when (videoUrl) {
                io.optString("url") -> {
                    val time: Int = if (ended) 0 else pos / 1000
                    val duration: Int =
                        if (ended) 0
                        else if (dur == 0 && timeline?.has("duration") == true)
                            timeline.optDouble("duration", 0.0).toInt()
                        else dur / 1000
                    val percent: Int = if (duration > 0) time * 100 / duration else 100
                    val newTimeline = JSONObject()
                    newTimeline.put("hash", hash)
                    newTimeline.put("time", time.toDouble())
                    newTimeline.put("duration", duration.toDouble())
                    newTimeline.put("percent", percent)
//                    runVoidJsFunc("Lampa.Timeline.update", newTimeline.toString())
                    break
                }
            }
            if (i >= playIndex) {
                val newTimeline = JSONObject()
                newTimeline.put("hash", hash)
                newTimeline.put("percent", 100)
                newTimeline.put("time", 0)
                newTimeline.put("duration", 0)
//                runVoidJsFunc("Lampa.Timeline.update", newTimeline.toString())
            }
        }
    }

    @SuppressLint("InflateParams")
    fun runPlayer(jsonObject: JSONObject) {
        val videoUrl = jsonObject.optString("url")
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndTypeAndNormalize(
            Uri.parse(videoUrl),
            if (videoUrl.endsWith(".m3u8")) "application/vnd.apple.mpegurl" else "video/*"
        )
        val resInfo =
            packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        if (resInfo.isEmpty()) {
            App.toast(R.string.no_activity_found, false)
            return
        }
        var playerPackageExist = false
        if (!SELECTED_PLAYER.isNullOrEmpty()) {
            for (info in resInfo) {
                if (info.activityInfo.packageName.lowercase(Locale.getDefault()) == SELECTED_PLAYER) {
                    playerPackageExist = true
                    break
                }
            }
        }
        if (!playerPackageExist || SELECTED_PLAYER.isNullOrEmpty()) {
            val excludedAppsPackageNames = hashSetOf(
                "com.android.gallery3d",
                "com.lonelycatgames.xplore",
                "com.android.tv.frameworkpackagestubs",
                "com.google.android.tv.frameworkpackagestubs",
                "com.instantbits.cast.webvideo",
                "com.ghisler.android.totalcommander",
                "com.google.android.apps.photos",
                "com.mixplorer.silver",
                "com.estrongs.android.pop",
                "pl.solidexplorer2"
            )
            val filteredList: MutableList<ResolveInfo> = mutableListOf()
            for (info in resInfo) {
                if (excludedAppsPackageNames.contains(info.activityInfo.packageName.lowercase(Locale.getDefault()))) {
                    continue
                }
                filteredList.add(info)
            }
            val mainActivity = this
            val listAdapter = AppListAdapter(mainActivity, filteredList)
            val playerChooser = AlertDialog.Builder(mainActivity)
            val appTitleView =
                LayoutInflater.from(mainActivity).inflate(R.layout.app_list_title, null)
            val switch = appTitleView.findViewById<SwitchCompat>(R.id.useDefault)
            playerChooser.setCustomTitle(appTitleView)

            playerChooser.setAdapter(listAdapter) { dialog, which ->
                val setDefaultPlayer = switch.isChecked
                SELECTED_PLAYER = listAdapter.getItemPackage(which)
                if (setDefaultPlayer) setPlayerPackage(SELECTED_PLAYER.toString())
                dialog.dismiss()
                runPlayer(jsonObject)
                if (!setDefaultPlayer) SELECTED_PLAYER = ""
            }
            val playerChooserDialog = playerChooser.create()
            playerChooserDialog.show()
            playerChooserDialog.listView.requestFocus()
        } else {
            var videoPosition: Long = 0
            val videoTitle =
                if (jsonObject.has("title")) jsonObject.optString("title") else "LAMPA video"
            val listTitles = ArrayList<String>()
            val listUrls = ArrayList<String>()
            val subsTitles = ArrayList<String>()
            val subsUrls = ArrayList<String>()
            val headers = ArrayList<String>()
            playIndex = -1

            if (playerTimeCode == "continue" && jsonObject.has("timeline")) {
                val timeline = jsonObject.optJSONObject("timeline")
                if (timeline?.has("time") == true) {
                    val hash = timeline.optString("hash", "0")
                    val latestTimeline =
                        if (playerFileView?.has(hash) == true) playerFileView?.optJSONObject(hash) else null
                    videoPosition = if (latestTimeline?.has("time") == true)
                        (latestTimeline.optDouble("time", 0.0) * 1000).toLong()
                    else
                        (timeline.optDouble("time", 0.0) * 1000).toLong()
                }
            }

            var ua = HttpHelper.userAgent
            if (jsonObject.has("headers")) {
                val headersJSON = jsonObject.optJSONObject("headers")
                if (headersJSON != null) {
                    val keys = headersJSON.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = headersJSON.optString(key)
                        when (key.lowercase(Locale.getDefault())) {
                            "user-agent" -> ua = value
                            "content-length" -> {}
                            else -> {
                                headers.add(key)
                                headers.add(value)
                            }
                        }
                    }
                }
            }
            headers.add("User-Agent")
            headers.add(ua)

            if (jsonObject.has("playlist") && playerAutoNext) {
                playJSONArray = jsonObject.getJSONArray("playlist")
                val badLinkPattern = "(/stream/.*?\\?link=.*?&index=\\d+)&preload\$".toRegex()
                for (i in 0 until playJSONArray.length()) {
                    val io = playJSONArray.getJSONObject(i)
                    if (io.has("url")) {
                        val url = if (torrserverPreload && internalTorrserve)
                            io.optString("url").replace(badLinkPattern, "$1&play")
                        else
                            io.optString("url")
                        if (url != io.optString("url")) {
                            io.put("url", url)
                            playJSONArray.put(i, io)
                        }
                        if (url == videoUrl)
                            playIndex = i
                        listUrls.add(io.optString("url"))
                        listTitles.add(
                            if (io.has("title")) io.optString("title") else (i + 1).toString()
                        )
                    }
                }
            }
            if (jsonObject.has("subtitles")) {
                val subsJSONArray = jsonObject.optJSONArray("subtitles")
                if (subsJSONArray != null)
                    for (i in 0 until subsJSONArray.length()) {
                        val io = subsJSONArray.getJSONObject(i)
                        if (io.has("url")) {
                            subsUrls.add(io.optString("url"))
                            subsTitles.add(io.optString("label", "Sub " + (i + 1).toString()))
                        }
                    }
            }
            if (playIndex < 0) {
                // current url not found in playlist or playlist missing
                playIndex = 0
                playJSONArray = JSONArray()
                playJSONArray.put(jsonObject)
            }
            playVideoUrl = videoUrl
            when (SELECTED_PLAYER) {
                "com.mxtech.videoplayer.pro", "com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.beta" -> {
                    //intent.setPackage(SELECTED_PLAYER)
                    intent.component = ComponentName(
                        SELECTED_PLAYER!!,
                        "$SELECTED_PLAYER.ActivityScreen"
                    )
                    intent.putExtra("title", videoTitle)
                    intent.putExtra("sticky", false)
                    intent.putExtra("headers", headers.toTypedArray())
                    if (playerTimeCode == "continue" && videoPosition > 0L) {
                        intent.putExtra("position", videoPosition.toInt())
                    } else if (playerTimeCode == "again"
                        || (playerTimeCode == "continue" && videoPosition == 0L)
                    ) {
                        intent.putExtra("position", 1)
                    }
                    if (listUrls.size > 1) {
                        val parcelableVideoArr = arrayOfNulls<Parcelable>(listUrls.size)
                        for (i in 0 until listUrls.size) {
                            parcelableVideoArr[i] = Uri.parse(listUrls[i])
                        }
                        intent.putExtra("video_list", parcelableVideoArr)
                        intent.putExtra("video_list.name", listTitles.toTypedArray())
                        intent.putExtra("video_list_is_explicit", true)
                    }
                    if (subsUrls.size > 0) {
                        val parcelableSubsArr = arrayOfNulls<Parcelable>(subsUrls.size)
                        for (i in 0 until subsUrls.size) {
                            parcelableSubsArr[i] = Uri.parse(subsUrls[i])
                        }
                        intent.putExtra("subs", parcelableSubsArr)
                        intent.putExtra("subs.name", subsTitles.toTypedArray())
                    }
                    intent.putExtra("return_result", true)
                }

                "is.xyz.mpv" -> {
                    // http://mpv-android.github.io/mpv-android/intent.html
                    intent.setPackage(SELECTED_PLAYER)
                    if (subsUrls.size > 0) {
                        val parcelableSubsArr = arrayOfNulls<Parcelable>(subsUrls.size)
                        for (i in 0 until subsUrls.size) {
                            parcelableSubsArr[i] = Uri.parse(subsUrls[i])
                        }
                        intent.putExtra("subs", parcelableSubsArr)
                    }
                    if (playerTimeCode == "continue" && videoPosition > 0L) {
                        intent.putExtra("position", videoPosition.toInt())
                    } else if (playerTimeCode == "again"
                        || (playerTimeCode == "continue" && videoPosition == 0L)
                    ) {
                        intent.putExtra("position", 1)
                    }
                }

                "org.videolan.vlc" -> {
                    // https://wiki.videolan.org/Android_Player_Intents
                    intent.component = ComponentName(
                        SELECTED_PLAYER!!,
                        "$SELECTED_PLAYER.gui.video.VideoPlayerActivity"
                    ) // required for return intent
                    intent.putExtra("title", videoTitle)
                    if (playerTimeCode == "continue" && videoPosition > 0L) {
                        intent.putExtra("from_start", false)
                        intent.putExtra("position", videoPosition)
                    } else if (playerTimeCode == "again"
                        || (playerTimeCode == "continue" && videoPosition == 0L)
                    ) {
                        intent.putExtra("from_start", true)
                        intent.putExtra("position", 0L)
                    }
                }

                "com.brouken.player" -> {
                    intent.setPackage(SELECTED_PLAYER)
                    intent.putExtra("title", videoTitle)
                    intent.putExtra("return_result", true)
                    if (playerTimeCode == "continue" || playerTimeCode == "again")
                        intent.putExtra("position", videoPosition.toInt())
                }

                "net.gtvbox.videoplayer", "net.gtvbox.vimuhd" -> {
                    intent.setPackage(SELECTED_PLAYER)
                    // see https://vimu.tv/player-api
                    if (listUrls.size <= 1) {
                        intent.putExtra("forcename", videoTitle)
                    } else {
                        intent.setDataAndType(
                            Uri.parse(videoUrl),
                            "application/vnd.gtvbox.filelist"
                        )
                        intent.putStringArrayListExtra(
                            "asusfilelist",
                            ArrayList(listUrls.subList(playIndex, listUrls.size))
                        )
                        intent.putStringArrayListExtra(
                            "asusnamelist",
                            ArrayList(listTitles.subList(playIndex, listUrls.size))
                        )
                    }
                    if (playerTimeCode == "continue" || playerTimeCode == "again") {
                        intent.putExtra("position", videoPosition.toInt())
                        intent.putExtra("startfrom", videoPosition.toInt())
                    }
                    intent.putExtra("forcedirect", true)
                    intent.putExtra("forceresume", true)
                }

                else -> {
                    intent.setPackage(SELECTED_PLAYER)
                }
            }
            try {
                intent.flags = 0 // https://stackoverflow.com/a/47694122
                resultLauncher.launch(intent)
            } catch (e: Exception) {
                App.toast(R.string.no_launch_player, false)
            }
        }
    }

    fun displaySpeechRecognizer() {
        if (VERSION.SDK_INT < 18) {
            // Create an intent that can start the Speech Recognizer activity
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
            }
            // This starts the activity and populates the intent with the speech text.
            try {
                speechLauncher.launch(intent)
            } catch (e: Exception) {
                App.toast(R.string.not_found_speech, false)
            }
        } else {
            // Verify permissions
            verifyMicPermissions(this)

            var dialog: AlertDialog? = null
            val view = layoutInflater.inflate(R.layout.dialog_search, null, false)
            val etSearch = view.findViewById<AppCompatEditText?>(R.id.etSearchQuery)
            val btnVoice = view.findViewById<AppCompatImageButton?>(R.id.btnVoiceSearch)
            val inputManager =
                getSystemService(Activity.INPUT_METHOD_SERVICE) as? InputMethodManager

            etSearch?.apply {
                setOnClickListener {
                    inputManager?.showSoftInput(this, 0) // SHOW_IMPLICIT
                }
                imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            }?.setOnEditorActionListener(TextView.OnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                    dialog?.getButton(BUTTON_POSITIVE)?.performClick()
                    return@OnEditorActionListener true
                }
                false
            })

            btnVoice?.apply {
                val context = this.context
                if (!hasMicPermissions(context)) {
                    this.isEnabled = false
                    etSearch?.requestFocus()
                } else {
                    this.isEnabled = true
                }
                setOnClickListener {
                    val dots = view.findViewById<LinearLayout>(R.id.searchDots)
                    val progress = view.findViewById<SpeechProgressView>(R.id.speechProgress)
                    val heights = intArrayOf(40, 56, 38, 55, 35)
                    progress.setBarMaxHeightsInDp(heights)
                    if (hasMicPermissions(context)) {
                        etSearch?.hint = context.getString(R.string.search_voice_hint)
                        btnVoice.visibility = View.GONE
                        dots?.visibility = View.VISIBLE
                    } else {
                        App.toast(R.string.search_requires_record_audio)
                        btnVoice.visibility = View.VISIBLE
                        dots?.visibility = View.GONE
                        etSearch?.hint = context.getString(R.string.search_is_empty)
                        etSearch?.requestFocus()
                    }
                    // start Speech
                    startSpeech(
                        getString(R.string.search_voice_hint),
                        progress
                    ) { result, final, success ->
                        etSearch?.hint = ""
                        etSearch?.setText(result)
                        if (final) {
                            btnVoice.visibility = View.VISIBLE
                            dots?.visibility = View.GONE
                        }
                        if (final && success) {
                            dialog?.getButton(BUTTON_POSITIVE)?.requestFocus() //.performClick()
                        }
                    }
                }
            }

            dialog = AlertDialog.Builder(this)
                .setView(view)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    dialog?.dismiss()
                    val query = etSearch.text.toString()
                    if (query.isNotEmpty()) {
//                        runVoidJsFunc("window.voiceResult", "'" + query.replace("'", "\\'") + "'")
                    } else { // notify user
                        App.toast(R.string.search_is_empty)
                    }
                }
                .create()

            // top position (no keyboard overlap)
            val lp = dialog.window?.attributes
            lp?.apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                verticalMargin = 0.1F
            }
            // show fullscreen dialog
            dialog.window?.setFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            )
            // set fullscreen mode (immersive sticky):
            @Suppress("DEPRECATION")
            var uiFlags: Int = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
            if (VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                @Suppress("DEPRECATION")
                uiFlags = uiFlags or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }
            @Suppress("DEPRECATION")
            dialog.window?.decorView?.systemUiVisibility = uiFlags
            dialog.show()
            dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)

            // run voice search
            btnVoice?.performClick()
        }
    }

    private fun startSpeech(
        msg: String,
        progress: SpeechProgressView,
        onSpeech: (result: String, final: Boolean, success: Boolean) -> Unit
    ): Boolean {
        val context = this.baseContext
        if (hasMicPermissions(context)) {
            try {
                // you must have android.permission.RECORD_AUDIO granted at this point
                Speech.init(context, packageName)
                    ?.startListening(progress, object : SpeechDelegate {
                        private var success = true
                        override fun onStartOfSpeech() {
                            Log.i("speech", "speech recognition is now active. $msg")
                        }

                        override fun onSpeechRmsChanged(value: Float) {
                            //Log.d("speech", "rms is now: $value")
                        }

                        override fun onSpeechPartialResults(results: List<String>) {
                            val str = StringBuilder()
                            for (res in results) {
                                str.append(res).append(" ")
                            }
                            Log.i("speech", "partial result: " + str.toString().trim { it <= ' ' })
                            onSpeech(str.toString().trim { it <= ' ' }, false, success)
                        }

                        override fun onSpeechResult(res: String) {
                            Log.i("speech", "result: $res")
                            if (res.isEmpty())
                                success = false
                            onSpeech(res, true, success)
                        }
                    })
                return true
            } catch (exc: SpeechRecognitionNotAvailable) {
                Log.e("speech", "Speech recognition is not available on this device!")
                App.toast(R.string.search_no_voice_recognizer)
                // You can prompt the user if he wants to install Google App to have
                // speech recognition, and then you can simply call:
                SpeechUtil.redirectUserToGoogleAppOnPlayStore(context)
                // to redirect the user to the Google App page on Play Store
            } catch (exc: GoogleVoiceTypingDisabledException) {
                Log.e("speech", "Google voice typing must be enabled!")
            }
        }
        return false
    }

//    fun runJsStorageChangeField(name: String) {
//        runVoidJsFunc(
//            "AndroidJS.StorageChange",
//            "JSON.stringify({" +
//                    "name: '${name}'," +
//                    "value: Lampa.Storage.field('${name}')" +
//                    "})"
//        )
//    }

//    fun runVoidJsFunc(funcName: String, params: String) {
//        val js = ("(function(){"
//                + "try {"
//                + funcName + "(" + params + ");"
//                + "return 'ok';"
//                + "} catch (e) {"
//                + "return 'error: ' + e.message;"
//                + "}"
//                + "})();")
//        browser?.evaluateJavascript(
//            js
//        ) { r: String ->
//            Log.i(
//                "runVoidJsFunc",
//                "$funcName($params) $r"
//            )
//        }
//    }
}