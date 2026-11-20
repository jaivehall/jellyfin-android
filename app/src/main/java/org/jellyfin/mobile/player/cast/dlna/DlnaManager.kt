package org.jellyfin.mobile.player.cast.dlna

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.player.source.JellyfinMediaSource
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.api.VideoRange
import org.jellyfin.sdk.model.api.VideoRangeType
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.time.Duration

/**
 * Orchestrates DLNA casting for the current playback session.
 *
 * Holds the selected renderer and builds an authenticated stream URL the TV fetches
 * directly from Jellyfin (fire-and-forget: the phone can lock or exit). Jellyfin
 * accepts the api_key query parameter for authentication.
 *
 * Compatibility strategy
 * ----------------------
 * DLNA renderers vary wildly. Field reports from an old Android TV (a DMR that
 * cannot run the official Jellyfin TV app) that could fetch some items but hung
 * forever on "retrieving content info" for others, showed the pattern:
 *
 * - MP4 / H.264 / single AAC audio               -> plays (direct stream)
 * - MP4 / H.264 / AAC but TWO audio tracks       -> hangs (index at end of file
 *   after remuxing, ambiguous audio selection)
 * - MKV / H.264 / E-AC3 5.1                      -> hangs (Matroska over DLNA is
 *   rarely implemented on old renderers)
 * - Transcoded MPEG-TS / H.264 / AAC             -> video plays, NO audio
 *   (AAC inside MPEG-TS is badly demuxed by those chipsets)
 *
 * So instead of guessing one URL per item, [startCasting] walks a LADDER of
 * profiles, cheapest/best first, and only keeps the one that actually starts:
 *
 *   1. direct     – original file, no server work (only DMR-friendly containers
 *                   with a single audio track)
 *   2. remux-ts   – stream-copy video into MPEG-TS (free on the server), which
 *                   fixes Matroska and end-of-file indexes and leaves exactly one
 *                   audio track
 *   3. h264-aac6  – real transcode to H.264, audio passthrough list (5.1 DTS/Dolby
 *                   kept as-is for TVs that decode it)
 *   4. h264-ac3   – H.264 + Dolby Digital stereo: the audio fallback for chipsets
 *                   that play AAC-in-TS without sound
 *   5. h264-aac2  – H.264 + AAC stereo, most conservative video profile
 *   6. remux-ts / direct – last resorts (e.g. 4K HDR sources the server cannot
 *                   re-encode, but the TV decodes natively)
 *
 * "Actually starts" is verified over UPnP (GetTransportInfo + GetPositionInfo):
 * a renderer that accepts the SOAP calls but stays at 00:00:00 is exactly the
 * "retrieving content info" hang, and triggers the next profile automatically.
 */
class DlnaManager(
    private val context: Context,
    private val apiClient: ApiClient,
) {
    private var currentDevice: DlnaDevice? = null

    val isCasting: Boolean get() = currentDevice != null
    val deviceName: String get() = currentDevice?.friendlyName.orEmpty()

    private var currentStreamUrl: String = ""
    private var currentTitle: String = ""
    private var currentSource: JellyfinMediaSource? = null
    private var startPosition: Duration = Duration.ZERO
    private var playing: Boolean = false

    val isPlaying: Boolean get() = playing
    val title: String get() = currentTitle

    /** Profile currently in use, e.g. "h264-ac3-ts". Handy in bug reports/logs. */
    var activeProfile: String = ""
        private set

    /** One stream attempt: URL, MIME type and whether the stream needs priming. */
    private class Candidate(
        val label: String,
        val url: String,
        val mimeType: String,
        val warmUp: Boolean,
    )

    private companion object {
        /** How long the renderer is watched before the attempt is declared failed. */
        const val FIRST_VERIFY_WINDOW_MS = 15_000L
        const val RETRY_VERIFY_WINDOW_MS = 9_000L

        /** Renderers that answer neither GetTransportInfo nor GetPositionInfo. */
        const val TELEMETRY_GRACE_MS = 5_000L
    }

    suspend fun discover(): List<DlnaDevice> = DlnaController.discover(context)

    private fun withApiKey(url: String): String {
        val token = apiClient.accessToken
        return when {
            token.isNullOrBlank() -> url
            url.contains("api_key=", ignoreCase = true) -> url
            url.contains('?') -> "$url&api_key=$token"
            else -> "$url?api_key=$token"
        }
    }

    /**
     * Transcoding URL for sources the TV's DLNA renderer cannot decode directly
     * (HEVC/H.265/AV1/VP9, 10-bit, HDR). The video is transcoded to H.264; the
     * audio uses a codec LIST, verified empirically against the server:
     *
     * - videoCodec=h264 with NO audioCodec (pure audio passthrough) makes the
     *   server return HTTP 500 "Error processing request" — the TV then receives
     *   an error page instead of a stream and shows "network unstable, retry".
     * - The remaining 500s on this endpoint are TRANSIENT: they occur on the
     *   first request while the server cold-starts the transcoder for the item
     *   and vanish on an immediate retry with the identical URL (verified with
     *   repeated probes). The warm-up therefore retries before degrading.
     * - audioCodec="aac,ac3,eac3,dts" + maxAudioChannels=6 keeps 5.1 DTS/Dolby/
     *   AC3 stream-copied into TS for the TV to decode natively; only 7.1+
     *   sources get downmixed.
     *
     * All URL parameters, no server config change.
     */
    private fun buildTranscodeUrl(source: JellyfinMediaSource, startTimeTicks: Long, playSessionId: String): String {
        val base = apiClient.videosApi.getVideoStreamUrl(
            itemId = source.itemId,
            static = false,
            container = "ts",
            videoCodec = "h264",
            audioCodec = "aac,ac3,eac3,dts",
            maxAudioChannels = 6,
            enableAutoStreamCopy = true,
            allowVideoStreamCopy = true,
            allowAudioStreamCopy = true,
            audioStreamIndex = source.selectedAudioStreamIndex,
            playSessionId = playSessionId,
            startTimeTicks = startTimeTicks,
            mediaSourceId = source.id,
            deviceId = apiClient.deviceInfo.id,
        )
        return withApiKey(base)
    }

    /**
     * H.264 + Dolby Digital stereo. Forced audio ENCODE (allowAudioStreamCopy =
     * false) so an AAC source is really re-encoded instead of staying AAC:
     * renderers whose MPEG-TS demuxer mishandles AAC-ADTS play the video silently,
     * while AC3 in TS is decoded by practically every DMR-era chipset.
     */
    private fun buildAc3TranscodeUrl(source: JellyfinMediaSource, startTimeTicks: Long, playSessionId: String): String {
        val base = apiClient.videosApi.getVideoStreamUrl(
            itemId = source.itemId,
            static = false,
            container = "ts",
            videoCodec = "h264",
            audioCodec = "ac3",
            maxAudioChannels = 2,
            enableAutoStreamCopy = true,
            allowVideoStreamCopy = true,
            allowAudioStreamCopy = false,
            audioStreamIndex = source.selectedAudioStreamIndex,
            playSessionId = playSessionId,
            startTimeTicks = startTimeTicks,
            mediaSourceId = source.id,
            deviceId = apiClient.deviceInfo.id,
        )
        return withApiKey(base)
    }

    /**
     * Simplest, maximally compatible profile (H.264 + AAC stereo) used when the
     * server rejects the multichannel one — verified HTTP 200 on every item tested
     * so far. Costs a downmix, but a playable stereo stream beats a server error
     * page any day.
     */
    private fun buildFallbackTranscodeUrl(source: JellyfinMediaSource, startTimeTicks: Long, playSessionId: String): String {
        val base = apiClient.videosApi.getVideoStreamUrl(
            itemId = source.itemId,
            static = false,
            container = "ts",
            videoCodec = "h264",
            audioCodec = "aac",
            maxAudioChannels = 2,
            enableAutoStreamCopy = true,
            allowVideoStreamCopy = true,
            allowAudioStreamCopy = true,
            audioStreamIndex = source.selectedAudioStreamIndex,
            playSessionId = playSessionId,
            startTimeTicks = startTimeTicks,
            mediaSourceId = source.id,
            deviceId = apiClient.deviceInfo.id,
        )
        return withApiKey(base)
    }

    /**
     * Remux (stream-copy) URL: keeps the ORIGINAL video bitstream (e.g. 4K HEVC
     * 10-bit HDR) untouched and copies it into an MPEG-TS container. Costs the
     * server almost nothing — no re-encode — and TS is the native DLNA
     * container, so the TV only needs its (already proven) HEVC decoder.
     *
     * Also the fix for perfectly decodable H.264 sources that still hang: an MKV
     * is not castable as-is on most renderers, and an MP4 whose moov atom sits at
     * the END of the file (typical after adding a second audio track) cannot be
     * parsed by a renderer that streams instead of downloading.
     */
    private fun buildRemuxUrl(source: JellyfinMediaSource, startTimeTicks: Long, playSessionId: String): String {
        val videoCodec = source.selectedVideoStream?.codec ?: "h264"
        val sourceAudio = source.selectedAudioStream?.codec?.lowercase(Locale.ROOT).orEmpty()
        // Old DMRs' MPEG-TS demuxer cannot decode AAC-ADTS: the video plays but
        // there is NO sound (verified 2026-11-19: "Eight Immortals" via remux-ts
        // had picture but silence). A stream-copied AC3 track is fine, but AAC /
        // EAC3 / DTS inside TS must be re-encoded to AC3. verifyStarted() cannot
        // detect silence (it only sees transport state + position), so this must
        // be fixed at the URL level, not by degrading to a later profile.
        // Keep true 5.1 only when the source audio is already AC3; otherwise
        // transcode to AC3 stereo. The VIDEO is still stream-copied (full quality)
        // and audioStreamIndex still selects the user's chosen (e.g. Chinese) track.
        val keepAudioCopy = sourceAudio == "ac3"
        val base = apiClient.videosApi.getVideoStreamUrl(
            itemId = source.itemId,
            static = false,
            container = "ts",
            videoCodec = videoCodec,
            audioCodec = "ac3",
            maxAudioChannels = if (keepAudioCopy) 6 else 2,
            enableAutoStreamCopy = true,
            allowVideoStreamCopy = true,
            allowAudioStreamCopy = keepAudioCopy,
            audioStreamIndex = source.selectedAudioStreamIndex,
            playSessionId = playSessionId,
            startTimeTicks = startTimeTicks,
            mediaSourceId = source.id,
            deviceId = apiClient.deviceInfo.id,
        )
        return withApiKey(base)
    }

    /** Direct URL (original file, no server transcoding) for decodable sources. */
    private fun buildDirectUrl(source: JellyfinMediaSource, startTimeTicks: Long, playSessionId: String): String {
        val base = apiClient.videosApi.getVideoStreamUrl(
            itemId = source.itemId,
            static = true,
            playSessionId = playSessionId,
            startTimeTicks = startTimeTicks,
            mediaSourceId = source.id,
            deviceId = apiClient.deviceInfo.id,
        )
        return withApiKey(base)
    }

    /**
     * True only when the TV's DMR likely cannot decode the source as-is.
     * H.264/AVC 8-bit SDR plays directly; HEVC/10-bit/HDR/AV1/VP9 need transcoding.
     */
    private fun needsTranscode(source: JellyfinMediaSource): Boolean {
        val video = source.selectedVideoStream ?: return true // unknown -> be safe
        val codec = video.codec?.lowercase(Locale.ROOT).orEmpty()
        if (codec !in setOf("h264", "avc")) return true
        if ((video.bitDepth ?: 8) > 8) return true
        if (video.videoRangeType != VideoRangeType.SDR && video.videoRangeType != VideoRangeType.UNKNOWN) return true
        if (video.videoRange == VideoRange.HDR) return true
        return false
    }

    private fun directMimeType(source: JellyfinMediaSource): String =
        when (source.sourceInfo.container?.lowercase(Locale.ROOT)) {
            "mkv" -> "video/x-matroska"
            "ts", "m2ts", "mpegts" -> "video/mp2t"
            "avi" -> "video/x-msvideo"
            "webm" -> "video/webm"
            else -> "video/mp4"
        }

    /**
     * Whether the ORIGINAL file is likely playable by a DLNA renderer as-is.
     * Old DMRs cannot parse Matroska at all, and choke on MP4/MKV files with
     * multiple audio tracks (moov/index at end of file, ambiguous selection).
     * Field evidence 2026-11-18: "Ocean Song" is MKV + HEVC + 3 audio tracks;
     * direct (static=true) returned a valid 4.94GB Matroska stream but the TV
     * sat in TRANSITIONING until it gave up with "unsupported source", which
     * then poisoned every later profile in the ladder.
     *
     * For such sources we must NOT try direct first: a failed direct attempt
     * leaves the DMR in an error state that dooms the remux/transcode fallbacks.
     */
    private fun isDirectFriendly(source: JellyfinMediaSource): Boolean {
        val container = source.sourceInfo.container?.lowercase(Locale.ROOT).orEmpty()
        if (container !in setOf("mp4", "mov", "m4v", "ts", "m2ts", "mpegts", "mpg", "mpeg")) return false
        if (source.audioStreams.size > 1) return false
        return true
    }

    /**
     * Ordered list of stream profiles to try for this item, best quality and
     * cheapest server-side first. See the class doc for the rationale.
     */
    private fun buildCandidates(
        source: JellyfinMediaSource,
        position: Duration,
        sessionIdOverride: String? = null,
        serverSeekOnly: Boolean = false,
    ): List<Candidate> {
        val playSessionId = sessionIdOverride ?: source.playSessionId
        val transcodeNeeded = needsTranscode(source)
        // Seek on the SERVER via the stream URL, never via a SOAP Seek after Play.
        // Domestic DMRs that receive Seek right after SetAVTransportURI frequently
        // freeze on "retrieving content info" with the position stuck at the seek
        // target (observed 2026-11-17: pos frozen at 2911000ms). Server-side seek
        // sends the TV a stream that already starts at the right offset.
        val startTimeTicks = position.inWholeMilliseconds * 10_000L
        val candidates = ArrayList<Candidate>(5)

        val directFriendly = isDirectFriendly(source)
        val directCandidates = ArrayList<Candidate>(2)
        // A direct (static=true) URL is the ORIGINAL file. Jellyfin IGNORES
        // audioStreamIndex on static=true (verified 2026-11-18: asking for
        // static=true&audioStreamIndex=3 still returned all 3 original tracks,
        // English first). So the TV picks its own track — usually the FIRST one,
        // which is often English even when the user selected Chinese on the phone.
        // Direct is therefore only audio-correct for SINGLE-audio files. Never
        // offer it for multi-audio sources: falling back to it would silently
        // reset the user's chosen track. remux/transcode profiles below all carry
        // audioStreamIndex and DO preserve the selection (verified: remux with
        // audioStreamIndex=2 returned exactly one AC3 5.1 Chinese track).
        // static=true (direct) ignores startTimeTicks: Jellyfin serves the
        // original file from 0 (verified 2026-11-19: direct seek showed pos=0
        // and timed out). For a server-side seek we must exclude it entirely.
        if (!serverSeekOnly && source.audioStreams.size <= 1) {
            directCandidates += Candidate("direct", buildDirectUrl(source, startTimeTicks, playSessionId), directMimeType(source), warmUp = false)
            // Some DMRs accept a direct stream but hang in TRANSITIONING when the
            // URL carries a non-zero start offset; replaying from 0 is free and
            // usually starts immediately.
            if (position > Duration.ZERO) {
                directCandidates += Candidate("direct-restart", buildDirectUrl(source, 0L, playSessionId), directMimeType(source), warmUp = false)
            }
        }

        // remux-ts = server stream-copies the ORIGINAL video bitstream into
        // MPEG-TS (zero re-encode, FULL quality, native DLNA container). It is
        // the best fallback for MKV/multi-audio/HEVC sources and costs nothing.
        val remux = Candidate("remux-ts", buildRemuxUrl(source, startTimeTicks, playSessionId), "video/mp2t", warmUp = true)
        // Real H.264 transcodes are the last resort: they cost server CPU and
        // lower picture quality, but work on renderers with no HEVC decoder.
        val transcodes = listOf(
            Candidate("h264-aac6-ts", buildTranscodeUrl(source, startTimeTicks, playSessionId), "video/mp2t", warmUp = true),
            Candidate("h264-ac3-ts", buildAc3TranscodeUrl(source, startTimeTicks, playSessionId), "video/mp2t", warmUp = true),
            Candidate("h264-aac2-ts", buildFallbackTranscodeUrl(source, startTimeTicks, playSessionId), "video/mp2t", warmUp = true),
        )

        if (directFriendly) {
            // DMR-friendly original (MP4/TS, single audio): straight direct first.
            candidates += directCandidates
            candidates += remux
            candidates += transcodes
        } else if (transcodeNeeded) {
            // MKV / multi-audio / HEVC-HDR: a direct attempt on the raw file
            // leaves old DMRs stuck and triggers "unsupported source" (2026-11-18).
            // Remux FIRST (full quality, free), real transcodes next, and keep
            // direct only as a last resort for modern renderers that do decode it.
            candidates += remux
            candidates += transcodes
            candidates += directCandidates
        } else {
            // H.264 in a non-friendly container: remux fixes the container at
            // full quality; direct only afterwards.
            candidates += remux
            candidates += directCandidates
            candidates += transcodes
        }
        return candidates
    }

    /**
     * Cast [source] to [device], walking the compatibility ladder until a profile
     * actually starts playing. [onAttempt] reports (attempt, total) so the UI can
     * explain why it takes a while on stubborn renderers.
     */
    suspend fun startCasting(
        device: DlnaDevice,
        source: JellyfinMediaSource,
        position: Duration,
        title: String,
        sessionIdOverride: String? = null,
        serverSeekOnly: Boolean = false,
        onAttempt: ((attempt: Int, total: Int) -> Unit)? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val candidates = buildCandidates(source, position, sessionIdOverride, serverSeekOnly)
        for (index in candidates.indices) {
            val candidate = candidates[index]
            if (index > 0) {
                // Do NOT send AVTransport.Stop between profiles. Domestic DMRs
                // (verified on an older Android TV, 2026-11-17) treat Stop as the
                // end of the whole cast session: they tear down the renderer
                // service and the TV exits the cast UI, after which every later
                // profile fails with ConnectException to the DMR port. A conforming
                // renderer replaces the current URI when SetAVTransportURI is sent
                // in PLAYING state, so just pause briefly to let it settle.
                delay(800)
            }
            onAttempt?.invoke(index + 1, candidates.size)

            // Prime transcoded/remuxed streams so Jellyfin's session is already
            // producing data when the TV connects (cold transcodes time out on DMRs).
            if (candidate.warmUp && !warmUp(candidate)) continue

            Timber.d("DLNA cast attempt ${index + 1}/${candidates.size}: ${candidate.label} -> ${candidate.url}")
            if (!DlnaController.play(device, candidate.url, title, mimeType = candidate.mimeType)) {
                Timber.w("Renderer rejected SetAVTransportURI/Play for ${candidate.label}")
                continue
            }
            val windowMs = if (index == 0) FIRST_VERIFY_WINDOW_MS else RETRY_VERIFY_WINDOW_MS
            if (!verifyStarted(device, windowMs)) {
                Timber.w("Renderer accepted ${candidate.label} but never started playback; degrading profile")
                continue
            }

            currentDevice = device
            currentStreamUrl = candidate.url
            currentTitle = title
            currentSource = source
            startPosition = position
            activeProfile = candidate.label
            playing = true
            Timber.i("DLNA cast started with profile ${candidate.label}")
            return@withContext true
        }
        Timber.e("DLNA cast failed: no compatible stream profile for this renderer")
        false
    }

    /**
     * Probe the stream, retrying the SAME profile twice: a first-request HTTP 500
     * is usually a transient transcoder cold-start failure that disappears on an
     * immediate retry, so degrading right away would throw away the best profile.
     */
    private suspend fun warmUp(candidate: Candidate): Boolean {
        var result = warmUpStream(candidate.url)
        var attempts = 0
        while (!result.isHealthy && attempts < 2) {
            attempts++
            Timber.w("Warm-up unhealthy for ${candidate.label} (HTTP ${result.code}); retry $attempts/2")
            delay(1_000)
            result = warmUpStream(candidate.url)
        }
        if (!result.isHealthy) {
            Timber.w("Profile ${candidate.label} unusable (HTTP ${result.code}); trying the next one")
        }
        return result.isHealthy
    }

    /**
     * Did the renderer really start playing (not just accept the SOAP calls)?
     *
     * A DMR stuck on "retrieving content info" frequently reports a FROZEN
     * RelTime — either 00:00:00, or (after our Seek) the seek target such as
     * 00:00:02. So a single sample merely reading >= 1s is NOT proof of playback:
     * 2026-11-17 "Ocean Song" (MKV/HEVC 10-bit, 3 audio tracks) reported exactly
     * 1922000ms once and the old check declared success, while the TV sat on
     * "retrieving content info" forever. We now require the position to actually
     * ADVANCE from the first sample; a frozen non-zero position is treated as a
     * hang so the ladder degrades to the next profile.
     *
     * Renderers that expose no telemetry at all are trusted after a short grace
     * period (nothing to verify, and blocking the user would be worse).
     */
    private suspend fun verifyStarted(device: DlnaDevice, windowMs: Long): Boolean {
        val start = System.currentTimeMillis()
        var deadline = start + windowMs
        var sawPlaying = false
        var sawPosition = false
        var sawTelemetry = false
        var stoppedStreak = 0
        var playingStreak = 0    // consecutive samples reporting PLAYING
        var extended = false
        var lastState: String? = null
        var baseline = -1L      // first position sample; progress is measured from here
        var bestPosition = -1L  // furthest position observed so far
        var stallSamples = 0    // consecutive samples with no forward progress

        while (System.currentTimeMillis() < deadline) {
            delay(1_200)

            lastState = DlnaController.getTransportState(device)
            when (lastState) {
                null -> Unit
                "PLAYING" -> {
                    sawTelemetry = true
                    sawPlaying = true
                    stoppedStreak = 0
                    playingStreak++
                }
                "STOPPED", "NO_MEDIA_PRESENT" -> {
                    sawTelemetry = true
                    stoppedStreak++
                    playingStreak = 0
                }
                else -> {
                    sawTelemetry = true
                    stoppedStreak = 0
                    playingStreak = 0
                }
            }

            val positionMs = DlnaController.getPosition(device)?.inWholeMilliseconds
            if (positionMs != null) {
                sawTelemetry = true
                sawPosition = true
                if (baseline < 0) baseline = positionMs
                if (positionMs > bestPosition) {
                    bestPosition = positionMs
                    stallSamples = 0
                } else {
                    stallSamples++
                }
                Timber.d("Renderer pos=${positionMs}ms state=$lastState advanced=${bestPosition - baseline}ms stalls=$stallSamples playingStreak=$playingStreak")

                // Real playback: the reported timeline advanced at least 1s.
                if (bestPosition >= baseline + 1_000) {
                    Timber.d("Renderer is playing (position ${positionMs}ms, advanced ${bestPosition - baseline}ms)")
                    return true
                }
                // Frozen at a non-zero value (seek target / "retrieving content info"):
                // three unchanged samples (~3.6s) mean this URL is stuck.
                if (stallSamples >= 3 && bestPosition > 0) {
                    Timber.w("Renderer position frozen at ${positionMs}ms (state=$lastState); degrading profile")
                    return false
                }
                // Stuck at zero while still TRANSITIONING ("retrieving content
                // info"): a DMR that cannot start this URL never leaves this
                // state. Fail fast (~5s). Do NOT extend this grace: on 2026-11-18
                // a 7s wait on an unsupported MKV let the TV hit its own timeout
                // and show "unsupported source", poisoning every later profile.
                if (stallSamples >= 4 && baseline == 0L && lastState == "TRANSITIONING") {
                    Timber.w("Renderer stuck at 0ms in TRANSITIONING; degrading profile")
                    return false
                }
            }

            // Sustained PLAYING with a ZERO position is a renderer whose location
            // telemetry is broken/absent, NOT a hang: a genuine "retrieving content
            // info" stall reports TRANSITIONING and never settles on PLAYING.
            // Restrict this to bestPosition <= 0 so the frozen-at-seek-target case
            // (bestPosition > 0, 2026-11-17 "Ocean Song") still fails below.
            // Fix for 2026-11-18: all profiles reached PLAYING with pos=0 and were
            // wrongly degraded, restarting the picture on every attempt.
            if (playingStreak >= 3 && bestPosition <= 0L) {
                Timber.d("Renderer reports PLAYING continuously with no position telemetry; treating as started")
                return true
            }

            // Renderer says PLAYING but has no position info: nothing else to check.
            if (sawPlaying && !sawPosition && System.currentTimeMillis() - start >= 4_000) return true
            // Two consecutive STOPPED/NO_MEDIA_PRESENT answers: it gave up on this URL.
            if (stoppedStreak >= 2) return false
            // Renderer without any UPnP telemetry (no transport state, no position):
            // trust the SOAP result rather than waiting out the whole window.
            if (!sawTelemetry && System.currentTimeMillis() - start >= TELEMETRY_GRACE_MS) return true
            // Slow start with no position info yet: allow one grace extension.
            if (!extended && sawPlaying && !sawPosition && System.currentTimeMillis() >= deadline - 2_000) {
                extended = true
                deadline += 8_000
            }
        }
        Timber.w("Renderer never advanced within ${windowMs}ms (state=$lastState, baseline=${baseline}ms, best=${bestPosition}ms)")
        return false
    }

    /**
     * Result of a stream warm-up probe: the HTTP status code (null on connection
     * error) plus how many bytes of actual stream data were read. A probe is
     * healthy only when it is 2xx AND data bytes arrived: the server answers
     * 200 with an EMPTY body when its transcoder cannot produce output for the
     * item (observed on heavy 4K HDR sources), and the TV then fails exactly
     * as if it had received an error page. Both must be treated as failure.
     */
    private class WarmUpResult(val code: Int?, val bytes: Int) {
        val isHealthy: Boolean
            get() = code != null && code in 200..299 && bytes > 0
    }

    /**
     * Touch the stream once so a transcoding session is started and producing data
     * before the TV connects. Jellyfin shares one transcode session per playSessionId,
     * so the TV's later request reuses the same session. Read the first bytes, then
     * drop the connection. Mirrors a DMR's plain GET (no Range header: the endpoint
     * misbehaves on ranged transcode requests); failures are non-fatal.
     */
    private fun warmUpStream(url: String): WarmUpResult {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Finplayer")
            }
            val code = connection.responseCode
            if (code in 200..299) {
                val read = connection.inputStream.use { it.read(ByteArray(4096)) }
                if (read > 0) {
                    Timber.i("DLNA stream warm-up OK (HTTP $code, read $read bytes)")
                } else {
                    Timber.w("DLNA stream warm-up returned HTTP $code with an EMPTY body")
                }
                WarmUpResult(code, maxOf(read, 0))
            } else {
                Timber.w("DLNA stream warm-up returned HTTP $code")
                WarmUpResult(code, 0)
            }
        } catch (e: Exception) {
            Timber.w(e, "DLNA stream warm-up failed (non-fatal)")
            WarmUpResult(null, 0)
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun pause(): Boolean {
        val device = currentDevice ?: return false
        val ok = DlnaController.pause(device)
        if (ok) playing = false
        return ok
    }

    suspend fun resume(): Boolean {
        val device = currentDevice ?: return false
        val ok = DlnaController.resume(device)
        if (ok) playing = true
        return ok
    }

    suspend fun togglePlayPause(): Boolean = if (playing) pause() else resume()

    suspend fun seek(position: Duration): Boolean {
        val device = currentDevice ?: return false
        return DlnaController.seek(device, position)
    }

    /**
     * Seek by re-pushing the stream from [position] instead of using the
     * AVTransport Seek action. Old DMRs frequently acknowledge Seek with
     * HTTP 200 yet leave the playhead untouched (observed 2026-11-19:
     * Seek returned true, no toast, TV did not move). They DO honour a
     * fresh stream whose URL carries startTimeTicks - the very same
     * server-side seek used when a cast starts at a resume position,
     * which is verified working on those TVs.
     */
    suspend fun seekByRestart(position: Duration): Boolean {
        val device = currentDevice ?: return false
        val source = currentSource ?: return false
        // A FRESH playSessionId forces Jellyfin to open a new stream session
        // that honours startTimeTicks. Reusing the old id made the server
        // resume the previous session and ignore the requested position
        // (verified 2026-11-19: TV replayed from the pre-seek position).
        val freshSessionId = java.util.UUID.randomUUID().toString()
        Timber.i("DLNA seek-by-restart to $position, fresh session=$freshSessionId")
        val ok = startCasting(
            device, source, position, currentTitle,
            sessionIdOverride = freshSessionId,
            serverSeekOnly = true,
        )
        Timber.i("DLNA seek-by-restart to $position -> ok=$ok")
        return ok
    }

    suspend fun getPosition(): Duration? {
        val device = currentDevice ?: return null
        return DlnaController.getPosition(device)
    }

    suspend fun stop() {
        currentDevice?.let { DlnaController.stop(it) }
        currentDevice = null
        currentStreamUrl = ""
        currentSource = null
        activeProfile = ""
        playing = false
    }
}
