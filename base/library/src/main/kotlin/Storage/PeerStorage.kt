package Storage

import Utils.Conversion
import Utils.peerStorage
import com.google.inject.Inject
import com.google.inject.Singleton
import il.ac.technion.cs.softwaredesign.storage.SecureStorage
import java.util.concurrent.CompletableFuture

@Singleton
class PeerStorage @Inject constructor(
    @peerStorage private val peerStorage: SecureStorage
) : Peer {
    override fun addPeers(infohash: String, peerData: List<Any>): CompletableFuture<Unit> {
        return peerStorage.write(infohash.toByteArray(), Conversion.toByteArray(peerData) as ByteArray)
    }

    override fun getPeers(infohash: String): CompletableFuture<List<Any>?> {
        return peerStorage.read(infohash.toByteArray())
            .thenApply { if (it == null) null else Conversion.fromByteArray(it) as List<Any>? }
    }

    override fun invalidatePeer(infohash: String, peerId: String): CompletableFuture<Unit> {
        // val peers = peerStorage.read(infohash.toByteArray())  //USED JOIN HERE, not sure if the implementation
        return peerStorage.read(infohash.toByteArray())
            .thenApply {
                if (it == null) null
            }.thenCompose {
                peerStorage.write(
                    infohash.toByteArray(),//TODO: does this actually work?
                    Conversion.toByteArray((it as Map<String, Any>).minus(peerId)) as ByteArray
                )
            }.thenApply { Unit }


//        val peersList = peers.join() as Map<String, Any>
//        return peers.thenApply {
//            if (it == null) null else peerStorage.write(
//                infohash.toByteArray(),
//                Conversion.toByteArray(peersList.minus(peerId)) as ByteArray
//            )
//        }

}

}