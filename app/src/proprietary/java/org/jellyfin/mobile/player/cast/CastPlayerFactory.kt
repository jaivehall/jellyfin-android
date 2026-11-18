package org.jellyfin.mobile.player.cast

import android.content.Context
import androidx.mediarouter.app.MediaRouteButton
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.MediaRouteButtonFactory
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.gms.cast.CastMediaControlIntent

class CastPlayerFactory : ICastPlayerFactory {
    override val isCastAvailable: Boolean = true

    // Same selector Media3's CastPlayer listens to, so selectRoute() connects the player.
    private val routeSelector = MediaRouteSelector.Builder()
        .addControlCategory(
            CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID),
        )
        .build()

    private val knownRoutes = mutableMapOf<String, MediaRouter.RouteInfo>()

    override fun createPlayer(context: Context, localPlayer: ExoPlayer): Player =
        CastPlayer.Builder(context)
            .setLocalPlayer(localPlayer)
            .build()

    override fun setupCastButton(context: Context, button: MediaRouteButton) {
        // Device discovery can take a while; let users abort the search explicitly.
        button.setDialogFactory(CancelableRouteDialogFactory())
        MediaRouteButtonFactory.setUpMediaRouteButton(context, button)
    }

    override fun discoverCastRoutes(
        context: Context,
        onRoutesChanged: (List<CastRouteInfo>) -> Unit,
    ): CastDiscoveryHandle {
        val router = MediaRouter.getInstance(context)
        val callback = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = publish(router)
            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = publish(router)
            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = publish(router)

            private fun publish(activeRouter: MediaRouter) {
                knownRoutes.clear()
                val routes = activeRouter.routes
                    .filter { it.isEnabled && it.matchesSelector(routeSelector) }
                    .onEach { knownRoutes[it.id] = it }
                    .map { CastRouteInfo(it.id, it.name) }
                onRoutesChanged(routes)
            }
        }
        router.addCallback(routeSelector, callback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        publishInitialRoutes(router, onRoutesChanged)
        return CastDiscoveryHandle { router.removeCallback(callback) }
    }

    private fun publishInitialRoutes(
        router: MediaRouter,
        onRoutesChanged: (List<CastRouteInfo>) -> Unit,
    ) {
        knownRoutes.clear()
        val routes = router.routes
            .filter { it.isEnabled && it.matchesSelector(routeSelector) }
            .onEach { knownRoutes[it.id] = it }
            .map { CastRouteInfo(it.id, it.name) }
        onRoutesChanged(routes)
    }

    override fun selectCastRoute(context: Context, key: String) {
        val route = knownRoutes[key] ?: return
        MediaRouter.getInstance(context).selectRoute(route)
    }
}
