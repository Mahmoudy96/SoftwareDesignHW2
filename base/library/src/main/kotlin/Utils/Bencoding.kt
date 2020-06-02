package Utils

import java.security.MessageDigest
//changed it from class to object, so we dont have a Mybencoding variable in Coursetorrent Class
//object does not need a constructor
object Bencoding {
    private var info_indx_first = -1
    private var info_indx_last = -1
    private var flag = false

    /**********************************=/=Functions=\=**********************************/
    /**
     * Encodes object using the Bencoding format
     * @param obj: Any string, integer, string or map/dictionary
     * @returns Bencoded obj as a string
     */
    public fun EncodeObject(obj: Any?): String {
        //clean()
        var output = StringBuilder()
        if (obj is String) {
            output.append(obj.length).append(':').append(obj)
        } else if (obj is Number || obj is Byte || obj is Int) {
            output.append('i').append(obj).append('e')
        } else if (obj is Map<*, *>) {
            output.append('d')
            for ((key, entry) in obj) {
                output.append(EncodeObject(key))
                output.append(EncodeObject(entry))
            }
            output.append('e')
        } else if (obj is List<*>) {
            output.append('l')
            obj.forEach {
                output.append(EncodeObject(it))

            }
            output.append('e')
        }

        return output.toString()
    }

    /**
     * Inner recursive function for decoding bencoded objects. The function decodes a single bencoded object of any type,
     * Interger, string, list or dictionary, and ends when that single object is decoded, returning the objects value
     * and end point within the larger, .torrent format object.
     * @param obj: the object to decode
     * @param start_location: the byte within the object to start decoding from.
     * @return Pair of decoded object(Int, Str, List, Map, or null if invalid bencoding) and location to
     * continue iterating from within the object.
     */
    public fun DecodeObjectInner(obj: ByteArray, start_location: Int = 0): Pair<Any?, Int> {
        if (start_location >= obj.size) return Pair(null, -2)
        var itr = start_location
        val ben_type: Char = obj[itr].toChar()
        itr++
        if (ben_type == 'l') { //list
            val list_out = mutableListOf<Any?>()
            while (true) {
                if (itr >= obj.size) return Pair(null, -1)
                val char: Char = obj[itr].toChar()
                if (char == 'e') return Pair(list_out, itr + 1)
                val valueItrPair = DecodeObjectInner(obj, itr)
                if (valueItrPair.first == null) return valueItrPair
                list_out.add(valueItrPair.first)
                itr = valueItrPair.second
            }
        } else if (ben_type == 'i') {  //integer
            if (itr >= obj.size) return Pair(null, -1)
            var negFlag: Boolean = false
            var sum: Int = 0
            var num: Char = obj[itr].toChar()
            if (num == '-') {
                negFlag = true
                itr++
            }
            while (true) {
                if (itr >= obj.size) return Pair(null, -1)
                num = obj[itr].toChar()
                if (itr + 1 >= obj.size) return Pair(null, -1)
                if (num == 'e') {
                    if (negFlag) return Pair(-sum, itr + 1)
                    return Pair(sum, itr + 1)
                }

                sum = (sum * 10) + (num - '0')  //check if num-'0' is working fine
                itr++
            }
        } else if (ben_type == 'd') {//dictionary
            val dict = mutableMapOf<Any?, Any?>()
            while (true) {
                if (itr >= obj.size) return Pair(null, -1)
                val char: Char = obj[itr].toChar()
                // itr++
                if (char == 'e') return Pair(dict, itr + 1)

                val keyItrPair = DecodeObjectInner(obj, itr)
                if (keyItrPair.first == null) return keyItrPair
                itr = keyItrPair.second
                if (keyItrPair.first.toString() == "info") {
                    info_indx_first = itr
                }
                val valueItrPair = DecodeObjectInner(obj, itr)
                itr = valueItrPair.second
                if (keyItrPair.first.toString() == "info") {
                    info_indx_last = itr
                }

                ///infohash_raw = obj.substring(info_indx_first + 1, info_indx_last + 2)
                dict[keyItrPair.first] = valueItrPair.first
            }
        } else if (ben_type - '0' >= 0 && ben_type - '9' <= 9) {//string
            if (itr + 1 >= obj.size) return Pair(null, -1)
            var str_len = ben_type - '0'
            while (true) {
                if (itr + 1 >= obj.size) return Pair(null, -1)
                val char: Char = obj[itr].toChar()
                if (char == ':') {
                    if (flag == true) {//???
                        val stringEnd = itr + str_len
                        flag = false
                        //val b:Byte= Byte()
                        val res = obj.copyOfRange(itr + 1, stringEnd+1)
                        return Pair(res, stringEnd + 1)
                    }
                    val stringEnd = itr + str_len
                    val str = StringBuilder()
//                    itr++
//                    while (itr <= stringEnd) {
//                        str.append(obj[itr].toChar())
//                        itr++
//
//                    }
                    //TODO: convert obj to string then take substring?
                    //possible for characters to be MORE THAN ONE BYTE???
                    str.append((obj.copyOfRange(itr + 1, stringEnd+1)).toString(Charsets.UTF_8))
                    itr = stringEnd + 1
                    //the pieces value is encoded differently, so we handle the decoding differently
                    if ((str.toString() == "pieces") or (str.toString() == "peers")) {
                        flag = true
                    }
                    return Pair(str.toString(),itr) //itr is at byte AFTER end of string
                }
                str_len = (str_len * 10) + (char - '0')
                itr++
            }
        }
        return Pair(null, -1)
    }

    /**
     * Wrapper function for decoding of bencoded objects
     * Use this for the reverse function of EncodeObject
     * @param obj: ByteArray of a Bencoded object
     */
    public fun DecodeObject(obj: ByteArray): Any? {
        return DecodeObjectInner(obj).first

    }

    /**
     * Calculates the infohash of the given bencoded object
     * Requires running Bencoding.DecodeObject or Bencoding.DecodeObjectM first, to prepare the class
     */
    public fun infohash(obj: ByteArray): String {

        val bytes = MessageDigest
            .getInstance("SHA-1")
            .digest(obj.copyOfRange(info_indx_first, info_indx_last))

        val infohash2 = StringBuilder()
        for (byte in bytes) {
            infohash2.append("%02x".format(byte))
        }
        // print(infohash2.toString())
        return infohash2.toString()
    }


    /**
     *
     * URL-Encodes the infohash hex-string. Taking each pair of hex characters(a single byte) and translating it into
     * the appropriate url-encoded value
     * [infohash] 20-byte hex sha-1 hash of the bencoded value of the info key in a torrent meta info dictionary
     * @returns: URLEncoded infohash
     */
    public fun urlInfohash(infohash: String): String {
        val encodedInfohash = StringBuilder()
        val charList = ('0'..'9') + ('a'..'z') + ('A'..'Z') + '.'+'_'+'-'+'~'
        //println(charList)
        for(i in infohash.indices step 2) {
            val hexVal : String =  (infohash.get(i).toString() +infohash.get(i+1).toString())
            val char = java.lang.Long.parseLong(hexVal, 16).toChar()
            //println("char num: $i - " + char + " " + hexVal + "\n")
            when(char) {
                in charList -> encodedInfohash.append(char)
                ' '-> encodedInfohash.append('+')
                else -> encodedInfohash.append("%$hexVal")
            }
        }
        return encodedInfohash.toString()
    }

    /**
     * Decodes object as a map.
     */
    public fun DecodeObjectM(obj: ByteArray): Map<Any, Any>? {
        val out = mutableListOf<Any?>(); //TODO: change Any? to Any
        var itr = 0
        var started: Boolean = false
        while (true) {
            var o: Pair<Any?, Int> = DecodeObjectInner(obj, itr);
            if (o.second == -2) {
                //getMetaData(out.last() as Map<Any, Any>, obj)
                return out.last() as Map<Any, Any>;
            } else if (o.second == -1 && o.first == null) {
                return null
            } else {
                out.add(o.first)
                //  started = true
            }
            itr = o.second
        }

    }

    /**
     * Returns the announce list from the decoded dictionary of a torrent
     * @param obj: Dictionary representing an object with .torrent format
     * @return announce-list or announce(if -list doesn't exist) field of torrent.
     */
    public fun Announce(obj: Map<Any, Any>): List<List<String>> {
        if (obj.containsKey("announce-list")) {
            return obj["announce-list"] as List<List<String>>
        } else { //if (hasList)
            return mutableListOf(mutableListOf<String>(obj["announce"] as String))
        }
    }
}