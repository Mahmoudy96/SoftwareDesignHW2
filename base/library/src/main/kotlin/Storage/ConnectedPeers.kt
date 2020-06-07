package Storage

import java.util.concurrent.CompletableFuture

interface ConnectedPeers {
    fun addConnectedPeers(infohash: String, connectedPlist: Map<String, Any>): CompletableFuture<Unit>
    fun getConnectedPeers(infohash:String): CompletableFuture<List<Any?>>
    fun getConnectedPeer(infohash: String,peerid: String): CompletableFuture<Any?>
    fun updateePeer(infohash: String, peerid: String,connectedPeer: Any): CompletableFuture<Unit>
    //add other functions if needed
}