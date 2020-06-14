package Storage

import Utils.InfoDictionary
import java.util.concurrent.CompletableFuture

interface Info {
    /*
     getter function
     @param infohash:String
     @return  CompletableFuture<ByteArray?>
    */
    fun getInfo(infohash:String): CompletableFuture<ByteArray?>
    /*
    adds InfoDictionary to Storage of torrent identified by  infohash
    @param infohash:String, info:InfoDictionary
    @return CompletableFuture<Unit>
    */
    fun addInfo(infohash:String, info: InfoDictionary): CompletableFuture<Unit>
}