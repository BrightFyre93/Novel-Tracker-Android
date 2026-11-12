package com.example.noveltracker

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.disk.DiskCache
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import okio.Path.Companion.toPath

/**
 * Cover images with a persistent disk cache (stored in app files, so covers still show offline).
 * Requests reuse the WebView's user agent and cookies, so a solved bot check also lets covers load.
 */
object Covers {
    @Volatile private var inst: ImageLoader? = null

    fun loader(ctx: Context): ImageLoader = inst ?: synchronized(this) {
        inst ?: ImageLoader.Builder(ctx.applicationContext)
            .diskCache {
                DiskCache.Builder()
                    .directory(ctx.applicationContext.filesDir.resolve("covers").absolutePath.toPath())
                    .maxSizeBytes(150L * 1024 * 1024)
                    .build()
            }
            .build().also { inst = it }
    }

    fun request(ctx: Context, url: String): ImageRequest {
        val ua = WebSettings.getDefaultUserAgent(ctx).replace("; wv", "")
        val cookie = CookieManager.getInstance().getCookie(url) ?: ""
        val headers = NetworkHeaders.Builder().add("User-Agent", ua).apply {
            if (cookie.isNotEmpty()) add("Cookie", cookie)
        }.build()
        return ImageRequest.Builder(ctx).data(url).httpHeaders(headers).diskCacheKey(url).build()
    }
}

@Composable
fun Cover(url: String, modifier: Modifier) {
    val ctx = LocalContext.current
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(Pal.Track)) {
        if (url.isNotBlank()) {
            AsyncImage(
                model = Covers.request(ctx, url), contentDescription = null,
                imageLoader = Covers.loader(ctx), contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}