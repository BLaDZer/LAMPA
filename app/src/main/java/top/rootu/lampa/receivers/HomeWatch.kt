package top.rootu.lampa.receivers

import android.annotation.TargetApi
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.os.Build
import android.util.Log
import androidx.tvprovider.media.tv.TvContractCompat
import top.rootu.lampa.App
import top.rootu.lampa.BuildConfig
import top.rootu.lampa.channels.ChannelManager
import top.rootu.lampa.channels.WatchNext
import top.rootu.lampa.content.Bookmarks
import top.rootu.lampa.helpers.Helpers.isAndroidTV
import top.rootu.lampa.helpers.Prefs.useWatchNext
import top.rootu.lampa.sched.Scheduler

@TargetApi(Build.VERSION_CODES.O)
class HomeWatch : BroadcastReceiver() {
    private val TAG = if (BuildConfig.DEBUG) "*****: HomeWatch" else "HomeWatch"

    override fun onReceive(context: Context, intent: Intent) {

        val action = intent.action

        if (action == null || !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isAndroidTV))
            return

        val watchNextId = intent.getLongExtra(TvContract.EXTRA_WATCH_NEXT_PROGRAM_ID, -1L)
        val previewId = intent.getLongExtra(TvContract.EXTRA_PREVIEW_PROGRAM_ID, -1L)

        when (action) {

            TvContractCompat.ACTION_INITIALIZE_PROGRAMS -> {
                if (BuildConfig.DEBUG)
                    Log.d(TAG, "ACTION_INITIALIZE_PROGRAMS received")
                Scheduler.scheduleUpdate(true)
            }

            TvContractCompat.ACTION_PREVIEW_PROGRAM_ADDED_TO_WATCH_NEXT -> {
                if (BuildConfig.DEBUG)
                    Log.d(TAG, "ACTION_PREVIEW_PROGRAM_ADDED_TO_WATCH_NEXT, preview $previewId, watch-next $watchNextId")
                if (!App.context.useWatchNext)
                    try {
                        val tmdbID = WatchNext.getTmdbIdFromWatchNextProgramId(watchNextId)
                        tmdbID?.let {
                            if (!Bookmarks().isBookmarked(tmdbID))
                                Bookmarks.add(it)
                        }
                    } catch (_: Exception) {
                    }
            }

            TvContractCompat.ACTION_WATCH_NEXT_PROGRAM_BROWSABLE_DISABLED -> {
                if (BuildConfig.DEBUG)
                    Log.d(TAG, "ACTION_WATCH_NEXT_PROGRAM_BROWSABLE_DISABLED, watch-next $watchNextId")
                if (!App.context.useWatchNext)
                    try {
                        val tmdbID = WatchNext.getTmdbIdFromWatchNextProgramId(watchNextId)
                        tmdbID?.let {
                            if (!Bookmarks().isBookmarked(tmdbID))
                                Bookmarks.rem(it)
                        }
                    } catch (_: Exception) {
                    }
            }
            // FIXME: match against favorite channel
            TvContractCompat.ACTION_PREVIEW_PROGRAM_BROWSABLE_DISABLED -> {
                if (BuildConfig.DEBUG)
                    Log.d(TAG, "ACTION_PREVIEW_PROGRAM_BROWSABLE_DISABLED, preview $previewId")
                try {
                    val tmdbID = ChannelManager.getTmdbIdFromPreviewProgramId(previewId)
                    tmdbID?.let {
                        if (Bookmarks().isBookmarked(it))
                            Bookmarks.rem(it)
                    }
                } catch (_: Exception) {
                }
            }
        }
    }
}