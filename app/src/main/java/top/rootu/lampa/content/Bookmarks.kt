package top.rootu.lampa.content

import android.util.Log
import top.rootu.lampa.AndroidJS
import top.rootu.lampa.models.TmdbID

class Bookmarks : LampaProviderI() {

    override fun get(): ReleaseID {
        return ReleaseID(Bookmarks.get())
    }

    fun add(tmdbID: String?) {
        Bookmarks.add(tmdbID)
    }

    fun rem(tmdbID: String?) {
        Bookmarks.rem(tmdbID)
    }

    fun isBookmarked(tmdbID: String?): Boolean {
        return get().items?.find { it.id.toString() == tmdbID } != null
    }

    fun isInWatchNext(tmdbID: String?): Boolean {
        val nxt = AndroidJS.FAV.wath
        return nxt?.contains(tmdbID) == true
    }
    companion object {
        fun get(): List<TmdbID> {
            val bookmarks = AndroidJS.FAV.book
            val cards = AndroidJS.FAV.card
            bookmarks?.let {
                Log.d("*****","Bookmarks.get() list: $bookmarks")
            }
            val found = cards?.filter { bookmarks?.contains(it.id) == true }
            //Log.d("*****", "Bookmarks cards found: ${found?.toString()}")

            return emptyList()
        }

        fun add(tmdbID: String?) {
            // TODO
        }

        fun rem(tmdbID: String?) {
            // TODO
        }

        fun addToWatchNext(it: String?) {
            // TODO
        }

        fun remFromWatchNext(it: String?) {
            // TODO
        }
    }
}