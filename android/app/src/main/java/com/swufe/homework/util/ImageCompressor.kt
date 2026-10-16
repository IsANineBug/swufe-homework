package com.swufe.homework.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把图片压到约 1 MB 的 JPEG（requirements §十一）。课表截图需保持文字清晰，长边上限 2400px。
 * WS5 发布作业时可直接复用。
 */
@Singleton
class ImageCompressor @Inject constructor(@ApplicationContext private val ctx: Context) {

    suspend fun compress(uri: Uri, targetBytes: Int = 1_000_000, maxEdge: Int = 2400): ByteArray = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        var sample = 1
        while (longest / (sample * 2) >= maxEdge) sample *= 2
        val bmp = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("无法读取图片")
        val scaled = scaleDown(bmp, maxEdge)
        var quality = 90
        var out: ByteArray
        do {
            out = ByteArrayOutputStream().use { s -> scaled.compress(Bitmap.CompressFormat.JPEG, quality, s); s.toByteArray() }
            quality -= 10
        } while (out.size > targetBytes && quality >= 50)
        if (scaled !== bmp) scaled.recycle()
        bmp.recycle()
        out
    }

    private fun scaleDown(b: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(b.width, b.height)
        if (longest <= maxEdge) return b
        val r = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(b, (b.width * r).toInt(), (b.height * r).toInt(), true)
    }
}
