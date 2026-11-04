package com.example.data.repository

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object AppIconCache {
    private val iconCache = ConcurrentHashMap<String, ImageBitmap>()
    private val bannerCache = ConcurrentHashMap<String, ImageBitmap>()
    private val customBanners = ConcurrentHashMap<String, ImageBitmap>()
    private val customIcons = ConcurrentHashMap<String, ImageBitmap>()
    private val noBannerKeys = ConcurrentHashMap.newKeySet<String>()
    private val noIconKeys = ConcurrentHashMap.newKeySet<String>()

    fun getCustomBanner(packageName: String): ImageBitmap? {
        return customBanners[packageName]
    }

    fun getCustomIcon(packageName: String): ImageBitmap? {
        return customIcons[packageName]
    }

    fun setCustomBanner(packageName: String, bitmap: Bitmap) {
        customBanners[packageName] = bitmap.asImageBitmap()
    }

    fun setCustomIcon(packageName: String, bitmap: Bitmap) {
        customIcons[packageName] = bitmap.asImageBitmap()
    }

    fun removeCustom(packageName: String) {
        customBanners.remove(packageName)
        customIcons.remove(packageName)
    }

    fun loadCustomImageFromDisk(context: Context, packageName: String, isBanner: Boolean): Bitmap? {
        val dir = File(context.filesDir, "custom_app_images")
        val file = File(dir, "$packageName.jpg")
        if (!file.exists() || file.length() == 0L) return null

        return try {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap != null) {
                if (isBanner) {
                    customBanners[packageName] = bitmap.asImageBitmap()
                } else {
                    customIcons[packageName] = bitmap.asImageBitmap()
                }
            }
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    fun getIcon(packageName: String, activityName: String, pm: PackageManager): ImageBitmap? {
        customIcons[packageName]?.let { return it }

        val key = "$packageName/$activityName"
        iconCache[key]?.let { return it }
        if (noIconKeys.contains(key)) return null

        val drawable = try {
            val component = ComponentName(packageName, activityName)
            pm.getActivityIcon(component) ?: pm.getApplicationIcon(packageName)
        } catch (_: Exception) {
            try {
                pm.getApplicationIcon(packageName)
            } catch (_: Exception) {
                null
            }
        }

        if (drawable == null) {
            noIconKeys.add(key)
            return null
        }

        val bitmap = drawableToBitmap(drawable, 96, 96)
        return if (bitmap != null) {
            val imageBitmap = bitmap.asImageBitmap()
            iconCache[key] = imageBitmap
            imageBitmap
        } else {
            noIconKeys.add(key)
            null
        }
    }

    fun getBanner(packageName: String, activityName: String, pm: PackageManager): ImageBitmap? {
        customBanners[packageName]?.let { return it }

        val key = "$packageName/$activityName"
        bannerCache[key]?.let { return it }
        if (noBannerKeys.contains(key)) return null

        val drawable = try {
            val component = ComponentName(packageName, activityName)
            pm.getActivityBanner(component) ?: pm.getApplicationBanner(packageName)
        } catch (_: Exception) {
            null
        }

        if (drawable == null) {
            noBannerKeys.add(key)
            return null
        }

        val bitmap = drawableToBitmap(drawable, 320, 180)
        return if (bitmap != null) {
            val imageBitmap = bitmap.asImageBitmap()
            bannerCache[key] = imageBitmap
            imageBitmap
        } else {
            noBannerKeys.add(key)
            null
        }
    }

    private fun drawableToBitmap(drawable: Drawable, width: Int, height: Int): Bitmap? {
        return try {
            if (drawable is BitmapDrawable && drawable.bitmap != null) {
                Bitmap.createScaledBitmap(drawable.bitmap, width, height, true)
            } else {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bitmap
            }
        } catch (_: Exception) {
            null
        }
    }

    fun clear() {
        iconCache.clear()
        bannerCache.clear()
        noBannerKeys.clear()
        noIconKeys.clear()
    }
}
