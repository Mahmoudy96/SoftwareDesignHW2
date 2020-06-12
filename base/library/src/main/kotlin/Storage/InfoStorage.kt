package Storage

import Utils.Conversion
import Utils.InfoDictionary
import Utils.infoStorage
import com.google.inject.Inject
import com.google.inject.Singleton
import il.ac.technion.cs.softwaredesign.storage.SecureStorage
import java.util.concurrent.CompletableFuture

@Singleton
class InfoStorage @Inject constructor(
        @infoStorage private val infoStorage: SecureStorage
) :Info{
    override fun addInfo(infohash: String, info: InfoDictionary): CompletableFuture<Unit> {
        return infoStorage.write(infohash.toByteArray(), Conversion.toByteArray(info) as ByteArray)

    }
    override fun getInfo(infohash: String): CompletableFuture<ByteArray?> {
        return infoStorage.read(infohash.toByteArray(Charsets.UTF_8))

    }
}