package il.ac.technion.cs.softwaredesign

import Storage.*
import Utils.*
import com.google.inject.Inject
import il.ac.technion.cs.softwaredesign.exceptions.PeerChokedException
import il.ac.technion.cs.softwaredesign.exceptions.PeerConnectException
import il.ac.technion.cs.softwaredesign.exceptions.PieceHashException
import il.ac.technion.cs.softwaredesign.exceptions.TrackerException
import io.github.vjames19.futures.jdk8.*
import java.io.IOException
import java.lang.Exception
import java.lang.System.currentTimeMillis
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Duration
import java.util.*
import java.util.concurrent.CompletableFuture
import kotlin.collections.HashMap
import kotlin.experimental.or
import kotlin.math.pow

const val DEFAULT_REQUEST_SIZE = 16384  // Default block size is 2^14 bytes, or 16kB.

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
    private val torrentStatStorage: TorrentStatistics,
    private val fileStorage: File,
    private val infoStorage: Info,
    private val bitfieldStorage: Bitfield,
    private val httpRequest: HTTPGet = HTTPGet()

) : CourseTorrent {
    private val connectedPeerStorage: HashMap<String, MutableList<Pair<ConnectedPeer, Socket>>> = hashMapOf()
    private val peerBitfields: HashMap<String, MutableList<Pair<KnownPeer, ByteArray>>> = hashMapOf()

    //triple: pieceIndex, subsetStart, Length
    private val peerRequestedBitfields: HashMap<String, MutableList<Pair<ConnectedPeer, MutableList<Triple<Int, Int, Int>>>>> =
        hashMapOf()
    private var startTime: Long? = null
    private var completionTime: Long? = null
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
        val infohash = Bencoding.infohash(torrent)
        return torrentStorage.getTorrentData(infohash)
            .thenCompose {
                if ((it != null) && (it.toString(Charsets.UTF_8) != unloadedVal)) throw IllegalStateException()
                torrentStorage.addTorrent(
                    infohash,
                    Conversion.toByteArray(Bencoding.Announce(value)) as ByteArray
                )
            }
            .thenCompose {
                val infoDict = Bencoding.Info(value)
                val files: HashMap<String, ByteArray> = hashMapOf()
                if (infoDict.length != null)
                    files[infoDict.name] = ByteArray(infoDict.length!!) { 0.toByte() }
                else {
                    for (file in infoDict.files!!)
                        files[file.path] = ByteArray(file.length) { 0.toByte() }
                }
                fileStorage.addFiles(infohash, files as Map<String, ByteArray>)
                torrentStatStorage.addTorrentStats(
                    infohash,
                    TorrentStats(0, 0, 0, 0, 0.0, 0, 0, Duration.ZERO, Duration.ZERO)
                )
                infoStorage.addInfo(infohash, infoDict)
            }
            //todo: initialize more things here. torrent Stats, bitfields, etc
            .thenApply { infohash }

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
        return torrentStatStorage.getTorrentStats(infohash)
            .thenApply { it ?: throw IllegalArgumentException() }
            .thenApply { if (it.toString() == unloadedVal) throw IllegalArgumentException() }
            .thenCompose { torrentStatStorage.getTorrentStats(infohash) as CompletableFuture<TorrentStats> }
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
            } finally {
                startTime = System.nanoTime()
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
                    WireProtocolEncoder.handshake(hexStringToByteArray(infohash), peer_id.toByteArray(encoding))
                peerSocket.getOutputStream().write(handshake)
                val response = peerSocket.getInputStream().readAllBytes()
                val decodedResponse: DecodedHandshake = WireProtocolDecoder.handshake(response)
                val peerWithID = KnownPeer(peer.ip, peer.port, decodedResponse.peerId.toString(encoding))
                val connectedPeer = ConnectedPeer(
                    peerWithID,
                    amChoking = true, amInterested = false, peerChoking = true, peerInterested = false,
                    completedPercentage = 0.0, averageSpeed = Double.POSITIVE_INFINITY
                )
                if (torrentStats(infohash).get().downloaded > 0) {
                    //TODO get rid of these .gets?
                    val bitfield = bitfieldStorage.getBitfield(infohash).get()
                    val message = WireProtocolEncoder.encode(5.toByte(), bitfield, 1)
                    peerSocket.getOutputStream().write(message)
                }
                Thread.sleep(100)
                val peerMessage = peerSocket.getInputStream().readAllBytes()
                handleIncomingMessage(peerSocket, peerWithID.peerId!!, infohash, peerMessage)
                Pair(connectedPeer, peerSocket)
            }
            .recover {
                if (it is IllegalArgumentException) throw it
                else throw PeerConnectException(it.toString())
            }
            .zip(knownPeers(infohash)) { peerSocketPair, knownPeers ->
                val newList =
                    knownPeers
                        .filter { !(it.equals(peerSocketPair.first.knownPeer)) }
                        .plus(peerSocketPair.first.knownPeer)
                peerStorage.addPeers(infohash, newList)
                if (connectedPeerStorage.containsKey(infohash))
                    connectedPeerStorage[infohash]!!.add(peerSocketPair)
                else
                    connectedPeerStorage[infohash] = mutableListOf(peerSocketPair)

                //todo add peers to bitfield, requested?
            }.thenApply { Unit }
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
                    connectedPeerStorage[infohash]?.find { it.first.knownPeer == peer }?.second?.close()
                    connectedPeerStorage[infohash]?.removeIf { it.first.knownPeer == peer }
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
            .thenApply { connectedPeerStorage[infohash]?.map { it.first } }
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
            .thenApply { it ?: throw IllegalArgumentException() }
            .thenApply { if ((it != null) && (it.toString(Charsets.UTF_8) != unloadedVal)) throw IllegalArgumentException() }
            .thenApply {
                if (peer.peerId == null) throw IllegalArgumentException()
                val connectedPair = (connectedPeerStorage[infohash]?.find { p -> p.first.knownPeer.equals(peer) })
                    ?: throw IllegalArgumentException()
                connectedPeerStorage[infohash]?.removeIf { it.first.knownPeer.equals(peer) }
                connectedPeerStorage[infohash]?.add(
                    Pair(
                        ConnectedPeer(
                            connectedPair.first.knownPeer
                            , true
                            , connectedPair.first.amInterested
                            , connectedPair.first.peerChoking
                            , connectedPair.first.peerInterested
                            , connectedPair.first.completedPercentage
                            , connectedPair.first.averageSpeed
                        )
                        , connectedPair.second
                    )
                )
                val msg = WireProtocolEncoder.encode(0.toByte())
                connectedPair.second.outputStream.write(msg)  //Sending unchoke message
            }.thenApply { Unit }

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
            .thenApply { it ?: throw IllegalArgumentException() }
            .thenApply { if (it.toString(Charsets.UTF_8) == unloadedVal) throw IllegalArgumentException() }
            .thenApply {
                if (peer.peerId == null) throw IllegalArgumentException() //TODO remove this?
                val connectedPair = (connectedPeerStorage[infohash]?.find { p -> p.first.knownPeer.equals(peer) })
                    ?: throw IllegalArgumentException()
                connectedPeerStorage[infohash]?.removeIf { it.first.knownPeer.equals(peer) }
                connectedPeerStorage[infohash]?.add(
                    Pair(
                        ConnectedPeer(
                            connectedPair.first.knownPeer
                            , true
                            , connectedPair.first.amInterested
                            , connectedPair.first.peerChoking
                            , connectedPair.first.peerInterested
                            , connectedPair.first.completedPercentage
                            , connectedPair.first.averageSpeed
                        )
                        , connectedPair.second
                    )
                )
                val msg = WireProtocolEncoder.encode(1.toByte())
                connectedPair.second.outputStream.write(msg)  //Sending unchoke message
            }.thenApply { Unit }

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
    override fun handleSmallMessages(): CompletableFuture<Unit> {
        return Future {

            val incomingSocket = socket?.accept()
          //  socket?.soTimeout = 10
            val handshake: ByteArray? = incomingSocket?.getInputStream()?.readBytes()
            if (handshake != null) {
                val decodedHandshake: DecodedHandshake = WireProtocolDecoder.handshake(handshake)
                incomingSocket.getOutputStream()
                    .write(WireProtocolEncoder.handshake(decodedHandshake.infohash, peer_id.toByteArray(encoding)))
                Thread.sleep(10) //TODO: is this needed?
                val actualMessage = incomingSocket.getInputStream().readAllBytes()
                val infohash = decodedHandshake.infohash.toString(encoding)
                val peerID = decodedHandshake.peerId.toString(encoding)
                //TODO: add peer to knownPeers and ConnectedPeers if unrecognized
                handleIncomingMessage(incomingSocket, infohash, peerID, actualMessage)
            }
        }


    }

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
        var pieceLen: Int = 0
        var downloadedLen = 0
        val recieved_piece: ByteArray = byteArrayOf()
        var updatedStats: TorrentStats = TorrentStats(0, 0, 0, 0, 0.0, 0, 0, Duration.ZERO, Duration.ZERO)
        var success = true
        return torrentStorage.isTorrentLoaded(infohash)
            .thenApply {
                if (it == false) throw IllegalArgumentException()
                if (peer.peerId == null) throw IllegalArgumentException()

            }
            .thenCompose { infoStorage.getInfo(infohash) }.thenApply { info ->
                var connectedpeer = (connectedPeerStorage[infohash]?.find { p -> p.first.knownPeer.equals(peer) })
                    ?: throw PeerConnectException("")
                if (connectedpeer.first.peerChoking) throw PeerChokedException("") //TODO: not sure about this
                //TODO:check if peer is intrested?
                val peersPieces =
                    peerRequestedBitfields[infohash]?.find { it -> it.first.knownPeer.equals(peer) }?.second?.find { it -> it.first == pieceIndex.toInt() }
                        ?: throw IllegalArgumentException()

                /******now requesting ******/
                val sockt = connectedpeer.second
                val infoDict = Conversion.fromByteArray(info) as InfoDictionary
                pieceLen = infoDict.pieceLength
                var len: Int = DEFAULT_REQUEST_SIZE
                var piece_offset = 0 //TODO: should be in loop, until we get all subsets of piece?
                while (len <= pieceLen) {
                    val msg = WireProtocolEncoder.encode(
                        6.toByte(),
                        pieceIndex.toInt(),
                        piece_offset,
                        len
                    )  //TODO: check if pieceIndex.toInt()is ok
                    if (sockt.isClosed) throw PeerConnectException("") // check if peer disconnected
                    sockt.getOutputStream().write(msg)
                    piece_offset += len
                    if (len + DEFAULT_REQUEST_SIZE > pieceLen) len += pieceLen - len else len += DEFAULT_REQUEST_SIZE
                    Thread.sleep(100)
                    if (connectedpeer.second.getInputStream().available() > 0) {
                        val peerMsg = connectedpeer.second.getInputStream().readAllBytes()
                        val bb = ByteBuffer.wrap(peerMsg)
                        bb.order(ByteOrder.BIG_ENDIAN)
                        bb.getInt()
                        val messageId = (bb).get()
                        if (messageId == 0.toByte()) throw PeerChokedException("") // todo: not sure about this
                        if (messageId != 7.toByte()) connectedpeer.first.knownPeer.peerId?.let {
                            handleIncomingMessage(
                                connectedpeer.second, infohash,
                                it, peerMsg
                            )
                        }
                        val decodedPeerMsg =
                            WireProtocolDecoder.decode(peerMsg, 2)
                        recieved_piece.plus(decodedPeerMsg.contents)
                        downloadedLen += recieved_piece.size
                    }

                }
                //check if the hash is the same as the piece donwloaded
                if (!checkPieceHash(infoDict.pieces, recieved_piece, pieceIndex.toInt())) success = false

            }.thenCompose {
                torrentStatStorage.getTorrentStats(infohash)
            }.thenApply {
                val oldStats = it as TorrentStats
                if (success) {
                    updatedStats =
                        TorrentStats(
                            oldStats.uploaded,
                            oldStats.downloaded + pieceLen.toLong(),
                            oldStats.left - pieceLen,
                            oldStats.wasted,
                            ((oldStats.uploaded).toDouble() / oldStats.downloaded + pieceLen.toLong()),
                            oldStats.pieces,
                            oldStats.havePieces + 1,
                            oldStats.leechTime,
                            oldStats.seedTime
                        )
                } else {
                    updatedStats =
                        TorrentStats(
                            oldStats.uploaded,
                            oldStats.downloaded,
                            oldStats.left,
                            oldStats.wasted + downloadedLen,
                            oldStats.shareRatio,
                            oldStats.pieces,
                            oldStats.havePieces,
                            oldStats.leechTime,
                            oldStats.seedTime
                        )
                }

            }.thenCompose {
                if (!success) throw PieceHashException("")
                else torrentStatStorage.addTorrentStats(infohash, updatedStats)
            }.thenCompose { fileStorage.getFiles(infohash) }.zip(fileStorage.getFiles(infohash)) { info, files ->
                //todo write the pieces to files
                val infoDict = info as InfoDictionary
                var downloadedData: ByteArray = byteArrayOf()
                if (infoDict.files == null) {
                    downloadedData = files[infoDict.name]!!
                } else {
                    for (file in infoDict.files!!) {
                        files[file.path]?.let { downloadedData.plus(it) }
                    }
                }
                var indx = (pieceIndex * pieceLen).toInt()
                var pieceOffset = 0
                while (pieceOffset != pieceLen) {
                    downloadedData[indx] = recieved_piece.get(pieceOffset)
                    indx++
                    pieceOffset++
                }
                val newfilesMap: Map<String, ByteArray> = mapOf()
                indx = 0
                if (infoDict.files == null) {
                    newfilesMap.plus(Pair(files[infoDict.name]!!, files))
                } else {
                    for (file in infoDict.files!!) {
                        val fileLen = (files[file.path]?.size) ?: 0
                        newfilesMap.plus(
                            Pair(files[file.path]!!, downloadedData.copyOfRange(indx, fileLen))
                        )
                        indx += fileLen + 1

                    }
                }
                (fileStorage.addFiles(infohash, newfilesMap))
            }.thenApply { Unit }
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
        var pieceLen: Int = 0
        var updatedStats: TorrentStats = TorrentStats(0, 0, 0, 0, 0.0, 0, 0, Duration.ZERO, Duration.ZERO)
        var duration: Long = 0
        return torrentStorage.isTorrentLoaded(infohash)
            .thenApply {
                if (it == false) throw IllegalArgumentException()
                val peersPieces =
                    peerRequestedBitfields[infohash]?.find { it -> it.first.knownPeer.equals(peer) }?.second
                if (peersPieces != null && (peersPieces.find { it -> it.first == pieceIndex.toInt() }) != null) throw IllegalArgumentException()
            }.thenCompose {
                peerStorage.getPeers(infohash)
            }.thenApply {
                if (it != null) {
                    val peer =
                        it.find { tpeer -> (tpeer as KnownPeer).equals(peer) } ?: throw IllegalArgumentException()
                }
            }.thenCompose { infoStorage.getInfo(infohash) }.zip(fileStorage.getFiles(infohash)) { info, files ->
                val infoDict = Conversion.fromByteArray(info) as InfoDictionary
                val peer: Pair<ConnectedPeer, Socket> =
                    connectedPeerStorage[infohash]?.find { p -> (p.first.knownPeer.equals(peer)) }
                        ?: throw IllegalArgumentException()
                /// get the piece from the files//
                pieceLen = infoDict.pieceLength
                var downloadedData: ByteArray = byteArrayOf()
                if (infoDict.files == null) {
                    downloadedData = files[infoDict.name]!!
                } else {
                    for (file in infoDict.files!!) {
                        files[file.path]?.let { downloadedData.plus(it) }
                    }
                }
                val index = pieceIndex * pieceLen
                val piece: ByteArray = downloadedData.copyOfRange(
                    index.toInt(),
                    index.toInt() + pieceLen
                )//TODO: again check if pieceIndex.toInt() is OK
                //////////////////////////////////
                var len: Int = DEFAULT_REQUEST_SIZE
                var piece_offset: Int = 0

                while (len <= pieceLen.toInt()) {
                    val msg = WireProtocolEncoder.encode(
                        7.toByte(),
                        piece.copyOfRange(piece_offset, len),
                        pieceIndex.toInt(),
                        piece_offset
                    )
                    peer.second.getOutputStream().write(msg)
                    // if (peer.second.isClosed) throw PeerConnectException("") //TODO: is this how we check if peer disconnected while we transfer blocks
                    peer.second.getOutputStream().write(msg)
                    piece_offset += len  //TODO: check if offset is calculated correctly
                    if (len + DEFAULT_REQUEST_SIZE > pieceLen) len += (pieceLen - len) else len += DEFAULT_REQUEST_SIZE
                    //checking if a request message recieved in 100ms
                    duration = currentTimeMillis() - time
                    if (duration <= 100) {
                        // TODO:check if we recieved a "request" message from peer
                        if (peer.second.getInputStream().available() > 0) {

                            val peerMsgBytes = peer.second.getInputStream().readAllBytes()
                            val bb = ByteBuffer.wrap(peerMsgBytes)
                            bb.order(ByteOrder.BIG_ENDIAN)
                            bb.getInt()
                            val messageId = (bb).get()
                            if (messageId == 6.toByte()) {
                                throw IllegalArgumentException()  ///todo how to make completablefuture incomplete
                            }

                            peer.first.knownPeer.peerId?.let {
                                handleIncomingMessage(
                                    peer.second, infohash,
                                    it, peerMsgBytes
                                )
                            }

                        }
                    }
                }
            }.thenCompose { torrentStatStorage.getTorrentStats(infohash) }.thenApply {
                val oldStats = it as TorrentStats
                updatedStats =
                    TorrentStats(
                        oldStats.uploaded + pieceLen,
                        oldStats.downloaded,
                        oldStats.left,
                        oldStats.wasted,
                        (oldStats.uploaded + pieceLen).toDouble() / (oldStats.downloaded),
                        oldStats.pieces,
                        oldStats.havePieces,
                        oldStats.leechTime,
                        oldStats.seedTime
                    )
            }.thenCompose {
                torrentStatStorage.addTorrentStats(infohash, updatedStats)
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
    ): CompletableFuture<Map<KnownPeer, List<Long>>> {
        return torrentStorage.getTorrentData(infohash)
            .thenApply { it ?: throw IllegalArgumentException() }
            .thenApply { if (it.toString() == unloadedVal) throw IllegalArgumentException() }
            .thenCompose { bitfieldStorage.getBitfield(infohash) }
            .thenApply { bitfield ->
                //todo am interested? keep, remove?
                var downloadedPieces: List<Long> = emptyList()
                for (byte in bitfield) {
                    val bitList: List<Long> =
                        byte.toUByte().toString(2).padStart(8, '0').toList().map { it.toString().toLong() }
                    downloadedPieces = downloadedPieces.plus(bitList)
                }
                downloadedPieces
            }
            .thenApply { downloadedPieces ->
                val nonChokingPeers =
                    connectedPeerStorage[infohash]?.filter { !it.first.peerChoking && it.first.amInterested }
                        ?.map { pair -> pair.first.knownPeer }
                val availablePieces: MutableMap<KnownPeer, List<Long>> = mutableMapOf()
                if (nonChokingPeers != null) {
                    nonChokingPeers.asSequence().forEach { peer ->
                        var peersDownloadedPieces: List<Long> = emptyList()
                        for (byte in peerBitfields[infohash]!!.find { it.first.equals(peer) }!!.second) {
                            val bitList: List<Long> =
                                byte.toUByte().toString(2).padStart(8, '0').toList().map { it.toString().toLong() }
                            peersDownloadedPieces = peersDownloadedPieces.plus(bitList)
                        }
                        peersDownloadedPieces.mapIndexed { index, pieceDownloaded ->
                            if (downloadedPieces[index] == 0.toLong())
                                pieceDownloaded
                            else
                                0.toLong()
                        }
                        availablePieces[peer] = peersDownloadedPieces
                    }
                }
                availablePieces as Map<KnownPeer, List<Long>>
            }
    }


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
    ): CompletableFuture<Map<KnownPeer, List<Long>>> {
        return torrentStorage.getTorrentData(infohash)
            .thenApply { it ?: throw IllegalArgumentException() }
            .thenApply { if (it.toString() == unloadedVal) throw IllegalArgumentException() }
            .thenApply {
                peerRequestedBitfields[infohash]
                    ?.filter { !it.first.amChoking }
                    ?.map { peerPiecePair ->
                        Pair(
                            peerPiecePair.first.knownPeer,
                            peerPiecePair.second.map { triple -> triple.first.toLong() }.distinct()
                        )
                    }?.toMap()
            }

    }

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
    override fun files(infohash: String): CompletableFuture<Map<String, ByteArray>> {
        return torrentStorage.getTorrentData(infohash)
            .thenApply { it ?: throw IllegalArgumentException() }
            .thenApply { if (it.toString() == unloadedVal) throw IllegalArgumentException() }
            .thenCompose { fileStorage.getFiles(infohash) }

    }

    /**
     * Load files into the client.
     *
     * If [files] has extra files, they are ignored. If it is missing a file, it is treated as all zeroes. If file
     * contents are too short, the file is padded with zeroes. If the file contents are too long, they are truncated.
     *
     * @param files A mapping from filename to file contents.
     * @throws IllegalArgumentException if [infohash] is not loaded,
     */
    override fun loadFiles(infohash: String, files: Map<String, ByteArray>): CompletableFuture<Unit> {
        return torrentStorage.getTorrentData(infohash)
            .thenApply { it ?: throw IllegalArgumentException() }
            .thenApply { if (it.toString() == unloadedVal) throw IllegalArgumentException() }
            .thenCompose { infoStorage.getInfo(infohash) }
            .thenApply { info ->
                val infoDict = Conversion.fromByteArray(info) as InfoDictionary
                val adjustedFiles: HashMap<String, ByteArray> = hashMapOf()
                if (infoDict.files == null) {  //single file case
                    adjustedFiles[infoDict.name] = adjustFile(infoDict.name, infoDict.length!!, files)
                } else {
                    for (multiFile in infoDict.files!!) {
                        adjustedFiles[multiFile.path] = adjustFile(multiFile.path, multiFile.length, files)
                    }
                }
                fileStorage.addFiles(infohash, adjustedFiles.toMap())

            }.thenApply { Unit }
    }

    private fun adjustFile(filename: String, realLength: Int, files: Map<String, ByteArray>): ByteArray {
        if (files.containsKey(filename)) {
            val fileLength = files[filename]!!.size
            if (fileLength < realLength) {
                return files[filename]!!.plus(ByteArray(realLength - fileLength) { 0.toByte() })
            } else {
                return files[filename]!!.take(realLength).toByteArray()
            }
        }
        return ByteArray(realLength) { 0.toByte() }
    }

    /**
     * Compare SHA-1 hash for the loaded pieces of torrent [infohash] against the meta-info file. If a piece fails hash
     * checking, it is zeroed and marked as not downloaded.
     *
     * @throws IllegalArgumentException if [infohash] is not loaded.
     * @return True if all the pieces have been downloaded and passed hash checking, false otherwise.
     */
    override fun recheck(infohash: String): CompletableFuture<Boolean> {
        return torrentStorage.getTorrentData(infohash)
            .thenApply { it ?: throw IllegalArgumentException() }
            .thenApply { if (it.toString() == unloadedVal) throw IllegalArgumentException() }
            .thenCompose { fileStorage.getFiles(infohash) }
            .zip(infoStorage.getInfo(infohash)) { files, info ->
                val infoDict = Conversion.fromByteArray(info) as InfoDictionary
                val pieceLength = infoDict.pieceLength
                val pieces = infoDict.pieces
                var downloadedData: ByteArray = byteArrayOf()
                var fileLength = 0
                if (infoDict.files == null) {  //single file case
                    fileLength = infoDict.length!!
                    downloadedData = files[infoDict.name] ?: throw Exception("this should not happen")
                } else {
                    //combine files into one ByteArray
                    for (file in infoDict.files!!) {
                        fileLength += file.length
                        downloadedData = downloadedData.plus(
                            files[file.path] ?: ByteArray(file.length) { 0.toByte() }
                        )
                    }
                }
                checkPiecesHash(pieces, downloadedData, fileLength, pieceLength)
            }
    }


    /*******************************Private Functions*****************************/


    private fun calculateDownloadedPieces(bitfield: ByteArray): Int {
        return 0
    }

    private fun checkPiecesHash(hashes: ByteArray, file: ByteArray, fileLength: Int, pieceLen: Int): Boolean {
        for (index in (0 until fileLength step pieceLen)) {
            val pieceHash: ByteArray =
                hashes.toList().chunked(20)[index].toByteArray() //todo what if pieces not multiple of 20
            val calculatedHash =
                MessageDigest.getInstance("SHA-1").digest(file.toList().chunked(pieceLen)[index].toByteArray())
            if (!pieceHash.contentEquals(calculatedHash))
                return false
        }
        return true
    }

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
        request_params += "&" + URLEncoder.encode("left", encoding) + "=" + URLEncoder.encode(left.toString(), encoding)
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

    /*
    * Use this to handle incoming messages after the handshake
    * handles messages of types:
    * keep-alive, choke, unchoke, interested, uninterested, have, bitfield, request
    * */
    private fun handleIncomingMessage(socket: Socket, infohash: String, peerID: String, message: ByteArray): Unit {
        val length = WireProtocolDecoder.length(message)
        if (length == 0) return //keep-alive, do nothing

        val messageId = WireProtocolDecoder.messageId(message).toInt()
        val numOfInts = when (messageId) {
            0, 1, 2, 3, 5 -> 1
            4 -> 2 //todo: verify
            6 -> 4
            else -> return
        }
        val decodedMessage = WireProtocolDecoder.decode(message, numOfInts)
        when (getMType(messageId)) {
            PeerMsgType.CHOKE -> {//do choke
                val peer: Pair<ConnectedPeer, Socket> =
                    connectedPeerStorage[infohash]?.find { it.first.knownPeer.peerId == peerID }
                        ?: throw Exception()
                //TODO what exception
                connectedPeerStorage[infohash]?.removeIf { it.first.knownPeer.peerId == peerID }
                connectedPeerStorage[infohash]?.add(
                    Pair(
                        ConnectedPeer(
                            peer.first.knownPeer
                            , peer.first.amChoking
                            , peer.first.amInterested
                            , true
                            , peer.first.peerInterested
                            , peer.first.completedPercentage
                            , peer.first.averageSpeed
                        )
                        , peer.second
                    )
                )
            }
            PeerMsgType.UNCHOKE -> {
                val peer: Pair<ConnectedPeer, Socket> =
                    connectedPeerStorage[infohash]?.find { it.first.knownPeer.peerId == peerID }
                        ?: throw Exception()
                //TODO what exception
                connectedPeerStorage[infohash]?.removeIf { it.first.knownPeer.peerId == peerID }
                connectedPeerStorage[infohash]?.add(
                    Pair(
                        ConnectedPeer(
                            peer.first.knownPeer
                            , peer.first.amChoking
                            , peer.first.amInterested
                            , false
                            , peer.first.peerInterested
                            , peer.first.completedPercentage
                            , peer.first.averageSpeed
                        )
                        , peer.second
                    )
                )
            }
            PeerMsgType.INTERESTED -> {
                val peer: Pair<ConnectedPeer, Socket> =
                    connectedPeerStorage[infohash]?.find { it.first.knownPeer.peerId == peerID }
                        ?: throw Exception()
                //TODO what exception
                connectedPeerStorage[infohash]?.removeIf { it.first.knownPeer.peerId == peerID }
                connectedPeerStorage[infohash]?.add(
                    Pair(
                        ConnectedPeer(
                            peer.first.knownPeer
                            , peer.first.amChoking
                            , peer.first.amInterested
                            , peer.first.peerChoking
                            , true
                            , peer.first.completedPercentage
                            , peer.first.averageSpeed
                        )
                        , peer.second
                    )
                )
            }
            PeerMsgType.NOT_INTERESTED -> {
                val peer: Pair<ConnectedPeer, Socket> =
                    connectedPeerStorage[infohash]?.find { it.first.knownPeer.peerId == peerID }
                        ?: throw Exception()
                //TODO what exception
                connectedPeerStorage[infohash]?.removeIf { it.first.knownPeer.peerId == peerID }
                connectedPeerStorage[infohash]?.add(
                    Pair(
                        ConnectedPeer(
                            peer.first.knownPeer
                            , peer.first.amChoking
                            , peer.first.amInterested
                            , peer.first.peerChoking
                            , false
                            , peer.first.completedPercentage
                            , peer.first.averageSpeed
                        )
                        , peer.second
                    )
                )
            }
            PeerMsgType.HAVE -> {
                val pieceIndex = decodedMessage.ints[1] //second int should be piece index
                val byteIndex = pieceIndex / 8 //Byte containing the bit representing the piece
                val bitIndex = pieceIndex % 8 //Bit representing the piece within the byte
                val bitfield = peerBitfields[infohash]?.find { it.first.peerId == peerID }?.second
                    ?: throw Exception()
                val newByte: Byte = bitfield[byteIndex] or (2.0.pow(bitIndex).toInt().toByte())
                bitfield[byteIndex] = newByte //todo verify this changes the peerBitfield
                //todo update stats
            }
            PeerMsgType.BITFIELD -> {
                val bitfield: ByteArray = decodedMessage.contents
                peerBitfields[infohash]?.replaceAll {
                    if (it.first.peerId == peerID) Pair(it.first, decodedMessage.contents)
                    else it
                    //todo update stats
                }
            }
            PeerMsgType.REQUEST -> {
                val pieceIndex = decodedMessage.ints[1]
                val beginWithinPiece = decodedMessage.ints[2]
                val blockLength = decodedMessage.ints[3]
                val peer: Pair<ConnectedPeer, Socket> =
                    connectedPeerStorage[infohash]?.find { it.first.knownPeer.peerId == peerID }
                        ?: throw Exception()
                if (peer.first.amChoking) return
                peerRequestedBitfields[infohash]?.find { it.first.knownPeer.equals(peer) }?.second?.add(
                    Triple(
                        pieceIndex,
                        beginWithinPiece,
                        blockLength
                    )
                )
            }
            else -> throw IllegalStateException() //TODO: don't throw?
        }
    }

    private fun hexStringToByteArray(input: String) =
        input.chunked(2).map { it.toUpperCase().toInt(16).toByte() }.toByteArray()

    private fun checkPieceHash(
        pieces: ByteArray,
        recievedPiece: ByteArray,
        pieceIndex: Int
    ): Boolean {
        val pieceHash: ByteArray = pieces.toList().chunked(20)[pieceIndex].toByteArray()
        val recievedPieceHashed = MessageDigest.getInstance("SHA-1").digest(recievedPiece)
        return pieceHash.contentEquals(recievedPieceHashed)

    }
}