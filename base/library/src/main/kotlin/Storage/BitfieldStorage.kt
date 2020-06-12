package Storage

import Utils.Conversion
import Utils.bitfieldStorage
import com.google.inject.Inject
import il.ac.technion.cs.softwaredesign.storage.SecureStorage
import java.util.concurrent.CompletableFuture

class BitfieldStorage @Inject constructor(
    @bitfieldStorage private val bitfieldStorage: SecureStorage
) : Bitfield {
    override fun addBitfield(infohash: String, Pieces: ByteArray): CompletableFuture<Unit> {
        //val pair = Pair(PiecesLen, Pieces)
        return bitfieldStorage.write(infohash.toByteArray(), Conversion.toByteArray(Pieces) as ByteArray)
    }

    override fun getBitfield(infohash: String): CompletableFuture<ByteArray> {
        return bitfieldStorage.read(infohash.toByteArray()).thenApply {
            if (it == null) null
            (Conversion.fromByteArray(it) as ByteArray)
        }
    }
/*
    override fun getpieceOfIndex(infohash: String, PieceIndex: Long): CompletableFuture<Byte> {
        return piecesStorage.read(infohash.toByteArray()).thenApply {
            if (it == null) null
            (Conversion.fromByteArray(it) as Pair<Long, ByteArray>).second[PieceIndex.toInt()]  //check if thats fine
        }
    }*/
}