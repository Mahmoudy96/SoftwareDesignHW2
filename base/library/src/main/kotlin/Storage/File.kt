package Storage

import java.util.concurrent.CompletableFuture

interface File {
    fun getFiles(infohash:String): CompletableFuture<Map<String,ByteArray>>
    fun getFile(infohash:String, filename: String): CompletableFuture<ByteArray>
    fun addFile(infohash:String, filename: String, file: ByteArray): CompletableFuture<Unit>
    fun addFiles(infohash:String, files: Map<String, ByteArray>) : CompletableFuture<Unit>
}