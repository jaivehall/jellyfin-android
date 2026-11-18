package org.jellyfin.mobile.player.cast.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Xml
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Minimal UPnP/DLNA client: discovers MediaRenderer devices via SSDP and
 * controls them through the AVTransport SOAP service.
 *
 * Kept dependency-free (standard library + XmlPullParser) on purpose so it
 * works identically in both proprietary and libre flavors and adds no GMS
 * requirement — unlike Google Cast.
 */
object DlnaController {
    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val AV_TRANSPORT_TYPE = "urn:schemas-upnp-org:service:AVTransport:1"
    private const val MULTICAST_LOCK_TAG = "finplayer:dlna"

    private var multicastLock: WifiManager.MulticastLock? = null

    /** Discover MediaRenderers on the local network. Safe to call off the main thread. */
    suspend fun discover(context: Context): List<DlnaDevice> = withContext(Dispatchers.IO) {
        val results = LinkedHashMap<String, DlnaDevice>()
        acquireMulticastLock(context)
        val socket = runCatching { DatagramSocket().apply { soTimeout = 1000 } }.getOrNull()
            ?: return@withContext emptyList<DlnaDevice>().also { releaseMulticastLock() }

        try {
            val search = (
                "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 3\r\n" +
                    "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
                    "\r\n"
                ).toByteArray(StandardCharsets.US_ASCII)
            val packet = DatagramPacket(
                search,
                search.size,
                InetAddress.getByName(SSDP_ADDRESS),
                SSDP_PORT,
            )
            socket.send(packet)

            val deadline = System.currentTimeMillis() + 5000L
            val seenLocations = HashSet<String>()
            while (System.currentTimeMillis() < deadline) {
                val buffer = ByteArray(2048)
                val response = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(response)
                } catch (e: IOException) {
                    continue // socket timeout, keep listening until deadline
                }
                val text = String(response.data, 0, response.length, StandardCharsets.UTF_8)
                if (!text.startsWith("HTTP/1.1 200", ignoreCase = true)) continue
                val location = parseHeader(text, "LOCATION") ?: continue
                val normalized = location.trim()
                if (!seenLocations.add(normalized)) continue
                parseDeviceDescription(normalized)?.let { device ->
                    val key = device.udn.ifBlank { device.controlUrl }
                    results.putIfAbsent(key, device)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "DLNA discovery failed")
        } finally {
            socket.close()
            releaseMulticastLock()
        }
        results.values.toList()
    }

    private fun acquireMulticastLock(context: Context) {
        runCatching {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock(MULTICAST_LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { Timber.w(it, "Could not acquire multicast lock; discovery may be unreliable") }
    }

    private fun releaseMulticastLock() {
        runCatching { multicastLock?.release() }.onFailure { Timber.w(it, "Could not release multicast lock") }
        multicastLock = null
    }

    private fun parseHeader(response: String, name: String): String? =
        response.lineSequence()
            .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()

    private fun parseDeviceDescription(location: String): DlnaDevice? {
        val connection = runCatching { URL(location).openConnection() as HttpURLConnection }.getOrNull()
            ?: return null
        return try {
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            val xml = connection.inputStream.use { it.readBytes() }
            val parser = Xml.newPullParser().apply {
                setInput(ByteArrayInputStream(xml), null)
            }

            var friendlyName = ""
            var udn = ""
            var controlUrl: String? = null
            var inAvTransport = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name.lowercase(Locale.ROOT)) {
                        "friendlyname" -> friendlyName = parser.nextText().orEmpty()
                        "udn" -> udn = parser.nextText().orEmpty()
                        "servicetype" -> inAvTransport = parser.nextText().orEmpty().contains("AVTransport:1")
                        "controlurl" -> if (inAvTransport) controlUrl = parser.nextText().orEmpty()
                    }
                }
                event = parser.next()
            }
            val relative = controlUrl ?: return null
            DlnaDevice(
                udn = udn,
                friendlyName = friendlyName.ifBlank { "DLNA device" },
                controlUrl = resolveUrl(location, relative),
                baseUrl = location,
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse DLNA description at $location")
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun resolveUrl(base: String, reference: String): String {
        if (reference.startsWith("http://", true) || reference.startsWith("https://", true)) return reference
        val baseUrl = URL(base)
        val root = "${baseUrl.protocol}://${baseUrl.authority}"
        return if (reference.startsWith("/")) root + reference else "$root/$reference"
    }

    /** Push a video stream to the renderer and start playback. */
    suspend fun play(
        device: DlnaDevice,
        streamUrl: String,
        title: String,
        startPosition: Duration = Duration.ZERO,
        mimeType: String = "video/mp2t",
    ): Boolean = withContext(Dispatchers.IO) {
        val metadata = buildDidl(title, streamUrl, mimeType)
        val uriResult = soapAction(device, "SetAVTransportURI") {
            append("<InstanceID>0</InstanceID>")
            append("<CurrentURI>${escapeXml(streamUrl)}</CurrentURI>")
            append("<CurrentURIMetaData>${escapeXml(metadata)}</CurrentURIMetaData>")
        }
        if (!uriResult) return@withContext false
        val playResult = soapAction(device, "Play") {
            append("<InstanceID>0</InstanceID>")
            append("<Speed>1</Speed>")
        }
        // Many TVs (especially domestic DMRs) get stuck in a "receiving data" buffer
        // state when Seek is issued right after SetAVTransportURI, before playback
        // has actually started. Send it after Play instead, and tolerate failure so
        // the worst case is playing from the beginning rather than a stuck screen.
        if (playResult && startPosition > Duration.ZERO) {
            delay(800)
            runCatching {
                soapAction(device, "Seek") {
                    append("<InstanceID>0</InstanceID>")
                    append("<Unit>REL_TIME</Unit>")
                    append("<Target>${formatTime(startPosition)}</Target>")
                }
            }
        }
        playResult
    }

    suspend fun pause(device: DlnaDevice): Boolean = withContext(Dispatchers.IO) {
        soapAction(device, "Pause") { append("<InstanceID>0</InstanceID>") }
    }

    suspend fun resume(device: DlnaDevice): Boolean = withContext(Dispatchers.IO) {
        soapAction(device, "Play") {
            append("<InstanceID>0</InstanceID>")
            append("<Speed>1</Speed>")
        }
    }

    suspend fun stop(device: DlnaDevice): Boolean = withContext(Dispatchers.IO) {
        soapAction(device, "Stop") { append("<InstanceID>0</InstanceID>") }
    }

    suspend fun seek(device: DlnaDevice, position: Duration): Boolean = withContext(Dispatchers.IO) {
        soapAction(device, "Seek") {
            append("<InstanceID>0</InstanceID>")
            append("<Unit>REL_TIME</Unit>")
            append("<Target>${formatTime(position)}</Target>")
        }
    }

    /**
     * Current playback position reported by the renderer, or null if unavailable.
     * NOTE: this is <b>RelTime</b> (elapsed time), NOT TrackDuration — the latter
     * is the TOTAL length of the track; reading it made the local player seek to
     * the very end of the video when casting stopped.
     */
    suspend fun getPosition(device: DlnaDevice): Duration? = withContext(Dispatchers.IO) {
        val body = soapRequest("GetPositionInfo") { append("<InstanceID>0</InstanceID>") }
        val response = postSoap(device, body) ?: return@withContext null
        val value = Regex("<RelTime>([^<]*)</RelTime>", RegexOption.IGNORE_CASE)
            .find(response)?.groupValues?.get(1)
            ?: return@withContext null
        parseTime(value)
    }

    /**
     * Transport state reported by the renderer (PLAYING, PAUSED_PLAYBACK,
     * TRANSITIONING, STOPPED, NO_MEDIA_PRESENT…), or null when the renderer does
     * not implement GetTransportInfo. Used to tell "actually playing" apart from
     * "accepted the URI but stuck on retrieving content info".
     */
    suspend fun getTransportState(device: DlnaDevice): String? = withContext(Dispatchers.IO) {
        val body = soapRequest("GetTransportInfo") { append("<InstanceID>0</InstanceID>") }
        val response = postSoap(device, body) ?: return@withContext null
        Regex("<CurrentTransportState>([^<]*)</CurrentTransportState>", RegexOption.IGNORE_CASE)
            .find(response)?.groupValues?.get(1)?.trim()?.uppercase(Locale.ROOT)
    }

    private fun soapAction(device: DlnaDevice, action: String, block: StringBuilder.() -> Unit): Boolean {
        val body = soapRequest(action, block)
        return postSoap(device, body)?.let { response ->
            !response.contains("<s:Fault", ignoreCase = true) &&
                !response.contains("SOAP-ENV:Fault", ignoreCase = true)
        } ?: false
    }

    private fun soapRequest(action: String, block: StringBuilder.() -> Unit): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
        append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
        append("<s:Body>")
        append("<u:$action xmlns:u=\"$AV_TRANSPORT_TYPE\">")
        append(StringBuilder().apply(block).toString())
        append("</u:$action>")
        append("</s:Body>")
        append("</s:Envelope>")
    }

    private fun postSoap(device: DlnaDevice, body: String): String? {
        val connection = runCatching { URL(device.controlUrl).openConnection() as HttpURLConnection }.getOrNull()
            ?: return null
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 4000
            connection.readTimeout = 4000
            connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            connection.setRequestProperty("SOAPACTION", "\"$AV_TRANSPORT_TYPE#${body.actionFromBody()}\"")
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            stream?.use { String(it.readBytes(), StandardCharsets.UTF_8) }
        } catch (e: Exception) {
            Timber.e(e, "DLNA SOAP request to ${device.controlUrl} failed")
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun String.actionFromBody(): String =
        Regex("<u:([A-Za-z]+) ").find(this)?.groupValues?.get(1).orEmpty()

    private fun buildDidl(title: String, url: String, mimeType: String): String = buildString {
        append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
        append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
        append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">")
        append("<item id=\"0\" parentID=\"-1\" restricted=\"1\">")
        append("<dc:title>${escapeXml(title)}</dc:title>")
        append("<upnp:class>object.item.videoItem</upnp:class>")
        append("<res protocolInfo=\"http-get:*:${escapeXml(mimeType)}:*\">${escapeXml(url)}</res>")
        append("</item>")
        append("</DIDL-Lite>")
    }

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun formatTime(duration: Duration): String {
        val total = duration.inWholeSeconds.coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return "%02d:%02d:%02d".format(Locale.US, hours, minutes, seconds)
    }

    private fun parseTime(value: String): Duration? {
        val parts = value.split(":").mapNotNull { it.toDoubleOrNull() }
        if (parts.size != 3) return null
        val seconds = parts[0] * 3600 + parts[1] * 60 + parts[2]
        return seconds.seconds
    }
}
