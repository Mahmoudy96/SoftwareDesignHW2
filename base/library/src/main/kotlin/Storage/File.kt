package Storage

import java.util.concurrent.CompletableFuture

interface File {
    /*
    returns torrent's files (torrent has more than one file)
    @param infohash:String
    @returns CompletableFuture<Map<String,ByteArray>>
   */
    fun getFiles(infohash: String): CompletableFuture<Map<String, ByteArray>>

    /*
     returns torrent's file identified by filename
     @param infohash:String, filename: String
     @returns CompletableFuture<ByteArray>
    */
    fun getFile(infohash: String, filename: String): CompletableFuture<ByteArray>

    /*
   adds a file to torrent identified by infohash
   @param infohash:String, filename: String, file: ByteArray
   @returns CompletableFuture<Unit>
  */
    fun addFile(infohash: String, filename: String, file: ByteArray): CompletableFuture<Unit>
    /*
  adds a files to torrent identified by infohash
  @param infohash:String, files: Map<String, ByteArray>
  @returns CompletableFuture<Unit>
 */
    fun addFiles(infohash: String, files: Map<String, ByteArray>): CompletableFuture<Unit>
}