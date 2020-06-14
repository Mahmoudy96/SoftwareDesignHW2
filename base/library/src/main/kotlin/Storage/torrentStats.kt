package Storage

import java.util.concurrent.CompletableFuture

interface TorrentStats {
    /*
          adds torrent stats to storage
          @param infohash:String,torrentStats :Any
          @return CompletableFuture<Unit>
         */
    fun addTorrentStats(infohash:String,torrentStats :Any): CompletableFuture<Unit>
    /*
       getter function
       @param infohash:String
       @return CompletableFuture<Any?>
      */
    fun getTorrentStats(infohash:String): CompletableFuture<Any?>
}