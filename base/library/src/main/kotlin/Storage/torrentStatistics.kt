package Storage

import Utils.Conversion
import Utils.statsStorage
import com.google.inject.Inject
import com.google.inject.Singleton
import il.ac.technion.cs.softwaredesign.storage.SecureStorage
import java.util.concurrent.CompletableFuture


@Singleton
class torrentStatistics @Inject constructor(
    @Utils.torrentStats private val torrentStatsStorage: SecureStorage
) : torrentStats {
    override fun addTorrentStats(infohash: String, torrentStats: Any): CompletableFuture<Unit> {
        return torrentStatsStorage.write(infohash.toByteArray(), Conversion.toByteArray(torrentStats) as ByteArray)
    }

    override fun getTorrentStats(infohash: String): CompletableFuture<Any?> {
        return torrentStatsStorage.read(infohash.toByteArray())
            .thenApply { if (it == null) null else Conversion.fromByteArray(it) as Any }
    }

    override fun updateTorrentStats(infohash: String, statsMap: Any): CompletableFuture<Unit> {
        return torrentStatsStorage.write(infohash.toByteArray(), Conversion.toByteArray(statsMap) as ByteArray)
    }
}