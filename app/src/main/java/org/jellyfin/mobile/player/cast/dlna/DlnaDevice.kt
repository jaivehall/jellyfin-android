package org.jellyfin.mobile.player.cast.dlna

/**
 * A UPnP/DLNA MediaRenderer discovered on the local network.
 *
 * [controlUrl] is the absolute URL of the AVTransport service control endpoint,
 * used to send SOAP actions (SetAVTransportURI, Play, Pause, ...).
 */
data class DlnaDevice(
    val udn: String,
    val friendlyName: String,
    val controlUrl: String,
    val baseUrl: String,
) {
    override fun toString(): String = friendlyName
}
