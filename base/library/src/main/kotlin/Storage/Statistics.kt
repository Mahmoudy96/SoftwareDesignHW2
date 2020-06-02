package Storage

import java.util.concurrent.CompletableFuture


interface Statistics {

    fun addTrackerStats(infohash:String,statsMap:Map<String,Any>): CompletableFuture<Unit>
    fun getStats(infohash:String):CompletableFuture<Map<String,Any>?>
    fun updateStats(infohash:String,statsMap:Map<String,Any>):CompletableFuture<Unit>
}