package org.jellyfin.mobile.app

import okhttp3.OkHttpClient
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val apiModule = module {
    // Jellyfin API builder and API client instance.
    // The SDK's default client factory is OkHttp-based; supply a base client
    // with our /Items sort interceptor so every native getItems() call is
    // normalized in one place (see ItemsSortInterceptor).
    single {
        val httpClient = OkHttpClient.Builder()
            .addInterceptor(ItemsSortInterceptor)
            .build()
        createJellyfin {
            context = androidContext()
            clientInfo = ClientInfo(name = Constants.APP_INFO_NAME, version = Constants.APP_INFO_VERSION)
            apiClientFactory = OkHttpFactory(base = httpClient)
        }
    }
    single { get<Jellyfin>().createApi() }
}
