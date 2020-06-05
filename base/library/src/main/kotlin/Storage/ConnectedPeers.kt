package Storage

import java.util.concurrent.CompletableFuture

interface ConnectedPeers {
    fun addConnectedPeers(infohash:String,connectedPlist: Any): CompletableFuture<Unit>
    fun getConnectedPeers(infohash:String): CompletableFuture<List<Any?>>
    //add other functions if needed
}