package il.ac.technion.cs.softwaredesign

import il.ac.technion.cs.softwaredesign.storage.SecureStorage
import io.github.vjames19.futures.jdk8.Future


class TestSecureStorage  : SecureStorage {

    val storage = mutableMapOf<String,ByteArray>()

    private val enc = Charsets.UTF_8
    override fun read(key: ByteArray): java.util.concurrent.CompletableFuture<ByteArray?> {
        val value = storage[key.toString(enc)]
        if (value != null) {
            Thread.sleep(value.size.toLong())
        }
        return Future{value}
    }

    override fun write(key: ByteArray, value: ByteArray): java.util.concurrent.CompletableFuture<Unit> {
        storage[key.toString(enc)] = value
        return Future{Unit}
    }
}
