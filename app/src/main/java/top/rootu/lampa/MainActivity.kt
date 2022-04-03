package top.rootu.lampa

import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import org.json.JSONObject
import org.xwalk.core.*
import org.xwalk.core.XWalkInitializer.XWalkInitListener
import org.xwalk.core.XWalkUpdater.XWalkUpdateListener
import java.lang.reflect.Array
import java.util.*
import java.util.regex.Pattern
import kotlin.collections.ArrayList
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity(), XWalkInitListener, XWalkUpdateListener {
    private var browser: XWalkView? = null
    private var mXWalkInitializer: XWalkInitializer? = null
    private var mXWalkUpdater: XWalkUpdater? = null
    private var mDecorView: View? = null
    private var browserInit = false
    var mSettings: SharedPreferences? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        mDecorView = window.decorView
        hideSystemUI()
        mSettings = getSharedPreferences(APP_PREFERENCES, MODE_PRIVATE)
        LAMPA_URL = mSettings?.getString(APP_URL, LAMPA_URL)
        SELECTED_PLAYER = mSettings?.getString(APP_PLAYER, SELECTED_PLAYER)

        // Must call initAsync() before anything that involves the embedding
        // API, including invoking setContentView() with the layout which
        // holds the XWalkView object.
        mXWalkInitializer = XWalkInitializer(this, this)
        mXWalkInitializer!!.initAsync()

        // Until onXWalkInitCompleted() is invoked, you should do nothing with the
        // embedding API except the following:
        // 1. Instantiate the XWalkView object
        // 2. Call XWalkPreferences.setValue()
        // 3. Call mXWalkView.setXXClient(), e.g., setUIClient
        // 4. Call mXWalkView.setXXListener(), e.g., setDownloadListener
        // 5. Call mXWalkView.addJavascriptInterface()
        XWalkPreferences.setValue(XWalkPreferences.REMOTE_DEBUGGING, true)
        XWalkPreferences.setValue(XWalkPreferences.ENABLE_JAVASCRIPT, true)
        // maybe this fixes crashes on mitv2?
        // XWalkPreferences.setValue(XWalkPreferences.ANIMATABLE_XWALK_VIEW, true);
        setContentView(R.layout.activity_main)
    }

    override fun onXWalkInitStarted() {}
    override fun onXWalkInitCancelled() {
        // Perform error handling here
        finish()
    }

    override fun onXWalkInitFailed() {
        if (mXWalkUpdater == null) {
            mXWalkUpdater = XWalkUpdater(this, this)
        }
        mXWalkUpdater!!.updateXWalkRuntime()
    }

    override fun onXWalkInitCompleted() {
        // Do anyting with the embedding API
        browserInit = true
        if (browser == null) {
            browser = findViewById(R.id.xwalkview)
            browser?.setLayerType(View.LAYER_TYPE_NONE, null)
            val progressBar = findViewById<ProgressBar>(R.id.progressBar_cyclic)
            browser?.setResourceClient(object : XWalkResourceClient(browser) {
                override fun onLoadFinished(view: XWalkView, url: String) {
                    super.onLoadFinished(view, url)
                    if (view.visibility != View.VISIBLE) {
                        view.visibility = View.VISIBLE
                        progressBar.visibility = View.GONE
                        println("LAMPA onLoadFinished $url")
                    }
                }
            })
        }
        val ua = browser?.userAgentString + " LAMPA_ClientForLegacyOS"
        browser?.userAgentString = ua
        browser?.setBackgroundColor(resources.getColor(R.color.lampa_background))
        browser?.addJavascriptInterface(AndroidJS(this, browser!!), "AndroidJS")
        if (LAMPA_URL.isNullOrEmpty()) {
            showUrlInputDialog()
        } else {
            browser?.loadUrl(LAMPA_URL)
        }
    }

    private fun showUrlInputDialog() {
        val mainActivity = this
        val builder = AlertDialog.Builder(mainActivity)
        builder.setTitle(R.string.input_url_title)

        // Set up the input
        val input = EditText(this)
        // Specify the type of input expected; this, for example, sets the input as a password,
        // and will mask the text
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.setText(if (LAMPA_URL.isNullOrEmpty()) "http://" else LAMPA_URL)
        builder.setView(input)

        // Set up the buttons
        builder.setPositiveButton(R.string.save) { dialog: DialogInterface?, which: Int ->
            LAMPA_URL = input.text.toString()
            if (URL_PATTERN.matcher(LAMPA_URL).matches()) {
                println("URL '$LAMPA_URL' is valid")
                if (mSettings?.getString(APP_URL, "") != LAMPA_URL) {
                    val editor = mSettings?.edit()
                    editor?.putString(APP_URL, LAMPA_URL)
                    editor?.apply()
                    browser?.loadUrl(LAMPA_URL)
                    Toast.makeText(mainActivity, R.string.change_url_press_back, Toast.LENGTH_LONG)
                        .show()
                }
            } else {
                println("URL '$LAMPA_URL' is invalid")
                Toast.makeText(mainActivity, R.string.invalid_url, Toast.LENGTH_LONG).show()
                showUrlInputDialog()
            }
            hideSystemUI()
        }
        builder.setNegativeButton(R.string.cancel) { dialog: DialogInterface, which: Int ->
            dialog.cancel()
            if (LAMPA_URL!!.isEmpty() && mSettings!!.getString(APP_URL, LAMPA_URL)!!
                    .isEmpty()
            ) {
                appExit()
            } else {
                LAMPA_URL = mSettings!!.getString(APP_URL, LAMPA_URL)
                hideSystemUI()
            }
        }
        builder.setNeutralButton(R.string.exit) { dialog: DialogInterface, which: Int ->
            dialog.cancel()
            appExit()
        }
        builder.show()
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            println("Back button long pressed")
            showUrlInputDialog()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onXWalkUpdateCancelled() {
        // Perform error handling here
        finish()
    }

    override fun onPause() {
        super.onPause()
        if (browserInit && browser != null) {
            browser?.pauseTimers()
            //            browser.onHide();
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (browserInit && browser != null) {
            browser?.onDestroy()
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()

        // Try to initialize again when the user completed updating and
        // returned to current activity. The initAsync() will do nothing if
        // the initialization is proceeding or has already been completed.
        mXWalkInitializer?.initAsync()
        if (browserInit && browser != null) {
            browser?.resumeTimers()
            //            browser.onShow();
        }
    }

    private fun hideSystemUI() {
        mDecorView?.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LOW_PROFILE)
    }

    fun appExit() {
        browser?.let {
            it.clearCache(true)
            it.onDestroy()
        }
        exitProcess(1)
    }

    fun runPlayer(jsonObject: JSONObject) {
        val link = jsonObject.optString("url")
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(
            Uri.parse(link),
            if (link.endsWith(".m3u8")) "application/vnd.apple.mpegurl" else "video/*"
        )
        val resInfo = packageManager.queryIntentActivities(intent, 0)
        if (resInfo.isEmpty()) {
            Toast.makeText(
                this,
                R.string.no_activity_found,
                Toast.LENGTH_SHORT
            ).show()
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
            val targetedShareIntents: MutableList<Intent> = ArrayList()
            for (info in resInfo) {
                val targetedShare = Intent(Intent.ACTION_VIEW)
                targetedShare.setDataAndType(
                    Uri.parse(link),
                    if (link.endsWith(".m3u8")) "application/vnd.apple.mpegurl" else "video/*"
                )
                if (jsonObject.has("title")) {
                    targetedShare.putExtra("title", jsonObject.optString("title"))
                }
                targetedShare.setPackage(info.activityInfo.packageName.lowercase(Locale.getDefault()))
                targetedShareIntents.add(targetedShare)
            }
            // Then show the ACTION_PICK_ACTIVITY to let the user select it
            val intentPick = Intent()
            intentPick.action = Intent.ACTION_PICK_ACTIVITY
            // Set the title of the dialog
            intentPick.putExtra(Intent.EXTRA_TITLE, "Выберите плеер для просмотра")
            intentPick.putExtra(Intent.EXTRA_INTENT, intent)
            intentPick.putExtra(Intent.EXTRA_INITIAL_INTENTS, targetedShareIntents.toTypedArray())
            // Call StartActivityForResult so we can get the app name selected by the user
            startActivityForResult(intentPick, REQUEST_PLAYER_SELECT)
        } else {
            val requestCode: Int
            if (jsonObject.has("title")) {
                intent.putExtra("title", jsonObject.optString("title"))
            }
            when (SELECTED_PLAYER) {
                "com.mxtech.videoplayer.pro", "com.mxtech.videoplayer.ad" -> {
                    //                case "com.mxtech.videoplayer.beta":
                    requestCode = REQUEST_PLAYER_MX
                    intent.putExtra("sticky", false)
                    intent.putExtra("return_result", true)
                    intent.setClassName(SELECTED_PLAYER!!, "$SELECTED_PLAYER.ActivityScreen")
                }
                "org.videolan.vlc" -> {
                    requestCode = REQUEST_PLAYER_VLC
                    intent.setPackage(SELECTED_PLAYER)
                }
                else -> {
                    requestCode = REQUEST_PLAYER_OTHER
                    intent.setPackage(SELECTED_PLAYER)
                }
            }
            if (requestCode != REQUEST_PLAYER_OTHER) {
                if (jsonObject.has("timeline")) {
                    val timeline = jsonObject.optJSONObject("timeline")
                    if (timeline?.has("time") == true) {
                        val position = (jsonObject.optDouble("time") * 1000).toLong()
                        if (position > 0) intent.putExtra("position", position)
                    }
                }
            }
            try {
                startActivityForResult(intent, requestCode)
            } catch (e: Exception) {
                Log.d(TAG, e.message, e)
                Toast.makeText(
                    this,
                    R.string.no_activity_found,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_PLAYER_SELECT) {
            if (data != null && data.component != null && !TextUtils.isEmpty(data.component!!.flattenToShortString())) {
                SELECTED_PLAYER =
                    data.component!!.flattenToShortString().split("/".toRegex()).toTypedArray()[0]
                val editor = mSettings!!.edit()
                editor.putString(APP_PLAYER, SELECTED_PLAYER)
                editor.apply()

                // Now you know the app being picked.
                // data is a copy of your launchIntent with this important extra info added.

                // Start the selected activity
                startActivity(data)
            }
        } else if (requestCode == REQUEST_PLAYER_MX || requestCode == REQUEST_PLAYER_VLC) {
            when (resultCode) {
                RESULT_OK -> Log.i(TAG, "Ok: $data")
                RESULT_CANCELED -> Log.i(TAG, "Canceled: $data")
                RESULT_ERROR -> Log.e(TAG, "Error occurred: $data")
                else -> Log.w(TAG, "Undefined result code ($resultCode): $data")
            }
            if (data != null) dumpParams(data)
            Toast.makeText(
                this,
                "MX or VLC returned",
                Toast.LENGTH_LONG
            ).show()
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    companion object {
        private const val TAG = "APP_MAIN"
        const val RESULT_ERROR = RESULT_FIRST_USER + 0
        const val APP_PREFERENCES = "settings"
        const val APP_URL = "url"
        const val APP_PLAYER = "player"
        var LAMPA_URL: String? = ""
        var SELECTED_PLAYER: String? = ""
        private const val URL_REGEX = "^https?://([-A-Za-z0-9]+\\.)+[-A-Za-z]{2,}(:[0-9]+)?(/.*)?$"
        private val URL_PATTERN = Pattern.compile(URL_REGEX)
        const val REQUEST_PLAYER_SELECT = 1
        const val REQUEST_PLAYER_OTHER = 2
        const val REQUEST_PLAYER_MX = 222
        const val REQUEST_PLAYER_VLC = 4
        private fun dumpParams(intent: Intent) {
            val sb = StringBuilder()
            val extras = intent.extras
            sb.setLength(0)
            sb.append("* dat=").append(intent.data)
            Log.v(TAG, sb.toString())
            sb.setLength(0)
            sb.append("* typ=").append(intent.type)
            Log.v(TAG, sb.toString())
            if (extras != null && extras.size() > 0) {
                sb.setLength(0)
                sb.append("    << Extra >>\n")
                for ((i, key) in extras.keySet().withIndex()) {
                    sb.append(' ').append(i + 1).append(") ").append(key).append('=')
                    appendDetails(sb, extras[key])
                    sb.append('\n')
                }
                Log.v(TAG, sb.toString())
            }
        }

        private fun appendDetails(sb: StringBuilder, `object`: Any?) {
            if (`object` != null && `object`.javaClass.isArray) {
                sb.append('[')
                val length = Array.getLength(`object`)
                for (i in 0 until length) {
                    if (i > 0) sb.append(", ")
                    appendDetails(sb, Array.get(`object`, i))
                }
                sb.append(']')
            } else if (`object` is Collection<*>) {
                sb.append('[')
                var first = true
                for (element in `object`) {
                    if (first) first = false else sb.append(", ")
                    appendDetails(sb, element)
                }
                sb.append(']')
            } else sb.append(`object`)
        }
    }
}