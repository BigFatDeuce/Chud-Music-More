package app.chudmusic.ui

import android.net.Uri
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/** What other apps hand to Chud Music (a playlist file, a shared YouTube link), kept until the UI reads it. */
object Incoming {
    sealed interface Item {
        data class PlaylistFile(val uri: Uri) : Item
        data class SharedText(val text: String) : Item
    }

    private val channel = Channel<Item>(Channel.BUFFERED)
    val items = channel.receiveAsFlow()
    fun send(item: Item) { channel.trySend(item) }

    /** A YouTube / YouTube Music link → its video id. */
    fun videoIdOf(text: String): String? {
        val m = Regex("""(?:youtu\.be/|[?&]v=|/shorts/|/embed/)([A-Za-z0-9_-]{11})""").find(text) ?: return null
        return m.groupValues[1]
    }
}
