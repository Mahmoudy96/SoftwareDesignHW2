package Storage

import Utils.Conversion
import com.google.inject.Inject
import com.google.inject.Singleton
import il.ac.technion.cs.softwaredesign.storage.SecureStorage
import java.util.concurrent.CompletableFuture


@Singleton
class TorrentStatistics @Inject constructor(
    @Utils.torrentStats private val torrentStatsStorage: SecureStorage
) : TorrentStats {
    override fun addTorrentStats(infohash: String, torrentStats: Any): CompletableFuture<Unit> {
        return torrentStatsStorage.write(infohash.toByteArray(), Conversion.toByteArray(torrentStats) as ByteArray)
    }

    override fun getTorrentStats(infohash: String): CompletableFuture<Any?> {
        return torrentStatsStorage.read(infohash.toByteArray())
            .thenApply { if (it == null) null else Conversion.fromByteArray(it) as Any }
    }

    override fun updateTorrentStats(infohash: String, statsMap: Map<String, Any>): CompletableFuture<Unit> {
        return torrentStatsStorage.write(infohash.toByteArray(), Conversion.toByteArray(statsMap) as ByteArray)
    }
}