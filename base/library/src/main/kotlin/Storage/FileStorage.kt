package Storage

import Utils.Conversion
import Utils.fileStorage
import com.google.inject.Inject
import com.google.inject.Singleton
import il.ac.technion.cs.softwaredesign.storage.SecureStorage
import java.util.concurrent.CompletableFuture

@Singleton
class FileStorage @Inject constructor(
        @fileStorage private val fileStorage: SecureStorage
) :File{
    override fun getFiles(infohash:String): CompletableFuture<Map<String, ByteArray>> {

        return fileStorage.read(infohash.toByteArray()).thenApply{if (it==null) null else Conversion.fromByteArray(it) as Map<String,ByteArray> }
    }

    override fun getFile(infohash: String, filename: String): CompletableFuture<ByteArray> {
        return fileStorage.read(infohash.toByteArray()).thenApply{if (it==null) null else Conversion.fromByteArray(it) }
                .thenApply {if(it == null) null else (it as Map<String,ByteArray>)[filename] }
    }

    override fun addFile(infohash:String, filename: String, file: ByteArray): CompletableFuture<Unit> {
        return fileStorage.read(infohash.toByteArray())
                .thenCompose{if(it==null) fileStorage.write(infohash.toByteArray(),Conversion.toByteArray(mapOf(filename to file)) as ByteArray)
                else {
                    val newFiles = (Conversion.fromByteArray(it) as Map<String,ByteArray>).plus(Pair(filename, file))
                    fileStorage.write(infohash.toByteArray(), Conversion.toByteArray(newFiles) as ByteArray)
                }
                }
    }

    override fun addFiles(infohash:String, files: Map<String, ByteArray>):CompletableFuture<Unit> {
        return fileStorage.write(infohash.toByteArray(), Conversion.toByteArray(files) as ByteArray)
    }

    override fun getPiece(infohash:String, filename: String, pieceIndex: Int): CompletableFuture<ByteArray> = TODO("lol")
    override fun updatePiece(infohash: String, filename: String, pieceIndex: Int, piece: ByteArray): CompletableFuture<Unit> = TODO("implement me")

}