package Utils

import java.io.Serializable

data class InfoDictionary (
        val pieceLength: Int,
        val pieces: ByteArray,
        val name: String,
        val length: Int?,
        val files: List<MultiFile>?
):Serializable

data class MultiFile(
        val length: Int,
        val path: String
):Serializable