package org.jellyfin.mobile.player.cast

import android.content.Context
import androidx.mediarouter.app.MediaRouteButton
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Flavor-specific factory that optionally wraps the local [ExoPlayer] in a
 * Cast-aware player and wires up the system cast route picker.
 *
 * The proprietary flavor uses Media3's CastPlayer (Google Cast), while the
 * libre flavor keeps plain local playback and hides the cast button.
 */
/** A cast receiver exposed by the flavor-specific backend. */
data class CastRouteInfo(val key: String, val name: String)

/** Handle used to stop an in-progress cast discovery. */
fun interface CastDiscoveryHandle {
    fun stop()
}

interface ICastPlayerFactory {
    val isCastAvailable: Boolean

    /** Create the player used by the player screen. */
    fun createPlayer(context: Context, localPlayer: ExoPlayer): Player

    /** Attach the route picker to a [MediaRouteButton] in the player controls. */
    fun setupCastButton(context: Context, button: MediaRouteButton)

    /**
     * Start discovering cast receivers. The callback runs on the main thread and receives
     * the current set of routes whenever it changes. Call [CastDiscoveryHandle.stop] when
     * discovery is no longer needed.
     */
    fun discoverCastRoutes(
        context: Context,
        onRoutesChanged: (List<CastRouteInfo>) -> Unit,
    ): CastDiscoveryHandle

    /** Select a discovered route; the CastPlayer connects to it automatically. */
    fun selectCastRoute(context: Context, key: String)
}
