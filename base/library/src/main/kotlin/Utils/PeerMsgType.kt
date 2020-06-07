package Utils

enum class PeerMsgType {
    CHOKE,//0
    UNCHOKE,//1
    INTERESTED,//2
    NOT_INTERESTED,//3
    HAVE,//4
    BITFIELD,//5
    REQUEST,//6
    PIECE,//7
    CANCEL,//8
    KEEP_ALIV, //9  KEEP_ALIVE did not have a type value, 9  is what we are going to use to identify keepAlive
    UNKOWN_MSG_TYPE;//10


}

public fun getMType(msg: Int?): PeerMsgType {
    for (t in PeerMsgType.values()) {
        if (t.ordinal == msg) return t
    }
    return PeerMsgType.UNKOWN_MSG_TYPE
}