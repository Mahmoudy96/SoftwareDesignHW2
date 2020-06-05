package Storage

import java.util.concurrent.CompletableFuture

interface torrentStats {

    fun addTorrentStats(infohash:String,torrentStats :Any): CompletableFuture<Unit>
    fun getTorrentStats(infohash:String): CompletableFuture<Any?>
    fun updateTorrentStats(infohash:String,statsMap:Map<String,Any>): CompletableFuture<Unit>

}