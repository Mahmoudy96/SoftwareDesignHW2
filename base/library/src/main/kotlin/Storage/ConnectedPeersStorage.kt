package Storage

import Utils.Conversion
import com.google.inject.Inject
import com.google.inject.Singleton
import il.ac.technion.cs.softwaredesign.storage.SecureStorage
import java.util.concurrent.CompletableFuture


@Singleton
class ConnectedPeersStorage @Inject constructor(
    @Utils.ConnectedPeersStorage private val ConnectedPeersStorage: SecureStorage
) : ConnectedPeers {
    override fun addConnectedPeers(infohash: String, connectedPlist: Map<String, Any>): CompletableFuture<Unit> {
        return ConnectedPeersStorage.write(infohash.toByteArray(), Conversion.toByteArray(connectedPlist) as ByteArray)

    }

    override fun getConnectedPeers(infohash: String): CompletableFuture<List<Any?>> {
        return ConnectedPeersStorage.read(infohash.toByteArray())
            .thenApply { if (it == null) null else (Conversion.fromByteArray(it) as Map<String, Any>).values.toList() as List<Any> }
    }

    override fun getConnectedPeer(infohash: String, peerid: String): CompletableFuture<Any?> {
        return ConnectedPeersStorage.read(infohash.toByteArray())
            .thenApply { if (it == null) null else (Conversion.fromByteArray(it) as Map<String, Any>)[peerid] }
    }

    override fun updateePeer(infohash: String, peerid: String, connectedPeer: Any): CompletableFuture<Unit> {
        return ConnectedPeersStorage.read(infohash.toByteArray()).thenApply {
            if (it == null || !((Conversion.fromByteArray(it) as Map<String, Any>).containsKey(peerid))) throw IllegalArgumentException()
            else
                (Conversion.fromByteArray(it) as HashMap<String, Any>)[peerid] = connectedPeer
        }
    }
}