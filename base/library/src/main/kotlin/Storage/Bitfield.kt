package Storage

import java.util.concurrent.CompletableFuture

interface Bitfield {
    /*
      adds Pieces of torrent Identified by infohash to storage
      @param infohash:String, Pieces:byteArray
      @return CompletableFuture<Unit>
     */
    fun addBitfield(infohash: String, Pieces: ByteArray): CompletableFuture<Unit>

    /*
   a getter funtion
   @param infohash:String
   @return CompletableFuture<ByteArray>
   */
    fun getBitfield(infohash: String): CompletableFuture<ByteArray>
    //fun getpieceOfIndex(infohash: String, PieceIndex: Long): CompletableFuture<Byte>
}