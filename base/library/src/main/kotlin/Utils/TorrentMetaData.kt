package Utils

import java.io.Serializable

class TorrentMetaData : Serializable {
    private var info_hash: String = ""
    private var announce: List<List<String>> = mutableListOf<List<String>>()
    private var MapTorrent: Map<Any, Any>? = mutableMapOf<Any, Any>()
    public fun init(info: String, anno: List<List<String>>, map: Map<Any, Any>) {
        info_hash = info
        announce = anno
        MapTorrent = map
    }

    public fun getAnnounce(): List<List<String>> {
        return announce
    }
}
