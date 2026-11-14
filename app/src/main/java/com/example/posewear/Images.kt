package com.example.posewear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/** 统一处理「绝对路径」与「SAF content:// uri」两种图片来源。 */
object Images {
    private val IMG_EXT = setOf("png", "jpg", "jpeg", "webp", "bmp")

    fun isImage(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in IMG_EXT

    fun open(ctx: Context, locator: String): InputStream? = try {
        if (locator.startsWith("content://", true))
            ctx.contentResolver.openInputStream(Uri.parse(locator))
        else {
            val f = File(locator)
            if (f.isFile) FileInputStream(f) else null
        }
    } catch (e: Exception) { null }

    /** 只读图片头拿原始像素尺寸（不解码整图），用于人物页显示分辨率。 */
    fun size(ctx: Context, locator: String): Pair<Int, Int> = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (locator.startsWith("content://", true)) {
            ctx.contentResolver.openInputStream(Uri.parse(locator))?.use {
                BitmapFactory.decodeStream(it, null, o)
            }
        } else {
            BitmapFactory.decodeFile(locator, o)
        }
        if (o.outWidth > 0 && o.outHeight > 0) Pair(o.outWidth, o.outHeight)
        else Pair(0, 0)
    } catch (e: Exception) {
        Pair(0, 0)
    }

    fun decode(ctx: Context, locator: String, maxEdge: Int): Bitmap? {
        val ins = open(ctx, locator) ?: return null
        val bytes = try { ins.use { it.readBytes() } } catch (e: Exception) { return null }
        if (bytes.isEmpty()) return null
        return try {
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
            var sample = 1
            val longest = maxOf(b.outWidth, b.outHeight)
            while (longest / sample > maxEdge && sample < 32) sample *= 2
            val o = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        } catch (e: Exception) { null }
    }

    /** 上传前统一转成 PNG（最长边 2048），等价于桌面版上传 pws_*.png 的做法。 */
    fun pngBytes(ctx: Context, locator: String): ByteArray {
        val bmp = decode(ctx, locator, 2048)
            ?: throw IllegalArgumentException("无法读取图片：${locator}")
        val bos = ByteArrayOutputStream()
        if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, bos))
            throw IllegalArgumentException("图片编码失败：${locator}")
        val data = bos.toByteArray()
        if (!bmp.isRecycled) bmp.recycle()
        return data
    }

    private fun cacheDir(ctx: Context): File =
        File(ctx.cacheDir, "thumbs").apply { if (!exists()) mkdirs() }

    private fun cacheFile(ctx: Context, locator: String, size: Int): File =
        File(cacheDir(ctx), md5("${locator}|${size}") + ".jpg")

    /**
     * 本地文件缩略图：直接 decodeFile 采样，不把整张原图读进 byte[]。
     * 目录里有几十张高像素照片时，比走 decode() 的 readBytes() 省得多。
     * 与 thumbnail() 共用磁盘缓存（缓存 key 含 locator 与 size）。
     */
    fun fileThumb(ctx: Context, path: String, size: Int = 128): Bitmap? {
        val cf = cacheFile(ctx, path, size)
        if (cf.isFile) {
            val hit = try { BitmapFactory.decodeFile(cf.absolutePath) } catch (e: Exception) { null }
            if (hit != null) return hit
        }
        return try {
            val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, probe)
            val longest = maxOf(probe.outWidth, probe.outHeight)
            if (longest <= 0) return null
            var sample = 1
            while (longest / sample > size * 2 && sample < 32) sample *= 2
            val src = BitmapFactory.decodeFile(
                path, BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return null
            val scale = minOf(size.toFloat() / src.width, size.toFloat() / src.height, 1f)
            val w = maxOf(1, (src.width * scale).toInt())
            val h = maxOf(1, (src.height * scale).toInt())
            val small = Bitmap.createScaledBitmap(src, w, h, true)
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawColor(Color.rgb(237, 241, 246))
            c.drawBitmap(small, ((size - w) / 2f), ((size - h) / 2f), null)
            try { cf.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 82, it) } }
            catch (e: Exception) { /* 缓存失败不影响显示 */ }
            if (!small.isRecycled) small.recycle()
            if (!src.isRecycled) src.recycle()
            out
        } catch (e: Exception) { null }
    }

    /** 缩略图：磁盘缓存 + 居中灰底，等价于桌面版 thumbs.py。 */
    fun thumbnail(ctx: Context, locator: String, size: Int = 256): Bitmap? {
        val cf = cacheFile(ctx, locator, size)
        if (cf.isFile) {
            val hit = try { BitmapFactory.decodeFile(cf.absolutePath) } catch (e: Exception) { null }
            if (hit != null) return hit
        }
        val src = decode(ctx, locator, size * 2) ?: return null
        val scale = minOf(size.toFloat() / src.width, size.toFloat() / src.height, 1f)
        val w = maxOf(1, (src.width * scale).toInt())
        val h = maxOf(1, (src.height * scale).toInt())
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.rgb(237, 241, 246))
        c.drawBitmap(small, ((size - w) / 2f), ((size - h) / 2f), null)
        try {
            cf.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        } catch (e: Exception) { /* 缓存失败不影响显示 */ }
        if (!small.isRecycled) small.recycle()
        if (!src.isRecycled) src.recycle()
        return out
    }
}
