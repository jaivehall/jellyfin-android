package org.jellyfin.mobile.player.cast

import android.content.Context
import androidx.core.view.isVisible
import androidx.mediarouter.app.MediaRouteButton
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Libre builds ship without Google Play Services, so casting is unavailable.
 * The local player is returned unchanged and the route button is hidden.
 */
class CastPlayerFactory : ICastPlayerFactory {
    override val isCastAvailable: Boolean = false

    override fun createPlayer(context: Context, localPlayer: ExoPlayer): Player = localPlayer

    override fun setupCastButton(context: Context, button: MediaRouteButton) {
        button.isVisible = false
    }

    override fun discoverCastRoutes(
        context: Context,
        onRoutesChanged: (List<CastRouteInfo>) -> Unit,
    ): CastDiscoveryHandle {
        onRoutesChanged(emptyList())
        return CastDiscoveryHandle { }
    }

    override fun selectCastRoute(context: Context, key: String) = Unit
}
