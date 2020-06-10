package il.ac.technion.cs.softwaredesign

import java.io.Serializable

data class KnownPeer(
    val ip: String,
    val port: Int,
    val peerId: String?

) : Serializable {
    public fun equals(peer: KnownPeer): Boolean {
        return ((peerId == peer.peerId) && (ip == peer.ip))
    }
}