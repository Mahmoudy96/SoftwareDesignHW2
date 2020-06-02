package il.ac.technion.cs.softwaredesign

import il.ac.technion.cs.softwaredesign.storage.SecureStorage
import java.util.concurrent.CompletableFuture


class TestSecureStorage  : SecureStorage {

    val storage = mutableMapOf<String,ByteArray>()

    private val enc = Charsets.UTF_8
    override fun read(key: ByteArray): CompletableFuture<ByteArray?> {
        val value = storage[key.toString(enc)]
        if (value != null) {
            Thread.sleep(value.size.toLong())
        }
        return value
    }

    override fun write(key: ByteArray, value: ByteArray): CompletableFuture<Unit> {
        storage[key.toString(enc)] = value
    }
}
