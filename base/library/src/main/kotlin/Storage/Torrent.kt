package Storage

import java.util.concurrent.CompletableFuture

interface Torrent {
    fun addTorrent(infohash: String, torrentData: ByteArray): CompletableFuture<Unit>
    fun removeTorrent(infohash: String, unloadValue: String): CompletableFuture<Unit>
    fun getTorrentData(infohash: String): CompletableFuture<ByteArray?>
    fun updateAnnounceList(infohash: String, announceList: List<List<String>>): CompletableFuture<Unit>
    fun isTorrentLoaded(infohash: String): CompletableFuture<Boolean>
}