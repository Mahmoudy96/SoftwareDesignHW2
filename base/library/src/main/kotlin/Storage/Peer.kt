package Storage

import java.util.concurrent.CompletableFuture

interface Peer {
    fun addPeers(infohash:String,peerData:List< Any>):CompletableFuture<Unit>
    fun getPeers(infohash:String): CompletableFuture<List<Any>?>
    fun invalidatePeer(infohash:String,peerId:String):CompletableFuture<Unit>
}