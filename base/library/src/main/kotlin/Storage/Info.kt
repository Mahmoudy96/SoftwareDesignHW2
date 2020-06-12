package Storage

import Utils.InfoDictionary
import java.util.concurrent.CompletableFuture

interface Info {
    fun getInfo(infohash:String): CompletableFuture<ByteArray?>
    fun addInfo(infohash:String, info: InfoDictionary): CompletableFuture<Unit>
}