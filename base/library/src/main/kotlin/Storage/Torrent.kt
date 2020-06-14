package Storage

import java.util.concurrent.CompletableFuture

interface Torrent {
    /*
     adds a torrent to storage, the torrent is identified by infohash
     @param infohash:String, torrentData:ByteArray
     @return CompletableFuture<Unit>
    */
    fun addTorrent(infohash: String, torrentData: ByteArray): CompletableFuture<Unit>

    /*
     removes a torrent from storage, the torrent is considered unloaded by replacing its "metadata" with unloaded value
     @param infohash:String, unloadValue: String
     @return CompletableFuture<Unit>
    */
    fun removeTorrent(infohash: String, unloadValue: String): CompletableFuture<Unit>

    /*
      getter funtion
      @param infohash:String
      @return CompletableFuture<ByteArray?>
     */
    fun getTorrentData(infohash: String): CompletableFuture<ByteArray?>

    /*
    updates annuonceList of torrent indentified by infohash
    @param infohash:String, announceList: List<List<String>>
    @return CompletableFuture<Unit>
   */
    fun updateAnnounceList(infohash: String, announceList: List<List<String>>): CompletableFuture<Unit>

    /*
     returns true if torrent is loaded
     @param infohash:String
     @return CompletableFuture<Boolean>
    */
    fun isTorrentLoaded(infohash: String): CompletableFuture<Boolean>
}