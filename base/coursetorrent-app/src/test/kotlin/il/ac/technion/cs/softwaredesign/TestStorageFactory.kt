package il.ac.technion.cs.softwaredesign

import il.ac.technion.cs.softwaredesign.storage.SecureStorage
import il.ac.technion.cs.softwaredesign.storage.SecureStorageFactory
import java.util.concurrent.CompletableFuture

class TestSecureStorageFactory : SecureStorageFactory {
    private val storageMap = mutableMapOf<String, SecureStorage>()
    private val enc = Charsets.UTF_8
    override fun open(name: ByteArray): CompletableFuture<SecureStorage> {
        val storage = storageMap[name.toString(enc)]
        var returnVal : SecureStorage? = null
        if (storage != null)
            returnVal = storage
        else
            returnVal = TestSecureStorage()
            storageMap[name.toString(enc)] = returnVal

        return returnVal
    }
}