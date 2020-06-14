package Storage

import java.util.concurrent.CompletableFuture

interface Peer {
    /*
    adds peers to torrent identified by infohash
    @param infohash:String , peerData:List< Any>
    @return CompletableFuture<Unit>
    */
    fun addPeers(infohash: String, peerData: List<Any>): CompletableFuture<Unit>

    /*
    getter function
    @param infohash:String
    @return CompletableFuture<List<Any>?>
    */
    fun getPeers(infohash: String): CompletableFuture<List<Any>?>

    /*
     invalidate a peer identified by peerId, this is an update function
     @param infohash:String ,peerId:String
     @return CompletableFuture<Unit>
     */
    fun invalidatePeer(infohash: String, peerId: String): CompletableFuture<Unit>
}