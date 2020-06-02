package Storage

import Utils.Conversion
import Utils.statsStorage
import com.google.inject.Inject
import com.google.inject.Singleton
import il.ac.technion.cs.softwaredesign.storage.SecureStorage
import java.util.concurrent.CompletableFuture

@Singleton
class StatisticsStorage @Inject constructor(
    @statsStorage private val statsStorage: SecureStorage
) : Statistics {
    override fun addTrackerStats(infohash: String, statsMap: Map<String, Any>): CompletableFuture<Unit> {
        return statsStorage.write(infohash.toByteArray(), Conversion.toByteArray(statsMap) as ByteArray)
    }

    override fun getStats(infohash: String): CompletableFuture<Map<String, Any>?> {
        return statsStorage.read(infohash.toByteArray())
            .thenApply { if (it == null) null else Conversion.fromByteArray(it) as Map<String, Any> }
    }

    /***
     * Scrape Data will be saved as a list of size 5. 0-3 are Scrape, in order. 4 is Failure.
     * if list(4) = null, no failure. otherwise, list(4) = failure reason
     */
    override fun updateStats(infohash: String, statsMap: Map<String, Any>): CompletableFuture<Unit> {
        return statsStorage.write(infohash.toByteArray(), Conversion.toByteArray(statsMap) as ByteArray)
    }
}