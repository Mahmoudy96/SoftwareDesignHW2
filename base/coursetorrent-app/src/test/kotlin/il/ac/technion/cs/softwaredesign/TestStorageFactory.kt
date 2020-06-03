package il.ac.technion.cs.softwaredesign

import il.ac.technion.cs.softwaredesign.storage.SecureStorage
import il.ac.technion.cs.softwaredesign.storage.SecureStorageFactory
import io.github.vjames19.futures.jdk8.Future

class TestSecureStorageFactory : SecureStorageFactory {
    private val storageMap = mutableMapOf<String, SecureStorage>()
    private val enc = Charsets.UTF_8
    override fun open(name: ByteArray): java.util.concurrent.CompletableFuture<SecureStorage> {
        val storage = storageMap[name.toString(enc)]
        var returnVal : SecureStorage? = null
        if (storage != null)
            returnVal = storage
        else
            returnVal = TestSecureStorage()
            storageMap[name.toString(enc)] = returnVal

        return Future{returnVal!!}
    }
}