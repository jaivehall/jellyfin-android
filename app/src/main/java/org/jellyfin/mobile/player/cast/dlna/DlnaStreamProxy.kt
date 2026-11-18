package org.jellyfin.mobile.player.cast.dlna

import android.content.Context
import android.net.wifi.WifiManager
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tiny local HTTP reverse proxy for DLNA casting.
 *
 * Many domestic TVs have a DLNA renderer that cannot fetch an HTTPS stream,
 * especially one on a non-standard port or signed with a newer CA chain. The
 * app itself CAN fetch it (system trust store is up to date), so we expose the
 * stream to the TV over plain HTTP on the LAN and forward the bytes upstream.
 *
 * Range requests are passed through so seeking on the TV keeps working.
 */
object DlnaStreamProxy {
    private const val BUFFER_SIZE = 64 * 1024
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    /**
     * Start the proxy and return the LAN URL the TV should fetch, or null on failure.
     * When [warmup] is true (transcoded sources), prime the upstream first so Jellyfin's
     * transcode is already producing data when the TV connects — otherwise the DMR
     * times out in the first seconds and shows "network unstable, retry later".
     */
    fun start(context: Context, upstreamUrl: String, warmup: Boolean = false): String? {
        stop()
        val hostIp = resolveLocalIp(context)
        if (hostIp == null) {
            Timber.w("DLNA proxy: no local LAN address found, falling back to direct URL")
            return null
        }
        return try {
            val socket = ServerSocket(0, 16, InetAddress.getByName("0.0.0.0"))
            serverSocket = socket
            running.set(true)
            val port = socket.localPort
            acceptThread = Thread({
                while (running.get()) {
                    try {
                        val client = socket.accept()
                        Thread({ handleClient(client, upstreamUrl) }, "dlna-proxy-client")
                            .apply { isDaemon = true; start() }
                    } catch (_: SocketException) {
                        break // server socket closed by stop()
                    } catch (e: Exception) {
                        if (running.get()) Timber.w(e, "DLNA proxy accept failed")
                    }
                }
            }, "dlna-proxy-accept").apply { isDaemon = true; start() }
            val url = "http://$hostIp:$port/stream"
            Timber.i("DLNA proxy listening at $url -> $upstreamUrl")
            if (warmup) warmUp(upstreamUrl)
            url
        } catch (e: Exception) {
            Timber.e(e, "DLNA proxy failed to start")
            stop()
            null
        }
    }

    /**
     * Prime a transcoded upstream stream by reading its first bytes. Jellyfin shares one
     * transcode session per playSessionId, so this starts ffmpeg and keeps the session
     * warm; when the TV connects moments later data flows immediately instead of the
     * DMR timing out on a cold transcode. Failure is non-fatal — the TV still gets the
     * URL and tries on its own.
     */
    private fun warmUp(upstreamUrl: String) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(upstreamUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Finplayer-DLNA-Proxy")
                setRequestProperty("Range", "bytes=0-")
            }
            val code = connection.responseCode
            if (code in 200..299) {
                val read = connection.inputStream.use { it.read(ByteArray(4096)) }
                Timber.i("DLNA proxy warm-up OK (HTTP $code, read $read bytes)")
            } else {
                Timber.w("DLNA proxy warm-up returned HTTP $code")
            }
        } catch (e: Exception) {
            Timber.w(e, "DLNA proxy warm-up failed (non-fatal)")
        } finally {
            connection?.disconnect()
        }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { acceptThread?.interrupt() }
        acceptThread = null
    }

    private fun handleClient(client: Socket, upstreamUrl: String) {
        try {
            client.soTimeout = 15_000
            val range = readRequestRange(client)
            val connection = URL(upstreamUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 0 // streaming: no read timeout
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Finplayer-DLNA-Proxy")
            if (!range.isNullOrBlank()) connection.setRequestProperty("Range", range)

            val code = connection.responseCode
            val out = BufferedOutputStream(client.getOutputStream(), BUFFER_SIZE)
            val statusText = when (code) {
                HttpURLConnection.HTTP_OK -> "OK"
                206 -> "Partial Content"
                416 -> "Requested Range Not Satisfiable"
                else -> connection.responseMessage.orEmpty()
            }
            out.write("HTTP/1.1 $code $statusText\r\n".toByteArray(StandardCharsets.US_ASCII))

            var sentContentType = false
            var sentAcceptRanges = false
            connection.headerFields.forEach { (name, values) ->
                if (name.isNullOrBlank() || values.isNullOrEmpty()) return@forEach
                if (!name.equals("Transfer-Encoding", ignoreCase = true) &&
                    !name.equals("Connection", ignoreCase = true) &&
                    !name.equals("Content-Length", ignoreCase = true)
                ) {
                    val value = values.firstOrNull().orEmpty()
                    out.write("$name: $value\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                    if (name.equals("Content-Type", ignoreCase = true)) sentContentType = true
                    if (name.equals("Accept-Ranges", ignoreCase = true)) sentAcceptRanges = true
                }
            }
            if (!sentContentType) out.write("Content-Type: video/mp4\r\n".toByteArray(StandardCharsets.US_ASCII))
            if (!sentAcceptRanges) out.write("Accept-Ranges: bytes\r\n".toByteArray(StandardCharsets.US_ASCII))
            out.write("Connection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            out.flush()

            if (code in 200..299) {
                connection.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        out.flush()
                    }
                }
            }
            out.flush()
        } catch (e: Exception) {
            Timber.w(e, "DLNA proxy client error")
        } finally {
            runCatching { client.close() }
        }
    }

    /** Read just the request headers (no buffering past them) and extract Range. */
    private fun readRequestRange(client: Socket): String? {
        val input = client.getInputStream()
        val header = ByteArrayOutputStream()
        var range: String? = null
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) break
            header.write(b)
            val ch = b.toChar()
            if (ch == '\r') continue
            if (ch == '\n') {
                val value = line.toString()
                if (value.isEmpty()) break // blank line => end of headers
                if (value.startsWith("Range:", ignoreCase = true)) {
                    range = value.substringAfter(':').trim()
                }
                line.setLength(0)
                continue
            }
            line.append(ch)
        }
        return range
    }

    @Suppress("DEPRECATION")
    private fun resolveLocalIp(context: Context): String? {
        runCatching {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val address = wifi?.connectionInfo?.ipAddress ?: 0
            if (address != 0) {
                val bytes = byteArrayOf(
                    (address and 0xFF).toByte(),
                    (address shr 8 and 0xFF).toByte(),
                    (address shr 16 and 0xFF).toByte(),
                    (address shr 24 and 0xFF).toByte(),
                )
                val ip = InetAddress.getByAddress(bytes).hostAddress
                if (!ip.isNullOrBlank() && ip != "0.0.0.0") return ip
            }
        }.onFailure { Timber.w(it, "DLNA proxy: Wi-Fi IP lookup failed") }

        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
