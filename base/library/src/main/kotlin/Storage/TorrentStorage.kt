package Storage

import Utils.Conversion
import Utils.Conversion.fromByteArray
import Utils.Conversion.toByteArray
import Utils.torrentStorage
import com.google.inject.Singleton
import il.ac.technion.cs.softwaredesign.storage.SecureStorage
import com.google.inject.Inject
import java.util.concurrent.CompletableFuture

@Singleton
class TorrentStorage @Inject constructor(
    @torrentStorage private val torrentStorage: SecureStorage
) : Torrent {
    override fun addTorrent(infohash: String, torrentData: ByteArray): CompletableFuture<Unit> {
        return torrentStorage.write(infohash.toByteArray(), torrentData)

    }

    override fun removeTorrent(infohash: String, unloadValue: String): CompletableFuture<Unit> {
        return torrentStorage.write(infohash.toByteArray(Charsets.UTF_8), unloadValue.toByteArray(Charsets.UTF_8))
    }

    override fun getTorrentData(infohash: String): CompletableFuture<ByteArray?> {
        return torrentStorage.read(infohash.toByteArray(Charsets.UTF_8))

    }

    override fun updateAnnounceList(infohash: String, announceList: List<List<String>>): CompletableFuture<Unit> {
        return torrentStorage.read(infohash.toByteArray())
            .thenApply {
                if (it == null || (fromByteArray(it).toString() == "unloaded")) null
            }.thenCompose {
                torrentStorage.write(
                    infohash.toByteArray(Charsets.UTF_8),
                    toByteArray(announceList) as ByteArray
                )
            }.thenApply { Unit }
    }
    override fun isTorrentLoaded(infohash: String): CompletableFuture<Boolean> {
        return torrentStorage.read(infohash.toByteArray(Charsets.UTF_8)).thenApply {
            it != null && (it.toString(Charsets.UTF_8) != "unloaded")
        }
    }
}