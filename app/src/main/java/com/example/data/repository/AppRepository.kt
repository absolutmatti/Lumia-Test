package com.example.data.repository

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.provider.Settings
import com.example.data.db.LumiaDatabase
import com.example.data.model.AppItem
import com.example.data.model.CardColorResult
import com.example.data.model.CustomAppImage
import com.example.data.model.FavoriteApp
import com.example.data.model.HiddenApp
import com.example.data.palette.PaletteExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.Collator
import java.util.Locale
import kotlin.math.max

class AppRepository(
    private val context: Context,
    private val database: LumiaDatabase,
    private val scope: CoroutineScope
) {
    private val packageManager: PackageManager = context.packageManager
    private val favoriteDao = database.favoriteAppDao()
    private val hiddenDao = database.hiddenAppDao()
    private val customImageDao = database.customAppImageDao()

    // Flow that triggers when packages are installed, updated, or removed
    private val packageUpdatesFlow: Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val pkgName = intent?.data?.schemeSpecificPart
                if (intent?.action == Intent.ACTION_PACKAGE_FULLY_REMOVED && pkgName != null) {
                    // Automatically clean up deleted packages from DB & storage
                    scope.launchCleanup(pkgName)
                }
                AppIconCache.clear()
                trySend(Unit)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
            addDataScheme("package")
        }
        context.registerReceiver(receiver, filter)
        trySend(Unit) // initial emission

        awaitClose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {}
        }
    }

    private fun CoroutineScope.launchCleanup(packageName: String) {
        launch(Dispatchers.IO) {
            favoriteDao.deleteByPackage(packageName)
            hiddenDao.deleteByPackage(packageName)
            customImageDao.deleteByPackage(packageName)
            val customFile = File(File(context.filesDir, "custom_app_images"), "$packageName.jpg")
            if (customFile.exists()) {
                customFile.delete()
            }
            AppIconCache.removeCustom(packageName)
            val remainingFavs = favoriteDao.getAllFavoritesList()
            favoriteDao.renumberFavorites(remainingFavs)
        }
    }

    val customImagesFlow: Flow<List<CustomAppImage>> = customImageDao.getAllCustomImages()

    // Installed raw app items combined with custom images
    private val rawInstalledAppsFlow: Flow<List<AppItem>> = combine(
        packageUpdatesFlow,
        customImagesFlow
    ) { _, customImages ->
        loadInstalledApps(customImages)
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )

    private suspend fun loadInstalledApps(customImages: List<CustomAppImage>): List<AppItem> = withContext(Dispatchers.Default) {
        val myPackage = context.packageName
        val customImageMap = customImages.associateBy { it.packageName }

        // 1. Leanback apps (priority)
        val leanbackIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val leanbackResolves = packageManager.queryIntentActivities(leanbackIntent, 0)

        // 2. Regular Launcher apps
        val standardIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val standardResolves = packageManager.queryIntentActivities(standardIntent, 0)

        // Map by package name, Leanback has priority
        val packageMap = mutableMapOf<String, AppItem>()

        for (ri in leanbackResolves) {
            val pkg = ri.activityInfo.packageName
            if (pkg == myPackage) continue
            val item = createAppItem(ri.activityInfo, isLeanback = true, customImageMap[pkg])
            packageMap[pkg] = item
        }

        for (ri in standardResolves) {
            val pkg = ri.activityInfo.packageName
            if (pkg == myPackage) continue
            if (!packageMap.containsKey(pkg)) {
                val item = createAppItem(ri.activityInfo, isLeanback = false, customImageMap[pkg])
                packageMap[pkg] = item
            }
        }

        // Sort alphabetically with system Collator
        val collator = Collator.getInstance(Locale.getDefault()).apply {
            strength = Collator.PRIMARY
        }

        packageMap.values.sortedWith { a, b ->
            collator.compare(a.label, b.label)
        }
    }

    private fun createAppItem(
        activityInfo: android.content.pm.ActivityInfo,
        isLeanback: Boolean,
        customImage: CustomAppImage?
    ): AppItem {
        val pkg = activityInfo.packageName
        val label = try {
            activityInfo.loadLabel(packageManager).toString()
        } catch (_: Exception) {
            pkg
        }

        val packageInfo = try {
            packageManager.getPackageInfo(pkg, 0)
        } catch (_: Exception) {
            null
        }

        val lastUpdateTime = (packageInfo?.lastUpdateTime ?: 0L) + (customImage?.timestamp ?: 0L)
        val isSystemApp = packageInfo?.applicationInfo?.let {
            (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        } ?: false

        val cacheKey = "$pkg-$lastUpdateTime"

        // Check if user set a custom image for this app
        val customBitmap = if (customImage != null) {
            AppIconCache.loadCustomImageFromDisk(context, pkg, customImage.isBanner)
        } else {
            null
        }

        val hasBanner: Boolean
        val colors: CardColorResult

        if (customImage != null && customBitmap != null) {
            if (customImage.isBanner) {
                hasBanner = true
                AppIconCache.setCustomBanner(pkg, customBitmap)
                colors = PaletteExtractor.getColors(cacheKey, BitmapDrawable(context.resources, customBitmap))
            } else {
                hasBanner = false
                AppIconCache.setCustomIcon(pkg, customBitmap)
                colors = PaletteExtractor.getColors(cacheKey, BitmapDrawable(context.resources, customBitmap))
            }
        } else {
            val systemHasBanner = try {
                activityInfo.loadBanner(packageManager) != null
            } catch (_: Exception) {
                false
            }
            hasBanner = systemHasBanner

            val iconDrawable = try {
                activityInfo.loadIcon(packageManager)
            } catch (_: Exception) {
                null
            }

            colors = PaletteExtractor.getColors(cacheKey, iconDrawable)

            // Pre-rasterize and cache icon and banner on background thread
            AppIconCache.getIcon(pkg, activityInfo.name, packageManager)
            if (hasBanner) {
                AppIconCache.getBanner(pkg, activityInfo.name, packageManager)
            }
        }

        return AppItem(
            packageName = pkg,
            activityName = activityInfo.name,
            label = label,
            isLeanback = isLeanback,
            hasBanner = hasBanner,
            isSystemApp = isSystemApp,
            lastUpdateTime = lastUpdateTime,
            dynamicColors = colors,
            hasCustomImage = customImage != null
        )
    }

    // Room Favorites Flow
    val favoritesDaoFlow: Flow<List<FavoriteApp>> = favoriteDao.getAllFavorites()

    // Room Hidden Flow
    val hiddenDaoFlow: Flow<List<HiddenApp>> = hiddenDao.getAllHidden()

    // 1. Favorites Panel apps (up to 6, maintaining added order)
    val favoriteAppsFlow: Flow<List<AppItem>> = combine(
        rawInstalledAppsFlow,
        favoritesDaoFlow
    ) { apps, favorites ->
        val appMap = apps.associateBy { it.packageName }
        favorites.mapNotNull { fav -> appMap[fav.packageName] }
    }

    // 2. Grid Apps (Remaining apps: not hidden and not favorite)
    val gridAppsFlow: Flow<List<AppItem>> = combine(
        rawInstalledAppsFlow,
        favoritesDaoFlow,
        hiddenDaoFlow
    ) { apps, favorites, hidden ->
        val favoritePackages = favorites.map { it.packageName }.toSet()
        val hiddenPackages = hidden.map { it.packageName }.toSet()

        val collator = Collator.getInstance(Locale.getDefault())
        apps.filter { it.packageName !in favoritePackages && it.packageName !in hiddenPackages }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    // 3. Hidden apps list for Settings management
    val hiddenAppsListFlow: Flow<List<AppItem>> = combine(
        rawInstalledAppsFlow,
        hiddenDaoFlow
    ) { apps, hidden ->
        val hiddenPackages = hidden.map { it.packageName }.toSet()
        apps.filter { it.packageName in hiddenPackages }
    }

    suspend fun addFavorite(packageName: String): Boolean = withContext(Dispatchers.IO) {
        val count = favoriteDao.getCount()
        if (count >= 6) return@withContext false

        val currentFavs = favoriteDao.getAllFavoritesList()
        if (currentFavs.any { it.packageName == packageName }) return@withContext true

        val nextPosition = currentFavs.size
        favoriteDao.insert(FavoriteApp(packageName, nextPosition))
        true
    }

    suspend fun removeFavorite(packageName: String) = withContext(Dispatchers.IO) {
        favoriteDao.deleteByPackage(packageName)
        val remaining = favoriteDao.getAllFavoritesList()
        favoriteDao.renumberFavorites(remaining)
    }

    suspend fun hideApp(packageName: String) = withContext(Dispatchers.IO) {
        // Also remove from favorites if present
        favoriteDao.deleteByPackage(packageName)
        val remaining = favoriteDao.getAllFavoritesList()
        favoriteDao.renumberFavorites(remaining)
        hiddenDao.insert(HiddenApp(packageName))
    }

    suspend fun unhideApp(packageName: String) = withContext(Dispatchers.IO) {
        hiddenDao.deleteByPackage(packageName)
    }

    suspend fun resetFavorites() = withContext(Dispatchers.IO) {
        favoriteDao.clearAll()
    }

    /**
     * Saves a user-selected custom icon or 16:9 banner for a specific package.
     * Automatically identifies wide images (>= 1.25) as 16:9 banners, and square/tall as icons.
     */
    suspend fun saveCustomAppImage(packageName: String, sourceUri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            var inputStream: InputStream? = context.contentResolver.openInputStream(sourceUri)
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream?.close()

            val srcWidth = options.outWidth
            val srcHeight = options.outHeight
            if (srcWidth <= 0 || srcHeight <= 0) return@withContext false

            val aspectRatio = srcWidth.toFloat() / srcHeight.toFloat()
            val isBanner = aspectRatio >= 1.25f

            val targetMaxWidth = if (isBanner) 640 else 192
            val targetMaxHeight = if (isBanner) 360 else 192

            var sampleSize = 1
            val maxDim = max(srcWidth.toFloat() / targetMaxWidth, srcHeight.toFloat() / targetMaxHeight)
            if (maxDim > 1.0f) {
                sampleSize = maxDim.toInt()
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            inputStream = context.contentResolver.openInputStream(sourceUri)
            val decodedBitmap = BitmapFactory.decodeStream(inputStream, null, decodeOptions)
            inputStream?.close()

            if (decodedBitmap == null) return@withContext false

            val finalBitmap = if (decodedBitmap.width > targetMaxWidth || decodedBitmap.height > targetMaxHeight) {
                val scale = minOf(
                    targetMaxWidth.toFloat() / decodedBitmap.width,
                    targetMaxHeight.toFloat() / decodedBitmap.height
                )
                val destW = (decodedBitmap.width * scale).toInt()
                val destH = (decodedBitmap.height * scale).toInt()
                val scaled = Bitmap.createScaledBitmap(decodedBitmap, destW, destH, true)
                if (scaled != decodedBitmap) {
                    decodedBitmap.recycle()
                }
                scaled
            } else {
                decodedBitmap
            }

            val dir = File(context.filesDir, "custom_app_images").apply { mkdirs() }
            val targetFile = File(dir, "$packageName.jpg")

            FileOutputStream(targetFile).use { out ->
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }

            if (isBanner) {
                AppIconCache.setCustomBanner(packageName, finalBitmap)
            } else {
                AppIconCache.setCustomIcon(packageName, finalBitmap)
            }

            customImageDao.insert(CustomAppImage(packageName, isBanner, System.currentTimeMillis()))
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Resets any custom icon or banner for the given package back to system default.
     */
    suspend fun resetCustomAppImage(packageName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.filesDir, "custom_app_images")
            val targetFile = File(dir, "$packageName.jpg")
            if (targetFile.exists()) {
                targetFile.delete()
            }
            AppIconCache.removeCustom(packageName)
            customImageDao.deleteByPackage(packageName)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun launchApp(context: Context, app: AppItem): Boolean {
        return try {
            val intent = packageManager.getLeanbackLaunchIntentForPackage(app.packageName)
                ?: packageManager.getLaunchIntentForPackage(app.packageName)

            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    fun openAppInfo(context: Context, packageName: String): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun uninstallApp(context: Context, packageName: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
