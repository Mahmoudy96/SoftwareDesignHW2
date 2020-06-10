package il.ac.technion.cs.softwaredesign

import Storage.*
import Utils.Bencoding
import Utils.Conversion
import Utils.HTTPGet
import com.google.inject.Inject
import il.ac.technion.cs.softwaredesign.exceptions.PeerChokedException
import il.ac.technion.cs.softwaredesign.exceptions.PeerConnectException
import il.ac.technion.cs.softwaredesign.exceptions.TrackerException
import io.github.vjames19.futures.jdk8.*
import java.io.IOException
import java.lang.Exception
import java.lang.System.currentTimeMillis
import java.net.*
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CompletableFuture

const val DEFAULT_REQUEST_SIZE = 16384  // Default block size is 2^14 bytes, or 16kB.
//TODO add global "clock" variabel
/**
 * This is the class implementing CourseTorrent, a BitTorrent client.
 *
 * Currently specified:
 * + Parsing torrent metainfo files (".torrent" files)
 * + Communication with trackers (announce, scrape).
 */
class CourseTorrentImpl @Inject constructor(
    private val statStorage: Statistics,
    private val peerStorage: Peer,
    private val torrentStorage: Torrent,
    private val torrentStatStorage: torrentStatistics,
    private val fileStorage: File,
    private val torrentPieces: Pieces,
    private val httpRequest: HTTPGet = HTTPGet()

) : CourseTorrent {
    private val connectedPeerStorage: HashMap<String, MutableList<Pair<ConnectedPeer, Socket>>> = hashMapOf()
    private val peersPiecesStorage: HashMap<String, MutableList<Pair<ConnectedPeer, MutableList<Long>>>> = hashMapOf()
    private val peersRequests: HashMap<String, MutableList<Pair<ConnectedPeer, MutableList<Long>>>> = hashMapOf()
    private var socket: ServerSocket? = null
    private var port = 6881
    private val encoding = Charsets.UTF_8
    private val unloadedVal = "unloaded"
    private val charList: List<Char> = ('a'..'z') + ('A'..'Z') + ('0'..'9')
    private val randomString = (1..6)
        .map { _ -> kotlin.random.Random.nextInt(0, charList.size) }
        .map(charList::get)
        .joinToString("")
    private val IDsumHash = MessageDigest.getInstance("SHA-1").digest((315737809 + 313380164).toString().toByteArray())
        .map { i -> "%x".format(i) }
        .joinToString("")
        .take(6)
    private val peer_id = "-CS1000-$IDsumHash$randomString"

    /**
     * Load in the torrent metainfo file from [torrent]. The specification for these files can be found here:
     * [Metainfo File Structure](https://wiki.theory.org/index.php/BitTorrentSpecification#Metainfo_File_Structure).
     *
     * After loading a torrent, it will be available in the system, and queries on it will succeed.
     *
     * This is a *create* command.
     *
     * @throws IllegalArgumentException If [torrent] is not a valid metainfo file.
     * @throws IllegalStateException If the infohash of [torrent] is already loaded.
     * @return The infohash of the torrent, i.e., the SHA-1 of the `info` key of [torrent].
     */
    override fun load(torrent: ByteArray): CompletableFuture<String> {
        val value =
            Bencoding.DecodeObjectM(torrent)
                ?: return Future { throw IllegalArgumentException() } //throw must be in completablefuture
        val info_hash = Bencoding.infohash(torrent)
        return torrentStorage.getTorrentData(info_hash)
            .thenApply {
                if ((it != null) && (it.toString(Charsets.UTF_8) != unloadedVal)) throw IllegalStateException()
                torrentStorage.addTorrent(
                    info_hash,
                    Conversion.toByteArray(Bencoding.Announce(value)) as ByteArray
                )
            }.thenApply { info_hash }

    }

    /**
     * Remove the torrent identified by [infohash] from the system.
     *
     * This is a *delete* command.
     *
     * @throws IllegalArgumentException If [infohash] is not loaded.
     */
    override fun unload(infohash: String): CompletableFuture<Unit> {
        return torrentStorage.getTorrentData(infohash)
            .thenApply { it ?: throw IllegalArgumentException() }
            .thenApply {
                if (it.toString(encoding) == unloadedVal) throw IllegalArgumentException()
            }.thenCompose {
                val u1 = torrentStorage.removeTorrent(infohash, unloadedVal)
                val u2 = statStorage.updateStats(infohash, mapOf(unloadedVal to Scrape(0, 0, 0, null)))
                val u3 = peerStorage.addPeers(infohash, listOf(KnownPeer("", 0, unloadedVal)))
                Future.allAsList(listOf(u1, u2, u3))
            }.thenApply { Unit }
    }

    /**
     * Return the announce URLs for the loaded torrent identified by [infohash].
     *
     * See [BEP 12](http://bittorrent.org/beps/bep_0012.html) for more information. This method behaves as follows:
     * * If the "announce-list" key exists, it will be used as the source for announce URLs.
     * * If "announce-list" does not exist, "announce" will be used, and the URL it contains will be in tier 1.
     * * The announce URLs should *not* be shuffled.
     *
     * This is a *read* command.
     *
     * @throws IllegalArgumentException If [infohash] is not loaded.
     * @return Tier lists of announce URLs.
     */
    override fun announces(infohash: String): CompletableFuture<List<List<String>>> {

        return torrentStorage.getTorrentData(infohash)
            .thenApply { it ?: throw IllegalArgumentException() }
            .thenApply {
                if (it.toString(Charsets.UTF_8) == unloadedVal) throw IllegalArgumentException()
                Conversion.fromByteArray(it as ByteArray) as List<List<String>>
            }
    }

    /**
     * Send an "announce" HTTP request to a single tracker of the torrent identified by [infohash], and update the
     * internal state according to the response. The specification for these requests can be found here:
     * [Tracker Protocol](https://wiki.theory.org/index.php/BitTorrentSpecification#Tracker_HTTP.2FHTTPS_Protocol).
     *
     * If [event] is [TorrentEvent.STARTED], shuffle the announce-list before selecting a tracker (future calls to
     * [announces] should return the shuffled list). See [BEP 12](http://bittorrent.org/beps/bep_0012.html) for more
     * information on shuffling and selecting a tracker.
     *
     * [event], [uploaded], [downloaded], and [left] should be included in the tracker request.
     *
     * The "compact" parameter in the request should be set to "1", and the implementation should support both compact
     * and non-compact peer lists.
     *
     * Peer ID should be set to "-CS1000-{Student ID}{Random numbers}", where {Student ID} is the first 6 characters
     * from the hex-encoded SHA-1 hash of the student's ID numbers (i.e., `hex(sha1(student1id + student2id))`), and
     * {Random numbers} are 6 random characters in the range [0-9a-zA-Z] generated at instance creation.
     *
     * If the connection to the tracker failed or the tracker returned a failure reason, the next tracker in the list
     * will be contacted and the announce-list will be updated as per
     * [BEP 12](http://bittorrent.org/beps/bep_0012.html).
     * If the final tracker in the announce-list has failed, then a [TrackerException] will be thrown.
     *
     * This is an *update* command.
     *
     * @throws TrackerException If the tracker returned a "failure reason". The failure reason will be the exception
     * message.
     * @throws IllegalArgumentException If [infohash] is not loaded.
     * @return The interval in seconds that the client should wait before announcing again.
     */
    override fun announce(
        infohash: String,
        event: TorrentEvent,
        uploaded: Long,
        downloaded: Long,
        left: Long
    ): CompletableFuture<Int> {
        var peerList: List<KnownPeer> =
            (peerStorage.getPeers(infohash).get() ?: emptyList<KnownPeer>()) as List<KnownPeer>
        if (peerList.isNotEmpty())
            if (peerList[0] == KnownPeer("", 0, unloadedVal)) peerList = emptyList<KnownPeer>()
        var statsMap: Map<String, ScrapeData> = (statStorage.getStats(infohash).get()
            ?: emptyMap<String, ScrapeData>()) as Map<String, ScrapeData>
        if (statsMap.containsKey(unloadedVal)) statsMap = emptyMap<String, ScrapeData>()
        val request_params =
            createAnnounceRequestParams(infohash, event, uploaded, downloaded, left, peer_id, port.toString())
        var latest_failure: String = ""
        var good_announce: String = ""
        var interval: Int = 0
        var success = false
        return Future {
            val previousValue = torrentStorage.getTorrentData(infohash).get() ?: throw IllegalArgumentException()
            if (previousValue.toString() == unloadedVal) throw IllegalArgumentException()
        }.map {
            var announce_list = announces(infohash).get() as MutableList<MutableList<String>>
            if (event == TorrentEvent.STARTED)
                announce_list = announce_list.map { list -> list.shuffled() } as MutableList<MutableList<String>>
            for (announce_tier in announce_list) {
                for (announce_url in announce_tier) {
                    if (success) break
                    val data = httpRequest.httpGET(announce_url, request_params)
                    if (httpRequest.connectionSuccess == false) {
                        latest_failure = data as String
                    } else {
                        val announceResponse = Bencoding.DecodeObjectM(data as ByteArray)
                        if (announceResponse?.containsKey("failure reason")!!)
                            latest_failure = announceResponse["failure reason"] as String
                        else {
                            latest_failure = ""
                            good_announce = announce_url
                            val knownPeersList = peerList.toMutableList()
                            if (announceResponse["peers"] is List<*>) {//Bencoding.DecodeObject(peers) is Map<*,*>){
                                (announceResponse["peers"] as List<*>).forEach { peers ->
                                    val peerDict = (peers as Map<String, Any>)
                                    val peerIP = decodeIP(peerDict["ip"] as String?)
                                    val newPeer =
                                        KnownPeer(peerIP, peerDict["port"] as Int, peerDict["peer id"] as String?)
                                    knownPeersList.filter { !(it.ip == newPeer.ip && it.port == newPeer.port) }
                                    knownPeersList.add(KnownPeer(newPeer.ip, newPeer.port, newPeer.peerId))
                                }
                            } else {
                                val peers: ByteArray = announceResponse["peers"] as ByteArray
                                val segmentedPeerList = peers.asList().chunked(6)
                                for (portIP in segmentedPeerList) {
                                    val peerIP = InetAddress.getByAddress(portIP.take(4).toByteArray()).hostAddress
                                    val peerPort = ((portIP[4].toUByte().toInt() shl 8) + (portIP[5].toUByte().toInt()))
                                    knownPeersList.filter { !(it.ip == peerIP.toString() && it.port == peerPort) }
                                    knownPeersList.add(KnownPeer(peerIP.toString(), peerPort, null))
                                }

                            }
                            peerStorage.addPeers(infohash, knownPeersList)
                            interval = announceResponse["interval"] as Int
                            success = true
                            val scrapeData: ScrapeData = Scrape(
                                (announceResponse["complete"] ?: 0) as Int,
                                (announceResponse["downloaded"] ?: 0) as Int,
                                (announceResponse["incomplete"] ?: 0) as Int,
                                announceResponse["name"] as String?
                            ) as ScrapeData
                            val newMap =
                                statsMap.plus(Pair(announce_url.split("/").dropLast(1).joinToString("/"), scrapeData))
                            statStorage.updateStats(infohash, newMap)
                        }
                    }
                    if (success == true) continue
                }
                if (success) {
                    announce_tier.remove(good_announce)
                    announce_tier.add(0, good_announce)
                    break
                }
            }
            if (latest_failure != "")
                throw TrackerException(latest_failure)
            torrentStorage.updateAnnounceList(infohash, announce_list as List<List<String>>)
            interval
        }
    }

    private fun scrapeAux(
        statsMap: Map<String, ScrapeData>,
        infohash: String
    ): CompletableFuture<Unit> {
        var map = statsMap
        val encoding = "UTF-8"
        val requestParams = URLEncoder.encode("info_hash", encoding) + "=" + Bencoding.urlInfohash(infohash)
        return announces(infohash).thenApply { (announce_tier) ->
            for (announce_url in (announce_tier as List<String>)) {
                // for (announce_url in announce_tier) {
                val splitAnnounce = announce_url.split("/")
                val splitScrape =
                    splitAnnounce.dropLast(1) + Regex("^announce").replace(splitAnnounce.last(), "scrape")
                val scrapeUrl = splitScrape.joinToString("/")
                val urlName = splitAnnounce.dropLast(1).joinToString("/")
                val data = httpRequest.httpGET(scrapeUrl, requestParams)
                if (!httpRequest.connectionSuccess) {
                    map = map.filter { (k, _) -> k != urlName }
                    map = map.plus(Pair(urlName, Failure(reason = "Connection Failure")))
                } else {
                    val scrapeResponse =
                        Bencoding.DecodeObjectM(data as ByteArray) ?: throw IllegalArgumentException()
                    val statsDict = scrapeResponse["files"] as Map<*, *>
                    if (statsDict.isEmpty()) {
                        map = map.filter { (k, _) -> k != urlName }
                        map = map.plus(Pair(urlName, Failure(reason = "not specified")))
                    } else {
                        val statsValues = statsDict.values.toList()[0] as Map<*, *>
                        val scrapeData: ScrapeData = Scrape(
                            statsValues["complete"] as Int,
                            statsValues["downloaded"] as Int,
                            statsValues["incomplete"] as Int,
                            statsValues["name"] as String?
                        ) as ScrapeData
                        map = map.filter { (k, _) -> k != urlName }
                        map = map.plus(Pair(urlName, scrapeData))
                    }
                }
            }
            // }
        }.thenCompose { statStorage.updateStats(infohash, map) }

    }
//----------------------------------------//

    /**
     * Scrape all trackers identified by a torrent, and store the statistics provided. The specification for the scrape
     * request can be found here:
     * [Scrape Protocol](https://wiki.theory.org/index.php/BitTorrentSpecification#Tracker_.27scrape.27_Convention).
     *
     * All known trackers for the torrent will be scraped.
     *
     * This is an *update* command.
     *
     * @throws IllegalArgumentException If [infohash] is not loaded.
     */
    override fun scrape(infohash: String): CompletableFuture<Unit> {
        val newmap = statStorage.getStats(infohash).get()
        //var map = emptyMap<String, ScrapeData>()
        return torrentStorage.getTorrentData(infohash)
            .thenApply { it ?: throw IllegalArgumentException() }
            .thenApply {
                if (it.toString() == unloadedVal) throw IllegalArgumentException()
                if (newmap == null || (newmap as Map<String, ScrapeData>).containsKey(unloadedVal))
                    scrapeAux(emptyMap<String, ScrapeData>(), infohash).get()
                else

                    scrapeAux(newmap as Map<String, ScrapeData>, infohash).get()
            }

    }


    /**
     * Invalidate a previously known peer for this torrent.
     *
     * If [peer] is not a known peer for this torrent, do nothing.
     *
     * This is an *update* command.
     *
     * @throws IllegalArgumentException If [infohash] is not loaded.
     */
    override fun invalidatePeer(infohash: String, peer: KnownPeer): CompletableFuture<Unit> {
        var peerList: List<KnownPeer> =
            (peerStorage.getPeers(infohash).get() ?: emptyList<KnownPeer>()) as List<KnownPeer>
        if (peerList.isNotEmpty())
            if (peerList[0] == KnownPeer("", 0, unloadedVal))
                peerList = emptyList<KnownPeer>()
        return torrentStorage.getTorrentData(infohash)
            .thenApply { it ?: throw IllegalArgumentException() }
            .thenApply { if (it.toString() == unloadedVal) throw IllegalArgumentException() }
            .thenCompose {
                val newList = peerList.filter { !(it.ip == peer.ip && it.port == peer.port) }
                peerStorage.addPeers(infohash, newList)
            }
    }


    /**
     * Return all known peers for the torrent identified by [infohash], in sorted order. This list should contain all
     * the peers that the client can attempt to connect to, in ascending numerical order. Note that this is not the
     * lexicographical ordering of the string representation of the IP addresses: i.e., "127.0.0.2" should come before
     * "127.0.0.100".
     *
     * The list contains unique peers, and does not include peers that have been invalidated.
     *
     * This is a *read* command.
     *
     * @throws IllegalArgumentException If [infohash] is not loaded.
     * @return Sorted list of known peers.
     */
    override fun knownPeers(infohash: String): CompletableFuture<List<KnownPeer>> {
        return torrentStorage.getTorrentData(infohash)
            .thenApply { it ?: throw IllegalArgumentException() }
            .thenApply { if (it.toString() == unloadedVal) throw IllegalArgumentException() }
            .thenCompose { peerStorage.getPeers(infohash) }.thenApply { it ?: emptyList() }.thenApply {
                (it as List<KnownPeer>).sortedBy {
                    it.ip.split(".").asSequence().map { "%02x".format(it.toInt().toByte()) }.toList()
                        .joinToString(separator = "") { it }
                }
            }
    }

    /**
     * Return all known statistics from trackers of the torrent identified by [infohash]. The statistics displayed
     * represent the latest information seen from a tracker.
     *
     * The statistics are updated by [announce] and [scrape] calls. If a response from a tracker was never seen, it
     * will not be included in the result. If one of the values of [ScrapeData] was not included in any tracker response
     * (e.g., "downloaded"), it would be set to 0 (but if there was a previous result that did include that value, the
     * previous result would be shown).
     *
     * If the last response from the tracker was a failure, the failure reason would be returned ([ScrapeData] is
     * defined to allow for this). If the failure was a failed connection to the tracker, the reason should be set to
     * "Connection failed".
     *
     * This is a *read* command.
     *
     * @throws IllegalArgumentException If [infohash] is not loaded.
     * @return A mapping from tracker announce URL to statistics.
     */
    override fun trackerStats(infohash: String): CompletableFuture<Map<String, ScrapeData>> {

        return torrentStorage.getTorrentData(infohash)
            .thenApply { it ?: throw IllegalArgumentException() }
            .thenApply { if (it.toString() == unloadedVal) throw IllegalArgumentException() }
            .thenCompose { statStorage.getStats(infohash) }
            .thenApply { it ?: emptyMap() }.thenApply { it as Map<String, ScrapeData> }
    }


    /**
     * Return information about the torrent identified by [infohash]. These statistics represent the current state
     * of the client at the time of querying.
     *
     * See [TorrentStats] for more information about the required data.
     *
     * This is a *read* command.
     *
     * @throws IllegalArgumentException if [infohash] is not loaded.
     * @return Torrent statistics.
     */
    override fun torrentStats(infohash: String): CompletableFuture<TorrentStats> {
        return torrentStatStorage.getTorrentStats(infohash) as CompletableFuture<TorrentStats>
    }

    /**
     * Start listening for peer connections on a chosen port.
     *
     * The port chosen should be in the range 6881-6889, inclusive. Assume all ports in that range are free.
     *
     * For a given instance of [CourseTorrent], the port sent to the tracker in [announce] and the port chosen here
     * should be the same.
     *
     * This is a *update* command. (maybe)
     *
     * @throws IllegalStateException If already listening.
     */
    override fun start(): CompletableFuture<Unit> {
        return ImmediateFuture {
            if (socket != null) throw IllegalStateException()
            else try {
                socket = ServerSocket(port)
                println("connected")
            } catch (u: UnknownHostException) {
                //hmm
                println(u)
            } catch (i: IOException) {
                println(i)
            }
        }

    }

    /**
     * Disconnect from all connected peers, and stop listening for new peer connections
     *
     * You may assume that this method is called before the instance is destroyed, and perform clean-up here.
     *
     * This is an *update* command. (maybe)
     *
     * @throws IllegalStateException If not listening.
     */
    override fun stop(): CompletableFuture<Unit> {
        return ImmediateFuture {
            if (socket == null) throw IllegalStateException()
            else try {
                socket!!.close();

            } catch (i: IOException) {
                println(i)
            }
        }
    }

    /**
     * Connect to [peer] using the peer protocol described in [BEP 003](http://bittorrent.org/beps/bep_0003.html).
     * Only connections over TCP are supported. If connecting to the peer failed, an exception is thrown.
     *
     * After connecting, send a handshake message, and receive and process the peer's handshake message. The peer's
     * handshake will contain a "peer_id", and future calls to [knownPeers] should return this peer_id for this peer.
     *
     * If this torrent has anything downloaded, send a bitfield message.
     *
     * Wait 100ms, and in that time handle any bitfield or have messages that are received.
     *
     * In the handshake, the "reserved" field should be set to 0 and the peer_id should be the same as the one that was
     * sent to the tracker.
     *
     * [peer] is equal to (and has the same [hashCode]) an object that was returned by [knownPeers] for [infohash].
     *
     * After a successful connection, this peer should be listed by [connectedPeers]. Peer connections start as choked
     * and not interested for both this client and the peer.
     *
     * This is an *update* command. (maybe)
     *
     * @throws IllegalArgumentException if [infohash] is not loaded or [peer] is not known.
     * @throws PeerConnectException if the connection to [peer] failed (timeout, connection closed after handshake, etc.)
     */
    override fun connect(infohash: String, peer: KnownPeer): CompletableFuture<Unit> {
        return knownPeers(infohash)
            .map { knownPeers -> if (knownPeers.contains(peer).not()) throw IllegalArgumentException() }
            .map {
                val peerSocket = Socket(peer.ip, peer.port)
                val handshake =
                    WireProtocolEncoder.handshake(infohash.toByteArray(encoding), peer_id.toByteArray(encoding))
                peerSocket.getOutputStream().write(handshake)
                val response = peerSocket.getInputStream().readAllBytes()
                val decodedResponse: DecodedHandshake = WireProtocolDecoder.handshake(response)
                val peerWithID = KnownPeer(peer.ip, peer.port, decodedResponse.peerId.toString(encoding))
                val connectedPeer = ConnectedPeer(
                    peerWithID,
                    amChoking = true, amInterested = false, peerChoking = true, peerInterested = false,
                    completedPercentage = 0.0, averageSpeed = 0.0
                )//TODO what should these be set to
                if (torrentStats(infohash).get().downloaded > 0) {//TODO get rid of this .get?
                    val bitfield = byteArrayOf(0xFF.toByte())
                    val message = WireProtocolEncoder.encode(5.toByte(), bitfield)
                    peerSocket.getOutputStream().write(message)
                    Thread.sleep(100)
                    val bitfieldResponse = peerSocket.getInputStream().readAllBytes()
                    val decodedBitfieldResponse = WireProtocolDecoder.decode(bitfieldResponse, 0)
                    print(decodedBitfieldResponse)

                    //TODO: handle bitfield or have response
                }
                Pair(connectedPeer, peerSocket)
            }
            .recover {
                if (it is IllegalArgumentException) throw it
                else throw PeerConnectException(it.toString())
            }
            .zip(knownPeers(infohash)) { peerSocketPair, knownPeers ->
                val newList =
                    knownPeers.filter {
                        !(it.ip == peerSocketPair.first.knownPeer.ip &&
                                it.port == peerSocketPair.first.knownPeer.port)
                    }
                        .plus(peerSocketPair.first.knownPeer)
                peerStorage.addPeers(infohash, newList)
                connectedPeerStorage[infohash]?.add(peerSocketPair) //todo: should be safe, can remove ??
            }
    }

    /**
     * Disconnect from [peer] by closing the connection.
     *
     * There is no need to send any messages.
     *
     * This is an *update* command. (maybe)
     *
     * @throws IllegalArgumentException if [infohash] is not loaded or [peer] is not connected.
     */
    override fun disconnect(infohash: String, peer: KnownPeer): CompletableFuture<Unit> {
        return connectedPeers(infohash).thenApply { cPeers ->
            var peerConnected = false
            for (cPeer in cPeers) {
                if (cPeer.knownPeer == peer) {
                    peerConnected = true
                    //remove cPeer from connected peer
                    //TODO: close socket???
                }
            }
            if (peerConnected.not()) throw IllegalArgumentException()
        }

    }

    /**
     * Return a list of peers that this client is currently connected to, with some statistics.
     *
     * See [ConnectedPeer] for more information.
     *
     * This is a *read* command. (maybe)
     *
     * @throws IllegalArgumentException if [infohash] is not loaded.
     */
    override fun connectedPeers(infohash: String): CompletableFuture<List<ConnectedPeer>> {
        return torrentStorage.getTorrentData(infohash)
            .thenApply { it ?: throw IllegalArgumentException() }
            .thenApply { if (it.toString(encoding) == unloadedVal) throw IllegalArgumentException() }
            .thenApply { connectedPeerStorage[infohash] }//todo: update to new connectedpeer list
            //.thenApply { it ?: emptyList<ConnectedPeer>()}.thenAccept{it.second}
            .thenApply { emptyList() }
    }

    /**
     * Send a choke message to [peer], which is currently connected. Future calls to [connectedPeers] should show that
     * this peer is choked.
     *
     * This is an *update* command. (maybe)
     *
     * @throws IllegalArgumentException if [infohash] is not loaded or [peer] is not connected.
     */
    override fun choke(infohash: String, peer: KnownPeer): CompletableFuture<Unit> {
        return torrentStorage.getTorrentData(infohash)
            .thenApply { if ((it != null) && (it.toString(Charsets.UTF_8) != unloadedVal)) throw IllegalArgumentException() }
            .thenCompose {
                if (peer.peerId == null) throw IllegalArgumentException()
                var connectedPair =
                    (connectedPeerStorage[infohash]?.find { p -> p.first.knownPeer.equals(peer) })
                        ?: throw PeerConnectException("")    //TODO what message to pass to peerexception
                if (connectedPair != null) {
                    connectedPair.first.amChoking = false
                    val msg = WireProtocolEncoder.encode(1.toByte())
                    connectedPair.second.outputStream.write(msg)  //Sending unchoke message
                } else {
                    throw IllegalArgumentException()
                }
                ImmediateFuture { Unit }
            }

    }


    /**
     * Send an unchoke message to [peer], which is currently connected. Future calls to [connectedPeers] should show
     * that this peer is not choked.
     *
     * This is an *update* command. (maybe)
     *
     * @throws IllegalArgumentException if [infohash] is not loaded or [peer] is not connected.
     */
    override fun unchoke(infohash: String, peer: KnownPeer): CompletableFuture<Unit> {
        return torrentStorage.getTorrentData(infohash)
            .thenApply { if ((it != null) && (it.toString(Charsets.UTF_8) != unloadedVal)) throw IllegalArgumentException() }
            .thenCompose {
                if (peer.peerId == null) throw IllegalArgumentException()
                var connectedPair =
                    (connectedPeerStorage[infohash]?.find { p -> p.first.knownPeer.equals(peer) })
                        ?: throw PeerConnectException("")    //TODO what message to pass to peerexception
                if (connectedPair != null) {
                    connectedPair.first.amChoking = false
                    val msg = WireProtocolEncoder.encode(1.toByte())
                    connectedPair.second.outputStream.write(msg)  //Sending unchoke message
                } else {
                    throw IllegalArgumentException()
                }
                ImmediateFuture { Unit }
            }
    }


    /**
     * Handle any messages that peers have sent, and send keep-alives if needed, as well as interested/not interested
     * messages.
     *
     * Messages to receive and handle from peers:
     *
     * 1. keep-alive: Do nothing.
     * 2. unchoke: Mark this peer as not choking in future calls to [connectedPeers].
     * 3. choke: Mark this peer as choking in future calls to [connectedPeers].
     * 4. have: Update the internal state of which pieces this client has, as seen in future calls to [availablePieces]
     * and [connectedPeers].
     * 5. request: Mark the peer as requesting a piece, as seen in future calls to [requestedPieces]. Ignore if the peer
     * is choked.
     * 6. handshake: When a new peer connects and performs a handshake, future calls to [knownPeers] and
     * [connectedPeers] should return it.
     *
     * Messages to send to each peer:
     *
     * 1. keep-alive: If it has been more than one minute since we sent a keep-alive message (it is OK to keep a global
     * count)
     * 2. interested: If the peer has a piece we don't, and we're currently not interested, send this message and mark
     * the client as interested in future calls to [connectedPeers].
     * 3. not interested: If the peer does not have any pieces we don't, and we're currently interested, send this
     * message and mark the client as not interested in future calls to [connectedPeers].
     *
     * These messages can also be handled by different parts of the code, as desired. In that case this method can do
     * less, or even nothing. It is guaranteed that this method will be called reasonably often.
     *
     * This is an *update* command. (maybe)
     */
    override fun handleSmallMessages(): CompletableFuture<Unit> = TODO("implement function")
//    {
//        val msg = this.socket?.getInputStream()
//        val msgType = getMType(msg?.read())
//        when (msgType) {
//            PeerMsgType.CHOKE -> choke() //which peer to choke
//            PeerMsgType.UNCHOKE -> unchoke()//which peer to unchoke
//            PeerMsgType.HAVE -> //payload is a single number, the index which that downloader just completed and checked the hash of.
//                PeerMsgType.REQUEST
//            ->
//        }
//
//    }

    /**
     * Download piece number [pieceIndex] of the torrent identified by [infohash].
     *
     * Attempt to download a complete piece by sending a series of request messages and receiving piece messages in
     * response. This method finishes successfully (i.e., the [CompletableFuture] is completed) once an entire piece has
     * been received, or an error.
     *
     * Requests should be of piece subsets of length 16KB (2^14 bytes). If only a part of the piece is downloaded, an
     * exception is thrown. It is unspecified whether partial downloads are kept between two calls to requestPiece:
     * i.e., on failure, you can either keep the partially downloaded data or discard it.
     *
     * After a complete piece has been downloaded, its SHA-1 hash will be compared to the appropriate SHA-1 has from the
     * torrent meta-info file (see 'pieces' in the 'info' dictionary), and in case of a mis-match an exception is
     * thrown and the downloaded data is discarded.
     *
     * This is an *update* command.
     *
     * @throws PeerChokedException if the peer choked the client before a complete piece has been downloaded.
     * @throws PeerConnectException if the peer disconnected before a complete piece has been downloaded.
     * @throws PieceHashException if the piece SHA-1 hash does not match the hash from the meta-info file.
     * @throws IllegalArgumentException if [infohash] is not loaded, [peer] is not known, or [peer] does not have [pieceIndex].
     */
    override fun requestPiece(infohash: String, peer: KnownPeer, pieceIndex: Long): CompletableFuture<Unit> {

        return torrentStorage.getTorrentData(infohash)
            .thenApply {
                if ((it != null) && (it.toString(Charsets.UTF_8) != unloadedVal)) throw IllegalArgumentException()
            }
            .thenCompose {
                if (peer.peerId == null) throw IllegalArgumentException()
                var connectedpeer =
                    (connectedPeerStorage[infohash]?.find { p -> p.first.knownPeer.equals(peer) })
                        ?: throw PeerConnectException("")    //TODO what message to pass to peerexception
                if (connectedpeer != null) {
                    if (connectedpeer.first.peerChoking) throw PeerChokedException("") //TODO: not sure about this
                }
                //checked if the peer has the piece
                val peersPieces = peersPiecesStorage[infohash]?.find { it -> it.first.knownPeer.equals(peer) }?.second
                if (peersPieces != null && !(peersPieces.contains(pieceIndex))) throw IllegalArgumentException()
                /******now requesting if p******/
                val sockt = connectedpeer.second
                val pieceLen: Byte = torrentPieces.getPiecesLen(infohash).join()
                var len: Int = DEFAULT_REQUEST_SIZE
                var piece_offset: Int = 0
                while (len <= pieceLen.toInt()) {
                    val msg = WireProtocolEncoder.encode(
                        6.toByte(),
                        pieceIndex.toInt(),
                        piece_offset,
                        len
                    )  //TODO: check if pieceIndex.toInt()is ok
                    if (sockt.isClosed) throw PeerConnectException("") // check if peer disconnected
                    sockt.getOutputStream().write(msg)
                    // handleIncomingMessage(sockt, 13, 6, listOf(pieceIndex.toInt(), piece_offset, len), ByteArray(0))
                    piece_offset += len
                    if (len + DEFAULT_REQUEST_SIZE > pieceLen) len += pieceLen - DEFAULT_REQUEST_SIZE else len += DEFAULT_REQUEST_SIZE
                    val decodedMsg = WireProtocolDecoder.decode(sockt.getInputStream().readAllBytes(), 2)
                    handleIncomingMessage(
                        sockt,    ///TODO: check whether to send peer's socket or my socket
                        decodedMsg.length,
                        decodedMsg.messageId,
                        decodedMsg.ints,
                        decodedMsg.contents
                    )
                    //TODO: check if to call "handleSmallMsges" to get message pieces ??
                }
                //TODO: check if the hash is the same as the piece donwloaded
                //TODO: update torrentStats if the sha-1 hash matched
                ImmediateFuture { Unit }

            }

    }

    /**
     * Send piece number [pieceIndex] of the [infohash] torrent to [peer].
     *
     * Upload a complete piece (as much as possible) by sending a series of piece messages. This method finishes
     * successfully (i.e., the [CompletableFuture] is completed) if [peer] hasn't requested another subset of the piece
     * in 100ms.
     *
     * This is an *update* command. (maybe)
     *
     * @throws IllegalArgumentException if [infohash] is not loaded, [peer] is not known, or [peer] did not request [pieceIndex].
     */
    override fun sendPiece(infohash: String, peer: KnownPeer, pieceIndex: Long): CompletableFuture<Unit> {
        val time = currentTimeMillis()
        var duration: Long = 0
        return torrentStorage.getTorrentData(infohash)
            .thenApply {
                //check if peer requested pieceIndex
                val peersPieces = peersRequests[infohash]?.find { it-> it.first.knownPeer.equals(peer) }?.second
                if(peersPieces!=null && !(peersPieces.contains(pieceIndex))) throw IllegalArgumentException()
                if (it == null) throw IllegalArgumentException()
            }.thenCompose {
                peerStorage.getPeers(infohash).thenApply {
                    if (it != null) {
                        val peer = it.find { tpeer -> tpeer.equals(peer) }
                        if (peer == null) throw IllegalArgumentException()

                    }
                    val peer: Pair<ConnectedPeer, Socket> =
                        connectedPeerStorage[infohash]?.find { p -> p.equals(peer) } ?: throw IllegalArgumentException()
                    val pieceLen: Byte = torrentPieces.getPiecesLen(infohash).join()
                    var len: Int = DEFAULT_REQUEST_SIZE
                    var piece_offset: Int = 0
                    while (len <= pieceLen.toInt()) {
                        val decodedMsg = WireProtocolDecoder.decode(peer.second.getInputStream().readAllBytes(), 2)
                        //TODO: how to check the time
                        //TODO: check: do we send to handleIncomingMsg the whole piecelen or do we send chunks each time
                        if (peer.second.isClosed) throw PeerConnectException("")
                        handleIncomingMessage(
                            peer.second,    ///TODO: check whether to send peer's socket or my socket
                            decodedMsg.length,
                            decodedMsg.messageId,
                            decodedMsg.ints,
                            decodedMsg.contents
                        )
                        piece_offset += len  //TODO: check if offset is calculated correctly
                        if (len + DEFAULT_REQUEST_SIZE > pieceLen) len += pieceLen - DEFAULT_REQUEST_SIZE else len += DEFAULT_REQUEST_SIZE
                        //TOD: CHECK THIS "if [peer] hasn't requested another subset of the piece in 100ms."
                        duration = currentTimeMillis() - time
                        if (duration >= 100) {
                            // TODO:check if we recieved a "request" message from peer
                        }

                    }

                }
            }
    }


    /**
     * List pieces that are currently available for download immediately.
     *
     * That is, pieces that:
     * 1. We don't have yet,
     * 2. A peer we're connected to does have,
     * 3. That peer is not choking us.
     *
     * Returns a mapping from connected, unchoking, interesting peer to a list of maximum length [perPeer] of pieces
     * that meet the above criteria. The lists may overlap (contain the same piece indices). The pieces in the list
     * should begin at [startIndex] and continue sequentially in a cyclical manner up to `[startIndex]-1`.
     *
     * For example, there are 3 pieces, we don't have any of them, and we are connected to PeerA that has piece 1 and
     * 2 and is not choking us. So, `availablePieces(infohash, 3, 2) => {PeerA: [2, 1]}`.
     *
     * This is a *read* command. (maybe)
     *
     * @throws IllegalArgumentException if [infohash] is not loaded.
     * @return Mapping from peer to a list of [perPeer] pieces that can be downloaded from it, starting at [startIndex].
     */
    override fun availablePieces(
        infohash: String,
        perPeer: Long,
        startIndex: Long
    ): CompletableFuture<Map<KnownPeer, List<Long>>> = TODO("Implement me!")

    /**
     * List pieces that have been requested by (unchoked) peers.
     *
     * If a a peer sent us a request message for a subset of a piece (possibly more than one), that piece will be listed
     * here.
     *
     * @throws IllegalArgumentException if [infohash] is not loaded.
     * @return Mapping from peer to a list of unique pieces that it has requested.
     */
    override fun requestedPieces(
        infohash: String
    ): CompletableFuture<Map<KnownPeer, List<Long>>> = TODO("Implement me!")

    /**
     * Return the downloaded files for torrent [infohash].
     *
     * Partially downloaded files are allowed. Bytes that haven't been downloaded yet are zeroed.
     * File names are given including path separators, e.g., "foo/bar/file.txt".
     *
     * This is a *read* command.
     *
     * @throws IllegalArgumentException if [infohash] is not loaded.
     * @return Mapping from file name to file contents.
     */
    override fun files(infohash: String): CompletableFuture<Map<String, ByteArray>> = TODO("Implement me!")

    /**
     * Load files into the client.
     *
     * If [files] has extra files, they are ignored. If it is missing a file, it is treated as all zeroes. If file
     * contents are too short, the file is padded with zeroes. If the file contents are too long, they are truncated.
     *
     * @param files A mapping from filename to file contents.
     * @throws IllegalArgumentException if [infohash] is not loaded,
     */
    override fun loadFiles(infohash: String, files: Map<String, ByteArray>): CompletableFuture<Unit> =
        TODO("Implement me!")

    /**
     * Compare SHA-1 hash for the loaded pieces of torrent [infohash] against the meta-info file. If a piece fails hash
     * checking, it is zeroed and marked as not downloaded.
     *
     * @throws IllegalArgumentException if [infohash] is not loaded.
     * @return True if all the pieces have been downloaded and passed hash checking, false otherwise.
     */
    override fun recheck(infohash: String): CompletableFuture<Boolean> {
        return Future { false }

    }


    /*******************************Private Functions*****************************/

    private fun fromStringToInteger(Ip: String): Int {
        var num: Int = 0
        var itr = 0
        while (itr != Ip.length) {
            val char: Char = Ip[itr].toChar()
            num = (num * 10) + (char - '0')
            itr++

        }
        return num
    }

    private fun createAnnounceRequestParams(
        infohash: String,
        event: TorrentEvent,
        uploaded: Long,
        downloaded: Long,
        left: Long,
        peerID: String,
        port: String
    ): String {
        var request_params = URLEncoder.encode("info_hash", encoding) + "=" + Bencoding.urlInfohash(infohash)
        request_params += "&" + URLEncoder.encode("peer_id", encoding) + "=" + URLEncoder.encode(peerID, encoding)
        request_params += "&" + URLEncoder.encode("port", encoding) + "=" + URLEncoder.encode(port, encoding)
        request_params += "&" + URLEncoder.encode("uploaded", encoding) + "=" + URLEncoder.encode(
            uploaded.toString(),
            encoding
        )
        request_params += "&" + URLEncoder.encode(
            "downloaded",
            encoding
        ) + "=" + URLEncoder.encode(downloaded.toString(), encoding)
        request_params += "&" + URLEncoder.encode("left", encoding) + "=" + URLEncoder.encode(
            left.toString(),
            encoding
        )
        request_params += "&" + URLEncoder.encode("compact", encoding) + "=" + URLEncoder.encode("1", encoding)
        request_params += "&" + URLEncoder.encode("event", encoding) + "=" + URLEncoder.encode(
            event.toString(),
            encoding
        )
        return request_params
    }

    private fun decodeIP(ip: String?): String {
        if (ip == null) return "0.0.0.0"
        if (ip.toByteArray().size == 4) {
            return InetAddress.getByAddress(ip.toByteArray()).hostAddress
        } else {
            return ip
        }
    }

    private fun handleIncomingMessage(
        socket: Socket,
        length: Int,
        messageId: Byte,
        ints: List<Int>,
        contents: ByteArray
    ): Unit = TODO(" IMPLEMENT ME")
}
