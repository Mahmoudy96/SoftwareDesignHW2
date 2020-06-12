package Storage

import java.util.concurrent.CompletableFuture

interface Bitfield {
    fun addBitfield(infohash: String, Pieces: ByteArray): CompletableFuture<Unit>
    fun getBitfield(infohash: String): CompletableFuture<ByteArray>
    //fun getpieceOfIndex(infohash: String, PieceIndex: Long): CompletableFuture<Byte>
}