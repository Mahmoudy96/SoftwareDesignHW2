package LibraryPackage

import Utils.Bencoding
import com.natpryce.hamkrest.*
import com.natpryce.hamkrest.assertion.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test


class BencodingTest {
    private val debian = this::class.java.getResource("/debian-10.3.0-amd64-netinst.iso.torrent").readBytes()
    private val announceListTorrent = this::class.java.getResource("/announceListTorrent.torrent").readBytes()
    private val encoding = Charsets.UTF_8

    @Nested inner class `Tests for encoding` {
        @Test fun testStringEncoding (){
            val test_string = "bencoding"
            val empty_string = ""
            val encoded_string = Bencoding.EncodeObject(test_string)
            val empty_encoding = Bencoding.EncodeObject(empty_string)
            assertThat(encoded_string, equalTo("9:bencoding"))
            assertThat(empty_encoding, equalTo("0:"))
        }
        @Test fun testIntegerEncoding (){
            val test_int = 368
            val test_int_neg = -15
            val zero = 0
            val encoded_int = Bencoding.EncodeObject(test_int)
            val encoded_neg = Bencoding.EncodeObject(test_int_neg)
            val encoded_nil = Bencoding.EncodeObject(zero)
            assertThat(encoded_int, equalTo("i368e"))
            assertThat(encoded_neg, equalTo("i-15e"))
            assertThat(encoded_nil, equalTo("i0e"))
        }
        @Test fun `test list encoding` (){
            val empty_list = listOf<String>()
            val test_list = listOf("ben","coding")
            val encoded_list = Bencoding.EncodeObject(test_list)
            val encoded_empty_list = Bencoding.EncodeObject(empty_list)
            assertThat(encoded_list, equalTo("l3:ben6:codinge")) //doesn't pass
            assertThat(encoded_empty_list, equalTo("le"))

        }
        @Test fun `test dictionary encoding` () {
            val empty_dict = mapOf<Int, Int>()
            val test_dict = mapOf(1 to "eggs", "be" to 3)
            val complicated_dict = mapOf("announce-list" to listOf("url1/6969", "url2/abc"), "info" to mapOf("comment" to "hello", "length" to 45))
            val encoded_empty_map = Bencoding.EncodeObject(empty_dict)
            val encoded_map = Bencoding.EncodeObject(test_dict)
            val complicated_encoding = Bencoding.EncodeObject(complicated_dict)
            print(complicated_encoding)
            assertThat(encoded_empty_map, equalTo("de"))
            assertThat(encoded_map, equalTo("di1e4:eggs2:bei3ee"))
            assertThat(complicated_encoding, equalTo("d13:announce-listl9:url1/69698:url2/abce4:infod7:comment5:hello6:lengthi45eee"))
        }
    }

    @Nested inner class `tests for decoding` {
        @Test fun `test int decoding`() {
            val encoded_int = "i25603e".toByteArray(encoding)
            val encoded_neg = "i-1259e".toByteArray(encoding)
            val encoded_zero = "i0e".toByteArray(encoding)
            val decoded_int = Bencoding.DecodeObject(encoded_int) as Int
            val decoded_neg = Bencoding.DecodeObject(encoded_neg) as Int
            val decoded_zero = Bencoding.DecodeObject(encoded_zero) as Int
            //is there a better way than .toString.toInt???
            assertThat(decoded_int, equalTo(25603))
            assertThat(decoded_neg, equalTo(-1259))
            assertThat(decoded_zero, equalTo(0))
        }
        @Test fun `test string decoding` (){
            val encoded_str = "4:spam".toByteArray(encoding)
            val encoded_empty_str = "0:".toByteArray(encoding)
            val decoded_str = Bencoding.DecodeObject(encoded_str) as String
            val decoded_empty_str = Bencoding.DecodeObject(encoded_empty_str) as String
            assertThat(decoded_str, equalTo("spam"))
            assertThat(decoded_empty_str, equalTo(""))


        }
        @Test fun `test list decoding` (){
            val empty_encoding = "le".toByteArray(encoding)
            val encoded_list = "l3:ben6:codinge".toByteArray(encoding)
            val decoded_empty = Bencoding.DecodeObject(empty_encoding) as List<String>
            val decoded_list = Bencoding.DecodeObject(encoded_list) as List<Any>
            //print(decoded_list)
            assertThat(decoded_empty.size, equalTo(0))
            //assertThat(decoded_list, equalTo(listOf("ben","coding")))

        }
        @Test fun `test dictionary decoding` (){
            val empty_encoding = "de".toByteArray(encoding)
            val encoded_dict = "di1e4:eggs2:bei3ee".toByteArray(encoding)
            val decoded_empty = Bencoding.DecodeObject(empty_encoding) as Map<Any, Any>
            val decoded_dict = Bencoding.DecodeObject(encoded_dict) as Map<Any, Any>
            assertThat(decoded_empty.size, equalTo(0))
            assertThat(decoded_dict, equalTo(mapOf(1 to "eggs", "be" to 3)))
        }
        @Test fun `decode entire torrent` () {
            val decoding = Bencoding.DecodeObject(debian)
            println(decoding.toString())
        }
    }
    @Test fun `test infohashing`(){
        val decoding = Bencoding.DecodeObjectM(debian)
        val infohash = Bencoding.infohash(debian)
        assertThat(infohash, equalTo("5a8062c076fa85e8056451c0d9aa04349ae27909"))
    }
    @Test fun `test announce`(){
        val decoding = Bencoding.DecodeObjectM(debian)
        val announce = Bencoding.Announce(decoding!!)
        assertThat(announce, allElements(hasSize(equalTo(1))))
        assertThat(announce, hasSize(equalTo(1)))
        assertThat(announce, allElements(hasElement("http://bttracker.debian.org:6969/announce")))
    }

    @Test fun `test announce-list`(){
        //one of the announce urls in the list
        val decoding = Bencoding.DecodeObjectM(announceListTorrent)
        val announces = Bencoding.Announce(decoding!!)
        assertThat(announces, allElements(hasSize(equalTo(1))))
        //8 urls in announce-list + announce
        assertThat(announces, hasSize(equalTo(8)))
        assertThat(announces, anyElement(hasElement("udp://62.138.0.158:6969/announce")))
    }
}