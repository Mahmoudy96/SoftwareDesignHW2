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
    override fun addConnectedPeers(infohash: String, connectedPlist: Any): CompletableFuture<Unit> {
        return ConnectedPeersStorage.write(infohash.toByteArray(), Conversion.toByteArray(connectedPlist) as ByteArray)

    }

    override fun getConnectedPeers(infohash: String): CompletableFuture<List<Any?>> {
        return ConnectedPeersStorage.read(infohash.toByteArray())
            .thenApply { if (it == null) null else Conversion.fromByteArray(it) as List<Any?> }
    }
}