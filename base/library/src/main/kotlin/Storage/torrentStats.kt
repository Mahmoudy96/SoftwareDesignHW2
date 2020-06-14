package Storage

import java.util.concurrent.CompletableFuture

interface TorrentStats {

    fun addTorrentStats(infohash:String,torrentStats :Any): CompletableFuture<Unit>
    fun getTorrentStats(infohash:String): CompletableFuture<Any?>
}