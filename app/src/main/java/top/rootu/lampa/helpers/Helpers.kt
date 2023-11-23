package top.rootu.lampa.helpers

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.webkit.WebViewCompat
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonSyntaxException
import top.rootu.lampa.App
import top.rootu.lampa.BuildConfig
import top.rootu.lampa.MainActivity
import top.rootu.lampa.R
import top.rootu.lampa.models.TmdbID
import java.util.*


@Suppress("DEPRECATION")
object Helpers {
    // NOTE: as of Oreo you must also add the REQUEST_INSTALL_PACKAGES permission to your manifest. Otherwise it just silently fails
    @JvmStatic
    fun installPackage(context: Context?, packagePath: String?) {
        if (packagePath == null || context == null) {
            return
        }
        val file = FileHelpers.getFileUri(context, packagePath) ?: return
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(file, "application/vnd.android.package-archive")
        intent.flags =
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION // without this flag android returned a intent error!
        try {
            context.applicationContext.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            e.printStackTrace()
        }
    }

    fun uninstallSelf() {
        App.toast("Hooray!")
        val pm = App.context.packageManager
        pm.setComponentEnabledSetting(
            ComponentName(App.context, MainActivity::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        val intent = Intent(Intent.ACTION_DELETE)
        intent.data = Uri.parse("package:" + App.context.packageName)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        App.context.startActivity(intent)
    }

    fun openLampa(): Boolean {
        val intent = Intent(App.context, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        App.context.startActivity(intent)
        return true
    }
    fun openSettings(): Boolean {
        val intent = Intent(App.context, MainActivity::class.java)
        intent.putExtra("cmd", "open_settings")
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        App.context.startActivity(intent)
        return true
    }
    fun setLocale(activity: Activity, languageCode: String?) {
        if (BuildConfig.DEBUG) Log.d("APP_MAIN", "set Locale to [$languageCode]")
        val locale = languageCode?.let { Locale(it) } ?: return
        Locale.setDefault(locale)
        val resources: Resources = activity.resources
        val config: Configuration = resources.configuration
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            config.setLocales(LocaleList(locale))
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
            config.setLocale(locale)
        else
            config.locale = locale
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    @Suppress("DEPRECATION")
    private fun isConnectedOld(context: Context): Boolean {
        val connManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkInfo = connManager.activeNetworkInfo
        return networkInfo?.isConnected == true

    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun isConnectedNewApi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork)
        return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    fun isConnected(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            isConnectedNewApi(context)
        } else {
            isConnectedOld(context)
        }
    }

    fun dp2px(context: Context, dip: Float): Int {
        val dm = context.resources.displayMetrics
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dip, dm).toInt()
    }

    private val deviceName: String
        get() = String.format("%s (%s)", Build.MODEL, Build.PRODUCT)

    fun getVebWiewVersion(context: Context): String {
        return try {
            var version = WebViewCompat.getCurrentWebViewPackage(context)?.versionName
            if (version.isNullOrEmpty()) version = ""
            version
        } catch (e: Exception) {
            ""
        }
    }

    fun isWebViewAvailable(context: Context): Boolean {
        return try {
            context.packageManager.getApplicationInfo(
                WebViewCompat.getCurrentWebViewPackage(
                    context
                )?.packageName!!, 0
            ).enabled
        } catch (e: Exception) {
            Build.VERSION.SDK_INT == Build.VERSION_CODES.KITKAT
        }
    }

    fun buildPendingIntent(tmdbId: TmdbID, providerName: String?): Intent {
        val intent = Intent(App.context, MainActivity::class.java)
        intent.putExtra("id", tmdbId.id)
        intent.putExtra("media_type", tmdbId.media_type)
        val idStr = Gson().toJson(tmdbId)
        intent.putExtra("TmdbIDJS", idStr)
        providerName?.let { intent.putExtra("Provider", it) }
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        intent.action = tmdbId.id.toString()
        return intent
    }

    @JvmStatic
    val isGenymotion: Boolean
        get() {
            val deviceName = deviceName
            return deviceName.contains("(vbox86p)")
        }

    val isAndroidTV: Boolean
        get() {
            return App.context.packageManager.hasSystemFeature("android.software.leanback")
        }

    val isAmazonDev: Boolean
        get() {
            return App.context.packageManager.hasSystemFeature("amazon.hardware.fire_tv")
        }

    fun isValidJson(json: String?): Boolean {
        // val gson = Gson()
        return try {
            // gson.fromJson(json, Any::class.java)
            parseStrict(json) != null
        } catch (ex: JsonSyntaxException) {
            false
        }
    }
    private fun parseStrict(json: String?): JsonElement? {
        return try {
            // throws on almost any non-valid json
            Gson().getAdapter(JsonElement::class.java).fromJson(json)
        } catch (e: Exception) {
            null
        }
    }

    // TODO
    fun manageFavorite(action: String?, catgoryName: String, id: String) {
        // actions: add | remove
        Log.d("*****", "manageFavorite($action, $catgoryName, $id)")
        if (action != null) {
            // mainActivity.runVoidJsFunc("Lampa.Favorite.$action", "'$catgoryName', '{id: $id}'")
        }
    }

    /* NOTE! must be called after setContentView */
    fun Activity.hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window?.insetsController?.let {
                // Default behavior is that if navigation bar is hidden, the system will "steal" touches
                // and show it again upon user's touch. We just want the user to be able to show the
                // navigation bar by swipe, touches are handled by custom code -> change system bar behavior.
                // Alternative to deprecated SYSTEM_UI_FLAG_IMMERSIVE.
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                // make navigation bar translucent (alternative to deprecated
                // WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
                // - do this already in hideSystemUI() so that the bar
                // is translucent if user swipes it up
                window?.navigationBarColor = getColor(R.color.black_80)
                // Finally, hide the system bars, alternative to View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                // and SYSTEM_UI_FLAG_FULLSCREEN.
                it.hide(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window?.decorView?.systemUiVisibility = (
                    // Hide the nav bar and status bar
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            // Keep the app content behind the bars even if user swipes them up
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                // use immersive sticky mode
                window?.decorView?.systemUiVisibility =
                    window?.decorView?.systemUiVisibility?.or(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)!!
                // make navbar translucent
                window?.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
            }
        }
    }
    /* NOTE! must be called after setContentView */
    fun Activity.showSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // show app content in fullscreen, i. e. behind the bars when they are shown (alternative to
            // deprecated View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION and View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
            window?.setDecorFitsSystemWindows(false)
            // finally, show the system bars
            window?.insetsController?.show(WindowInsets.Type.systemBars())
        } else {
            // Shows the system bars by removing all the flags
            // except for the ones that make the content appear under the system bars.
            @Suppress("DEPRECATION")
            window?.decorView?.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }
}
