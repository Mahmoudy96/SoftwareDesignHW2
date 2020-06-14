package Storage

import java.util.concurrent.CompletableFuture


interface Statistics {
    /*
       adds tracker statistics of torrent identified by indfohash
       @param infohash:String , statsMap:Map<String,Any>
       @return CompletableFuture<Unit>
       */
    fun addTrackerStats(infohash: String, statsMap: Map<String, Any>): CompletableFuture<Unit>

    /*
     returns the statistics of torrent identified by infohash
     @param infohash:String
     @return CompletableFuture<Map<String,Any>?>
     */
    fun getStats(infohash: String): CompletableFuture<Map<String, Any>?>

    /*
     updates tracker statistics of torrent identified by indfohash
     @param infohash:String , statsMap:Map<String,Any>
     @return CompletableFuture<Unit>
     */
    fun updateStats(infohash: String, statsMap: Map<String, Any>): CompletableFuture<Unit>
}