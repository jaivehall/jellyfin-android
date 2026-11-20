package org.jellyfin.mobile.app

import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber

/**
 * Normalizes the sort parameters of every native Jellyfin `/Items` request so
 * the media library is always ordered the same way:
 *
 * - all `sortBy` values are replaced with `DateCreated`, `ProductionYear`,
 *   `PremiereDate`, `SortName` (in that order)
 * - all `sortOrder` values are replaced with a single `Descending`
 *
 * This is installed as an interceptor on the OkHttp client backing the SDK's
 * [okhttp3.OkHttpClient], so it covers every native `itemsApi.getItems(...)`
 * call in one place instead of patching each call site. The equivalent rewrite
 * for the WebView (jellyfin-web) side lives in `itemsSortOverride.js`.
 */
object ItemsSortInterceptor : Interceptor {
    private const val PATH_ITEMS = "/Items"
    private val SORT_BY = listOf("DateCreated", "ProductionYear", "PremiereDate", "SortName")
    private const val SORT_ORDER = "Descending"

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        // Match any path ending in /Items (tolerates a trailing slash and
        // base-path deployments like /jellyfin/Items), case-insensitive.
        // Kept in sync with the WebView rewrite in itemsSortOverride.js.
        val path = url.encodedPath.trimEnd('/')
        if (!path.endsWith(PATH_ITEMS, ignoreCase = true)) {
            return chain.proceed(request)
        }

        val prevSortBy = url.queryParameterValues("sortBy")
        val prevSortOrder = url.queryParameterValues("sortOrder")
        val newUrl = url.newBuilder()
            .removeAllQueryParameters("sortBy")
            .removeAllQueryParameters("sortOrder")
            .apply {
                SORT_BY.forEach { addQueryParameter("sortBy", it) }
                addQueryParameter("sortOrder", SORT_ORDER)
            }
            .build()

        Timber.d(
            "ItemsSortInterceptor: rewrote /Items sort params: " +
                "sortBy=$prevSortBy -> $SORT_BY, sortOrder=$prevSortOrder -> [$SORT_ORDER]"
        )
        return chain.proceed(request.newBuilder().url(newUrl).build())
    }
}
