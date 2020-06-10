package Storage

import Utils.Conversion
import Utils.peerStorage
import Utils.piecesStorage
import com.google.inject.Inject
import il.ac.technion.cs.softwaredesign.storage.SecureStorage
import java.util.concurrent.CompletableFuture

class torrentPieces @Inject constructor(
    @piecesStorage private val piecesStorage: SecureStorage
) : Pieces {
    override fun addTorrentPieces(infohash: String, PiecesLen: Byte, Pieces: ByteArray): CompletableFuture<Unit> {
        val pair = Pair(PiecesLen, Pieces)
        return piecesStorage.write(infohash.toByteArray(), Conversion.toByteArray(pair) as ByteArray)
    }

    override fun getPiecesLen(infohash: String): CompletableFuture<Byte> {
        return piecesStorage.read(infohash.toByteArray()).thenApply {
            if (it == null) null
            (Conversion.fromByteArray(it) as Pair<Byte, ByteArray>).first
        }
    }

    override fun getpieceOfIndex(infohash: String, PieceIndex: Long): CompletableFuture<Byte> {
        return piecesStorage.read(infohash.toByteArray()).thenApply {
            if (it == null) null
            (Conversion.fromByteArray(it) as Pair<Long, ByteArray>).second[PieceIndex.toInt()]  //check if thats fine
        }
    }
}