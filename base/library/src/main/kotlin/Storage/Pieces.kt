package Storage

import java.util.concurrent.CompletableFuture

interface Pieces {
    fun addTorrentPieces(infohash: String, PiecesLen: Byte, Pieces: ByteArray): CompletableFuture<Unit>
    fun getPiecesLen(infohash: String): CompletableFuture<Byte>
    fun getpieceOfIndex(infohash: String, PieceIndex: Long): CompletableFuture<Byte>
}