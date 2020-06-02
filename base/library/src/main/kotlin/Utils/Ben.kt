package Utils

/**
 * Bencode Encoder/Decoder in Kotlin (extended version) - https://en.wikipedia.org/wiki/Bencode
 * https://gist.github.com/omarmiatello/b09d4ba995c8c0881b0921c0e7aa9bdc
 *
 * By Omar Miatello
 */
class Ben(val str: String, var i: Int = 0) {
    fun read(count: Int) = str.substring(i, i + count).apply { i += length }
    fun readUntil(c: Char) = str.substring(i, str.indexOf(c, i)).apply { i += length + 1 }
    fun decode(): Any = when (read(1)[0]) {
        'i' -> readUntil('e').toInt()
        'l' -> ArrayList<Any>().apply {
            var obj = decode()
            while (obj != Unit) {
                add(obj)
                obj = decode()
            }
        }
        'd' -> HashMap<String, Any>().apply {
            var obj = decode()
            while (obj != Unit) {
                put(obj as String, decode())
                obj = decode()
            }
        }
        'e' -> Unit
        in ('0'..'9') -> read((str[i-1] + readUntil(':')).toInt())
        else -> throw IllegalStateException("Char: ${str[i-1]}")
    }

    companion object {
        fun encodeStr(obj: Any): String = when (obj) {
            is Int -> "i${obj}e"
            is String -> "${obj.length}:$obj"
            is List<*> -> "l${obj.joinToString("") { encodeStr(it!!) }}e"
            is Map<*, *> -> "d${obj.map { encodeStr(it.key!!) + encodeStr(it.value!!) }.joinToString("")}e"
            else -> throw IllegalStateException()
        }
    }
}