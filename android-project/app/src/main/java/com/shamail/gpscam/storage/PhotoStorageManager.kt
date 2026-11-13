package com.shamail.gpscam.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.location.Geocoder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.shamail.gpscam.data.CoordFormat
import com.shamail.gpscam.data.GpsSnapshot
import com.shamail.gpscam.data.LocationSource
import com.shamail.gpscam.data.ResolutionPreset
import com.shamail.gpscam.data.SavedPhotoItem
import com.shamail.gpscam.data.TimestampMode
import com.shamail.gpscam.data.WatermarkConfig
import com.shamail.gpscam.data.WatermarkPosition
import com.shamail.gpscam.data.WatermarkSize
import com.shamail.gpscam.watermark.WatermarkRenderer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

data class SourceExifSnapshot(
    val dateTimeMillis: Long?,
    val latitude: Double?,
    val longitude: Double?,
    val dateTimeOriginal: String? = null,
    val dateTimeDigitized: String? = null,
    val dateTime: String? = null,
    val make: String? = null,
    val model: String? = null,
    val software: String? = null,
    val exposureTime: String? = null,
    val fNumber: String? = null,
    val isoSpeed: String? = null,
    val focalLength: String? = null,
    val flash: String? = null,
    val whiteBalance: String? = null
)

data class PhotoEditHistoryEntry(
    val recordedAtMillis: Long,
    val actionLabel: String,
    val sourcePhotoName: String?,
    val customDateTimeMillis: Long?,
    val customLocationTitle: String?,
    val customAddress: String?,
    val customLat: Double?,
    val customLon: Double?
)

data class PhotoMetadataRecord(
    val fileName: String,
    val sourcePhotoName: String?,
    val originalCaptureMillis: Long?,
    val originalLatitude: Double?,
    val originalLongitude: Double?,
    val originalLocationTitle: String?,
    val originalAddress: String?,
    val cameraInfo: String?,
    val watermarkDateTimeMillis: Long,
    val watermarkLocationTitle: String,
    val watermarkAddress: String,
    val watermarkLatitude: Double?,
    val watermarkLongitude: Double?,
    val isCustomTimestamp: Boolean,
    val isCustomLocation: Boolean,
    val history: List<PhotoEditHistoryEntry>
)

data class EditablePhotoContext(
    val sourceBitmap: Bitmap,
    val gps: GpsSnapshot,
    val config: WatermarkConfig,
    val originalDisplayMillis: Long,
    val originalCaptureMillis: Long,
    val originalSourceName: String,
    val hasCleanOriginal: Boolean,
    val sourceExif: SourceExifSnapshot,
    val cameraMake: String?,
    val cameraModel: String?
)

object PhotoStorageManager {

    const val SUBFOLDER_NAME = "GpsCamByShamail"
    const val RELATIVE_PICTURES_PATH = "Pictures/GpsCamByShamail"
    private const val SOURCE_CACHE_DIR = "watermark_sources"
    private const val HISTORY_DIR = "photo_history"
    private const val MAX_CACHED_SOURCES = 80
    private const val MAX_HISTORY_RECORDS = 200

    fun decodeGalleryUriBitmap(
        context: Context,
        sourceUri: Uri,
        preset: ResolutionPreset
    ): Bitmap? {
        return try {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOpts)
            }
            if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

            val targetMax = if (preset.maxLongEdge > 0) preset.maxLongEdge else 3840
            val rawLong = max(boundsOpts.outWidth, boundsOpts.outHeight)
            var sampleSize = 1
            while (rawLong / sampleSize > targetMax * 2) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val raw = context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOpts)
            } ?: return null

            val orientation = runCatching {
                context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                    ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                }
            }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.postRotate(90f)
                    matrix.preScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.postRotate(270f)
                    matrix.preScale(-1f, 1f)
                }
            }

            val oriented = if (!matrix.isIdentity) {
                val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                if (rotated != raw) raw.recycle()
                rotated
            } else {
                raw
            }

            val currentLong = max(oriented.width, oriented.height)
            if (currentLong <= targetMax) return oriented

            val scale = targetMax.toFloat() / currentLong.toFloat()
            val targetW = (oriented.width * scale).toInt().coerceAtLeast(1)
            val targetH = (oriented.height * scale).toInt().coerceAtLeast(1)
            val resized = Bitmap.createScaledBitmap(oriented, targetW, targetH, true)
            if (resized != oriented) oriented.recycle()
            resized
        } catch (_: Exception) {
            null
        }
    }

    fun decodeAndOrientTempCapture(
        tempFile: File,
        isFrontCamera: Boolean,
        preset: ResolutionPreset
    ): Bitmap? {
        val raw = BitmapFactory.decodeFile(tempFile.absolutePath) ?: return null
        val exif = runCatching { ExifInterface(tempFile.absolutePath) }.getOrNull()
        val orientation = exif?.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        ) ?: ExifInterface.ORIENTATION_NORMAL

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.preScale(-1f, 1f)
            }
        }
        if (isFrontCamera &&
            orientation != ExifInterface.ORIENTATION_FLIP_HORIZONTAL &&
            orientation != ExifInterface.ORIENTATION_TRANSPOSE &&
            orientation != ExifInterface.ORIENTATION_TRANSVERSE
        ) {
            matrix.postScale(-1f, 1f)
        }

        val oriented = if (!matrix.isIdentity) {
            val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
            if (rotated != raw) raw.recycle()
            rotated
        } else {
            raw
        }

        val maxEdge = preset.maxLongEdge
        if (maxEdge <= 0) return oriented

        val currentLongEdge = max(oriented.width, oriented.height)
        if (currentLongEdge <= maxEdge) return oriented

        val scale = maxEdge.toFloat() / currentLongEdge.toFloat()
        val targetW = (oriented.width * scale).toInt().coerceAtLeast(1)
        val targetH = (oriented.height * scale).toInt().coerceAtLeast(1)
        val resized = Bitmap.createScaledBitmap(oriented, targetW, targetH, true)
        if (resized != oriented) oriented.recycle()
        return resized
    }

    fun readSourcePhotoExif(context: Context, uri: Uri, filePath: String? = null): SourceExifSnapshot {
        return runCatching {
            val exif = if (!filePath.isNullOrBlank() && File(filePath).exists()) {
                ExifInterface(filePath)
            } else {
                context.contentResolver.openInputStream(uri)?.use { stream -> ExifInterface(stream) }
            }
            if (exif == null) return@runCatching SourceExifSnapshot(null, null, null)

            val dtOrig = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            val dtDig = exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
            val dt = exif.getAttribute(ExifInterface.TAG_DATETIME)
            val dtMillis = parseExifDateTime(dtOrig ?: dtDig ?: dt)
            val coords = exif.latLong

            SourceExifSnapshot(
                dateTimeMillis = dtMillis,
                latitude = coords?.getOrNull(0),
                longitude = coords?.getOrNull(1),
                dateTimeOriginal = dtOrig,
                dateTimeDigitized = dtDig,
                dateTime = dt,
                make = exif.getAttribute(ExifInterface.TAG_MAKE),
                model = exif.getAttribute(ExifInterface.TAG_MODEL),
                software = exif.getAttribute(ExifInterface.TAG_SOFTWARE),
                exposureTime = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME),
                fNumber = exif.getAttribute(ExifInterface.TAG_F_NUMBER),
                isoSpeed = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY),
                focalLength = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH),
                flash = exif.getAttribute(ExifInterface.TAG_FLASH),
                whiteBalance = exif.getAttribute(ExifInterface.TAG_WHITE_BALANCE)
            )
        }.getOrElse {
            SourceExifSnapshot(null, null, null)
        }
    }

    fun processAndSaveWatermarkedPhoto(
        context: Context,
        sourceBitmap: Bitmap,
        config: WatermarkConfig,
        gps: GpsSnapshot,
        captureDeviceMillis: Long = System.currentTimeMillis(),
        isGalleryImport: Boolean = false,
        hasCustomOverrides: Boolean = false,
        originalCaptureGps: GpsSnapshot? = null,
        originalSourceExif: SourceExifSnapshot? = null,
        sourceDisplayName: String? = null
    ): Uri? {
        val logoBitmap = WatermarkRenderer.loadLogoBitmap(context, config)
        val watermarked = WatermarkRenderer.renderOntoBitmap(
            source = sourceBitmap,
            config = config,
            gps = gps,
            logoBitmap = logoBitmap,
            captureMillis = captureDeviceMillis
        )

        val fileStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(captureDeviceMillis))
        val baseName = if (isGalleryImport) {
            "SHAMAIL_GALLERY_GPS_$fileStamp.jpg"
        } else {
            "SHAMAIL_GPS_$fileStamp.jpg"
        }
        val fileName = ensureUniqueOutputFileName(context, baseName)
        val quality = config.jpegQuality.coerceIn(70, 100)
        val displayedMillis = config.resolveTimestampMillis(gps, captureDeviceMillis)

        val effectiveOriginalMillis = if (isGalleryImport) {
            originalSourceExif?.dateTimeMillis ?: captureDeviceMillis
        } else {
            captureDeviceMillis
        }
        val effectiveOriginalGps = when {
            isGalleryImport -> {
                if (originalSourceExif?.latitude != null && originalSourceExif.longitude != null) {
                    GpsSnapshot(
                        hasFix = true,
                        latitude = originalSourceExif.latitude,
                        longitude = originalSourceExif.longitude,
                        gpsTimestampMillis = effectiveOriginalMillis,
                        locationSource = LocationSource.LIVE_GPS
                    )
                } else {
                    GpsSnapshot(hasFix = false)
                }
            }
            originalCaptureGps != null -> originalCaptureGps
            gps.locationSource == LocationSource.LIVE_GPS -> gps
            else -> GpsSnapshot(hasFix = false)
        }

        val customTimestamp = config.timestampMode == TimestampMode.MANUAL ||
            (originalSourceExif?.dateTimeMillis != null && abs(displayedMillis - originalSourceExif.dateTimeMillis) > 60_000L)
        val customLocation = hasCustomOverrides ||
            gps.locationSource != LocationSource.LIVE_GPS ||
            (originalCaptureGps != null && originalCaptureGps.hasFix && (
                abs(gps.latitude - originalCaptureGps.latitude) > 0.0001 ||
                    abs(gps.longitude - originalCaptureGps.longitude) > 0.0001
                ))

        val savedUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStoreQ(
                context = context,
                bitmap = watermarked,
                fileName = fileName,
                quality = quality,
                config = config,
                originalExif = originalSourceExif,
                originalGps = effectiveOriginalGps,
                originalCaptureMillis = effectiveOriginalMillis,
                isGalleryImport = isGalleryImport,
                isCustomWatermark = customTimestamp || customLocation
            )
        } else {
            saveViaLegacyPublicPictures(
                context = context,
                bitmap = watermarked,
                fileName = fileName,
                quality = quality,
                config = config,
                originalExif = originalSourceExif,
                originalGps = effectiveOriginalGps,
                originalCaptureMillis = effectiveOriginalMillis,
                isGalleryImport = isGalleryImport,
                isCustomWatermark = customTimestamp || customLocation
            )
        }

        if (savedUri != null) {
            persistCleanSourceBackup(
                context = context,
                displayName = fileName,
                cleanSourceBitmap = sourceBitmap,
                config = config,
                gps = gps,
                displayMillis = displayedMillis,
                originalCaptureMillis = effectiveOriginalMillis
            )

            val historyEntries = mutableListOf<PhotoEditHistoryEntry>()
            val existingSourceRecord = sourceDisplayName?.let { loadHistoryRecordByName(context, it) }
            if (existingSourceRecord != null) {
                historyEntries.addAll(existingSourceRecord.history)
            }
            if (isGalleryImport || customTimestamp || customLocation) {
                val actionDesc = when {
                    isGalleryImport && (customTimestamp || customLocation) -> "Gallery GPS Custom Watermark"
                    isGalleryImport -> "Gallery GPS Watermark Copy"
                    customTimestamp && customLocation -> "Custom Date/Time & Location Watermark"
                    customTimestamp -> "Custom Date & Time Watermark"
                    else -> "Custom Location Watermark"
                }
                historyEntries.add(
                    PhotoEditHistoryEntry(
                        recordedAtMillis = System.currentTimeMillis(),
                        actionLabel = actionDesc,
                        sourcePhotoName = sourceDisplayName ?: if (isGalleryImport) "Device Gallery" else null,
                        customDateTimeMillis = displayedMillis,
                        customLocationTitle = if (gps.hasFix) gps.buildLocationHeadline() else "",
                        customAddress = if (gps.hasFix) gps.buildFullAddressLine() else "",
                        customLat = if (gps.hasFix) gps.latitude else null,
                        customLon = if (gps.hasFix) gps.longitude else null
                    )
                )
            }

            val makeModel = listOfNotNull(
                originalSourceExif?.make ?: Build.MANUFACTURER?.takeIf { !isGalleryImport },
                originalSourceExif?.model ?: Build.MODEL?.takeIf { !isGalleryImport }
            ).filter { it.isNotBlank() }.distinct().joinToString(" ").ifBlank { null }

            val record = PhotoMetadataRecord(
                fileName = fileName,
                sourcePhotoName = existingSourceRecord?.sourcePhotoName ?: sourceDisplayName,
                originalCaptureMillis = existingSourceRecord?.originalCaptureMillis
                    ?: (if (isGalleryImport) originalSourceExif?.dateTimeMillis else captureDeviceMillis),
                originalLatitude = existingSourceRecord?.originalLatitude
                    ?: if (effectiveOriginalGps.hasFix) effectiveOriginalGps.latitude else null,
                originalLongitude = existingSourceRecord?.originalLongitude
                    ?: if (effectiveOriginalGps.hasFix) effectiveOriginalGps.longitude else null,
                originalLocationTitle = existingSourceRecord?.originalLocationTitle
                    ?: if (!isGalleryImport && effectiveOriginalGps.hasFix) effectiveOriginalGps.buildLocationHeadline() else null,
                originalAddress = existingSourceRecord?.originalAddress
                    ?: if (!isGalleryImport && effectiveOriginalGps.hasFix) effectiveOriginalGps.buildFullAddressLine() else null,
                cameraInfo = existingSourceRecord?.cameraInfo ?: makeModel,
                watermarkDateTimeMillis = displayedMillis,
                watermarkLocationTitle = if (gps.hasFix) gps.buildLocationHeadline() else "",
                watermarkAddress = if (gps.hasFix) gps.buildFullAddressLine() else "",
                watermarkLatitude = if (gps.hasFix) gps.latitude else null,
                watermarkLongitude = if (gps.hasFix) gps.longitude else null,
                isCustomTimestamp = customTimestamp || (existingSourceRecord?.isCustomTimestamp == true),
                isCustomLocation = customLocation || (existingSourceRecord?.isCustomLocation == true),
                history = historyEntries
            )
            persistPhotoMetadataRecord(context, record)
        }
        if (watermarked != sourceBitmap && !watermarked.isRecycled) {
            watermarked.recycle()
        }
        return savedUri
    }

    fun loadEditablePhotoContext(
        context: Context,
        photoUri: Uri,
        displayName: String,
        filePath: String?,
        fallbackConfig: WatermarkConfig
    ): EditablePhotoContext? {
        val safeKey = sanitizeCacheKey(displayName)
        val cacheDir = File(context.filesDir, SOURCE_CACHE_DIR)
        val cleanImgFile = File(cacheDir, "$safeKey.jpg")
        val cleanMetaFile = File(cacheDir, "$safeKey.json")

        val sourceExif = readSourcePhotoExif(context, photoUri, filePath)
        val existingRecord = loadHistoryRecordByName(context, displayName)

        if (cleanImgFile.exists() && cleanMetaFile.exists()) {
            val cachedBitmap = BitmapFactory.decodeFile(cleanImgFile.absolutePath)
            val parsedMeta = runCatching { JSONObject(cleanMetaFile.readText()) }.getOrNull()
            if (cachedBitmap != null && parsedMeta != null) {
                val gps = jsonToGps(parsedMeta.optJSONObject("gps"))
                val cfg = jsonToConfig(parsedMeta.optJSONObject("config"), fallbackConfig)
                val storedDisplayMillis = parsedMeta.optLong("displayMillis", System.currentTimeMillis())
                val storedCaptureMillis = parsedMeta.optLong(
                    "originalCaptureMillis",
                    existingRecord?.originalCaptureMillis ?: sourceExif.dateTimeMillis ?: storedDisplayMillis
                )
                return EditablePhotoContext(
                    sourceBitmap = cachedBitmap,
                    gps = gps,
                    config = cfg,
                    originalDisplayMillis = storedDisplayMillis,
                    originalCaptureMillis = storedCaptureMillis,
                    originalSourceName = displayName,
                    hasCleanOriginal = true,
                    sourceExif = sourceExif,
                    cameraMake = sourceExif.make,
                    cameraModel = sourceExif.model
                )
            }
        }

        val decoded = if (!filePath.isNullOrBlank() && File(filePath).exists()) {
            val f = File(filePath)
            decodeAndOrientTempCapture(f, isFrontCamera = false, preset = ResolutionPreset.MAX)
        } else {
            decodeGalleryUriBitmap(context, photoUri, ResolutionPreset.MAX)
        } ?: return null

        val lat = sourceExif.latitude
        val lon = sourceExif.longitude
        var gps = if (lat != null && lon != null) {
            GpsSnapshot(
                hasFix = true,
                latitude = lat,
                longitude = lon,
                gpsTimestampMillis = sourceExif.dateTimeMillis ?: System.currentTimeMillis(),
                locationSource = LocationSource.LIVE_GPS
            )
        } else {
            GpsSnapshot(
                hasFix = false,
                gpsTimestampMillis = sourceExif.dateTimeMillis ?: System.currentTimeMillis()
            )
        }

        if (lat != null && lon != null && Geocoder.isPresent()) {
            runCatching {
                @Suppress("DEPRECATION")
                val list = Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 1)
                val addr = list?.firstOrNull()
                if (addr != null) {
                    val city = addr.locality ?: addr.subAdminArea ?: ""
                    val district = addr.subAdminArea ?: ""
                    val province = addr.adminArea ?: ""
                    val country = addr.countryName ?: ""
                    val street = (0..addr.maxAddressLineIndex).joinToString(", ") {
                        addr.getAddressLine(it).orEmpty()
                    }.trim()
                    gps = gps.copy(
                        city = city,
                        district = district,
                        province = province,
                        country = country,
                        streetAddress = street
                    )
                }
            }
        }

        val origMillis = existingRecord?.originalCaptureMillis
            ?: sourceExif.dateTimeMillis
            ?: System.currentTimeMillis()

        return EditablePhotoContext(
            sourceBitmap = decoded,
            gps = gps,
            config = fallbackConfig,
            originalDisplayMillis = existingRecord?.watermarkDateTimeMillis ?: origMillis,
            originalCaptureMillis = origMillis,
            originalSourceName = displayName,
            hasCleanOriginal = false,
            sourceExif = sourceExif,
            cameraMake = sourceExif.make,
            cameraModel = sourceExif.model
        )
    }

    fun saveEditedDateTimeCopy(
        context: Context,
        editable: EditablePhotoContext,
        editedMillis: Long,
        is24HourFormat: Boolean,
        dateFormatPattern: String
    ): Pair<Uri, String>? {
        val updatedConfig = editable.config.copy(
            timestampMode = TimestampMode.MANUAL,
            manualTimestampMillis = editedMillis,
            is24HourFormat = is24HourFormat,
            dateFormatPattern = dateFormatPattern,
            showWatermark = true
        )
        val logoBitmap = WatermarkRenderer.loadLogoBitmap(context, updatedConfig)
        val watermarked = WatermarkRenderer.renderOntoBitmap(
            source = editable.sourceBitmap,
            config = updatedConfig,
            gps = editable.gps,
            logoBitmap = logoBitmap,
            captureMillis = editedMillis,
            forceOpaqueBackground = !editable.hasCleanOriginal
        )

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(editedMillis))
        val fileName = ensureUniqueOutputFileName(context, "SHAMAIL_GPS_${stamp}_copy.jpg")
        val quality = updatedConfig.jpegQuality.coerceIn(70, 100)

        val originalGps = if (editable.sourceExif.latitude != null && editable.sourceExif.longitude != null) {
            GpsSnapshot(
                hasFix = true,
                latitude = editable.sourceExif.latitude,
                longitude = editable.sourceExif.longitude,
                gpsTimestampMillis = editable.originalCaptureMillis,
                locationSource = LocationSource.LIVE_GPS
            )
        } else if (editable.gps.hasFix && editable.gps.locationSource == LocationSource.LIVE_GPS) {
            editable.gps
        } else {
            GpsSnapshot(hasFix = false)
        }

        val savedUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStoreQ(
                context = context,
                bitmap = watermarked,
                fileName = fileName,
                quality = quality,
                config = updatedConfig,
                originalExif = editable.sourceExif,
                originalGps = originalGps,
                originalCaptureMillis = editable.originalCaptureMillis,
                isGalleryImport = false,
                isCustomWatermark = true
            )
        } else {
            saveViaLegacyPublicPictures(
                context = context,
                bitmap = watermarked,
                fileName = fileName,
                quality = quality,
                config = updatedConfig,
                originalExif = editable.sourceExif,
                originalGps = originalGps,
                originalCaptureMillis = editable.originalCaptureMillis,
                isGalleryImport = false,
                isCustomWatermark = true
            )
        }

        if (savedUri != null) {
            if (editable.hasCleanOriginal) {
                persistCleanSourceBackup(
                    context = context,
                    displayName = fileName,
                    cleanSourceBitmap = editable.sourceBitmap,
                    config = updatedConfig,
                    gps = editable.gps,
                    displayMillis = editedMillis,
                    originalCaptureMillis = editable.originalCaptureMillis
                )
            }

            val prevRecord = loadHistoryRecordByName(context, editable.originalSourceName)
            val historyList = mutableListOf<PhotoEditHistoryEntry>()
            if (prevRecord != null) {
                historyList.addAll(prevRecord.history)
            }
            historyList.add(
                PhotoEditHistoryEntry(
                    recordedAtMillis = System.currentTimeMillis(),
                    actionLabel = "Custom Date & Time Watermark",
                    sourcePhotoName = editable.originalSourceName,
                    customDateTimeMillis = editedMillis,
                    customLocationTitle = if (editable.gps.hasFix) editable.gps.buildLocationHeadline() else "",
                    customAddress = if (editable.gps.hasFix) editable.gps.buildFullAddressLine() else "",
                    customLat = if (editable.gps.hasFix) editable.gps.latitude else null,
                    customLon = if (editable.gps.hasFix) editable.gps.longitude else null
                )
            )

            val makeModel = listOfNotNull(
                editable.cameraMake,
                editable.cameraModel
            ).filter { it.isNotBlank() }.distinct().joinToString(" ").ifBlank { null }

            val record = PhotoMetadataRecord(
                fileName = fileName,
                sourcePhotoName = prevRecord?.sourcePhotoName ?: editable.originalSourceName,
                originalCaptureMillis = prevRecord?.originalCaptureMillis ?: editable.originalCaptureMillis,
                originalLatitude = prevRecord?.originalLatitude
                    ?: if (originalGps.hasFix) originalGps.latitude else null,
                originalLongitude = prevRecord?.originalLongitude
                    ?: if (originalGps.hasFix) originalGps.longitude else null,
                originalLocationTitle = prevRecord?.originalLocationTitle
                    ?: if (originalGps.hasFix && editable.gps.locationSource == LocationSource.LIVE_GPS) {
                        editable.gps.buildLocationHeadline()
                    } else null,
                originalAddress = prevRecord?.originalAddress
                    ?: if (originalGps.hasFix && editable.gps.locationSource == LocationSource.LIVE_GPS) {
                        editable.gps.buildFullAddressLine()
                    } else null,
                cameraInfo = prevRecord?.cameraInfo ?: makeModel,
                watermarkDateTimeMillis = editedMillis,
                watermarkLocationTitle = if (editable.gps.hasFix) editable.gps.buildLocationHeadline() else "",
                watermarkAddress = if (editable.gps.hasFix) editable.gps.buildFullAddressLine() else "",
                watermarkLatitude = if (editable.gps.hasFix) editable.gps.latitude else null,
                watermarkLongitude = if (editable.gps.hasFix) editable.gps.longitude else null,
                isCustomTimestamp = true,
                isCustomLocation = prevRecord?.isCustomLocation ?: (editable.gps.locationSource != LocationSource.LIVE_GPS),
                history = historyList
            )
            persistPhotoMetadataRecord(context, record)
        }
        if (watermarked != editable.sourceBitmap && !watermarked.isRecycled) {
            watermarked.recycle()
        }

        return savedUri?.let { Pair(it, fileName) }
    }

    fun loadPhotoMetadataRecord(
        context: Context,
        photoUri: Uri,
        displayName: String,
        filePath: String?
    ): PhotoMetadataRecord {
        val sidecar = loadHistoryRecordByName(context, displayName)
        val exif = readSourcePhotoExif(context, photoUri, filePath)
        val makeModel = listOfNotNull(exif.make, exif.model)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
            .ifBlank { null }

        if (sidecar != null) {
            return sidecar.copy(
                originalCaptureMillis = sidecar.originalCaptureMillis ?: exif.dateTimeMillis,
                originalLatitude = sidecar.originalLatitude ?: exif.latitude,
                originalLongitude = sidecar.originalLongitude ?: exif.longitude,
                cameraInfo = sidecar.cameraInfo ?: makeModel
            )
        }

        return PhotoMetadataRecord(
            fileName = displayName,
            sourcePhotoName = null,
            originalCaptureMillis = exif.dateTimeMillis,
            originalLatitude = exif.latitude,
            originalLongitude = exif.longitude,
            originalLocationTitle = null,
            originalAddress = null,
            cameraInfo = makeModel,
            watermarkDateTimeMillis = exif.dateTimeMillis ?: System.currentTimeMillis(),
            watermarkLocationTitle = "",
            watermarkAddress = "",
            watermarkLatitude = exif.latitude,
            watermarkLongitude = exif.longitude,
            isCustomTimestamp = false,
            isCustomLocation = false,
            history = emptyList()
        )
    }

    private fun saveViaMediaStoreQ(
        context: Context,
        bitmap: Bitmap,
        fileName: String,
        quality: Int,
        config: WatermarkConfig,
        originalExif: SourceExifSnapshot?,
        originalGps: GpsSnapshot,
        originalCaptureMillis: Long,
        isGalleryImport: Boolean,
        isCustomWatermark: Boolean
    ): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$SUBFOLDER_NAME")
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000L)
            put(MediaStore.Images.Media.DATE_TAKEN, originalCaptureMillis)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            }
            resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                applyPreservedExifMetadata(
                    exif = exif,
                    config = config,
                    originalExif = originalExif,
                    originalGps = originalGps,
                    originalCaptureMillis = originalCaptureMillis,
                    isGalleryImport = isGalleryImport,
                    isCustomWatermark = isCustomWatermark
                )
                exif.saveAttributes()
            }
            val doneValues = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(uri, doneValues, null, null)
            uri
        } catch (_: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    private fun saveViaLegacyPublicPictures(
        context: Context,
        bitmap: Bitmap,
        fileName: String,
        quality: Int,
        config: WatermarkConfig,
        originalExif: SourceExifSnapshot?,
        originalGps: GpsSnapshot,
        originalCaptureMillis: Long,
        isGalleryImport: Boolean,
        isCustomWatermark: Boolean
    ): Uri? {
        return try {
            @Suppress("DEPRECATION")
            val picturesRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val targetDir = File(picturesRoot, SUBFOLDER_NAME)
            if (!targetDir.exists()) targetDir.mkdirs()

            val outFile = File(targetDir, fileName)
            FileOutputStream(outFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            }

            val exif = ExifInterface(outFile.absolutePath)
            applyPreservedExifMetadata(
                exif = exif,
                config = config,
                originalExif = originalExif,
                originalGps = originalGps,
                originalCaptureMillis = originalCaptureMillis,
                isGalleryImport = isGalleryImport,
                isCustomWatermark = isCustomWatermark
            )
            exif.saveAttributes()

            MediaScannerConnection.scanFile(
                context,
                arrayOf(outFile.absolutePath),
                arrayOf("image/jpeg"),
                null
            )

            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                outFile
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun applyPreservedExifMetadata(
        exif: ExifInterface,
        config: WatermarkConfig,
        originalExif: SourceExifSnapshot?,
        originalGps: GpsSnapshot,
        originalCaptureMillis: Long,
        isGalleryImport: Boolean,
        isCustomWatermark: Boolean
    ) {
        val exifDateFmt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)

        if (originalExif != null) {
            if (!originalExif.dateTimeOriginal.isNullOrBlank()) {
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, originalExif.dateTimeOriginal)
            }
            if (!originalExif.dateTimeDigitized.isNullOrBlank()) {
                exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, originalExif.dateTimeDigitized)
            }
            if (!originalExif.dateTime.isNullOrBlank()) {
                exif.setAttribute(ExifInterface.TAG_DATETIME, originalExif.dateTime)
            } else if (!originalExif.dateTimeOriginal.isNullOrBlank()) {
                exif.setAttribute(ExifInterface.TAG_DATETIME, originalExif.dateTimeOriginal)
            }
            if (!originalExif.make.isNullOrBlank()) exif.setAttribute(ExifInterface.TAG_MAKE, originalExif.make)
            if (!originalExif.model.isNullOrBlank()) exif.setAttribute(ExifInterface.TAG_MODEL, originalExif.model)
            if (!originalExif.exposureTime.isNullOrBlank()) exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, originalExif.exposureTime)
            if (!originalExif.fNumber.isNullOrBlank()) exif.setAttribute(ExifInterface.TAG_F_NUMBER, originalExif.fNumber)
            if (!originalExif.isoSpeed.isNullOrBlank()) exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, originalExif.isoSpeed)
            if (!originalExif.focalLength.isNullOrBlank()) exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, originalExif.focalLength)
            if (!originalExif.flash.isNullOrBlank()) exif.setAttribute(ExifInterface.TAG_FLASH, originalExif.flash)
            if (!originalExif.whiteBalance.isNullOrBlank()) exif.setAttribute(ExifInterface.TAG_WHITE_BALANCE, originalExif.whiteBalance)
        } else if (!isGalleryImport) {
            val captureExifStr = exifDateFmt.format(Date(originalCaptureMillis))
            exif.setAttribute(ExifInterface.TAG_DATETIME, captureExifStr)
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, captureExifStr)
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, captureExifStr)
            if (!Build.MANUFACTURER.isNullOrBlank()) exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
            if (!Build.MODEL.isNullOrBlank()) exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
        }

        if (originalExif?.latitude != null && originalExif.longitude != null) {
            exif.setLatLong(originalExif.latitude, originalExif.longitude)
        } else if (originalGps.hasFix && originalGps.locationSource == LocationSource.LIVE_GPS && !isGalleryImport) {
            exif.setLatLong(originalGps.latitude, originalGps.longitude)
            exif.setAttribute(
                ExifInterface.TAG_GPS_PROCESSING_METHOD,
                if (originalGps.isOfflineAddress) "GPS_OFFLINE" else "FUSED_GPS"
            )
        }

        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Gps cam by shamail v2.0.0")
        exif.setAttribute(ExifInterface.TAG_ARTIST, config.organizationName)

        val sourceTag = when {
            isGalleryImport && isCustomWatermark -> "CUSTOM_WATERMARK_GALLERY_COPY"
            isGalleryImport -> "GALLERY_GPS_WATERMARK_COPY"
            isCustomWatermark -> "CUSTOM_WATERMARK_INFO"
            config.timestampMode == TimestampMode.GPS_FIX -> "VERIFIED_GPS_CAPTURE"
            else -> "VERIFIED_DEVICE_CAPTURE"
        }
        exif.setAttribute(ExifInterface.TAG_USER_COMMENT, sourceTag)
        exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "${config.organizationName} | $sourceTag")
    }

    fun listSavedPhotos(context: Context): List<SavedPhotoItem> {
        val items = mutableListOf<SavedPhotoItem>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.RELATIVE_PATH
            )
            val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf("%$SUBFOLDER_NAME%")
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

            runCatching {
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: "SHAMAIL_GPS.jpg"
                        val dateSec = cursor.getLong(dateCol)
                        val contentUri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            id
                        )
                        val provenance = readProvenanceBadge(context, contentUri, null, name)
                        items.add(
                            SavedPhotoItem(
                                uri = contentUri,
                                displayName = name,
                                dateAddedSeconds = dateSec,
                                filePath = null,
                                timestampProvenance = provenance
                            )
                        )
                    }
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                SUBFOLDER_NAME
            )
            if (dir.exists()) {
                dir.listFiles { f -> f.isFile && (f.name.endsWith(".jpg", true) || f.name.endsWith(".jpeg", true)) }
                    ?.sortedByDescending { it.lastModified() }
                    ?.forEach { file ->
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )
                        val provenance = readProvenanceBadge(context, uri, file, file.name)
                        items.add(
                            SavedPhotoItem(
                                uri = uri,
                                displayName = file.name,
                                dateAddedSeconds = file.lastModified() / 1000L,
                                filePath = file.absolutePath,
                                timestampProvenance = provenance
                            )
                        )
                    }
            }
        }
        return items
    }

    fun readProvenanceBadge(context: Context, uri: Uri, file: File?, displayName: String? = null): String {
        val targetName = displayName ?: file?.name
        if (!targetName.isNullOrBlank()) {
            val sidecar = loadHistoryRecordByName(context, targetName)
            if (sidecar != null) {
                return when {
                    sidecar.isCustomTimestamp && sidecar.isCustomLocation -> "Custom Location & Time"
                    sidecar.isCustomTimestamp -> "Custom Date & Time"
                    sidecar.isCustomLocation -> "Custom Location Info"
                    else -> "Verified Capture Info"
                }
            }
        }

        return try {
            val exif = if (file != null && file.exists()) {
                ExifInterface(file.absolutePath)
            } else {
                context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
            }
            val comment = exif?.getAttribute(ExifInterface.TAG_USER_COMMENT)
                ?: exif?.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)
                ?: ""
            when {
                comment.contains("CUSTOM_WATERMARK", ignoreCase = true) ||
                    comment.contains("CUSTOM_DISPLAY_TIME", ignoreCase = true) -> "Custom Watermark Info"
                comment.contains("GALLERY_GPS", ignoreCase = true) -> "Gallery Watermark Copy"
                comment.contains("VERIFIED_GPS", ignoreCase = true) -> "Verified GPS Time"
                else -> "Verified Capture Info"
            }
        } catch (_: Exception) {
            "Saved in $RELATIVE_PICTURES_PATH"
        }
    }

    fun deletePhoto(context: Context, uri: Uri, filePath: String?): Boolean {
        return try {
            if (!filePath.isNullOrBlank()) {
                val f = File(filePath)
                if (f.exists() && f.delete()) return true
            }
            context.contentResolver.delete(uri, null, null) > 0
        } catch (_: Exception) {
            false
        }
    }

    private fun parseExifDateTime(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply { isLenient = false }
                .parse(text)?.time
        }.getOrNull()
    }

    private fun ensureUniqueOutputFileName(context: Context, requestedName: String): String {
        val existingNames = listSavedPhotos(context).map { it.displayName }.toHashSet()
        if (!existingNames.contains(requestedName)) return requestedName
        val dotIdx = requestedName.lastIndexOf('.')
        val stem = if (dotIdx > 0) requestedName.substring(0, dotIdx) else requestedName
        val ext = if (dotIdx > 0) requestedName.substring(dotIdx) else ".jpg"
        var index = 1
        while (existingNames.contains("${stem}_$index$ext")) {
            index++
        }
        return "${stem}_$index$ext"
    }

    private fun sanitizeCacheKey(displayName: String): String {
        return displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    private fun persistPhotoMetadataRecord(context: Context, record: PhotoMetadataRecord) {
        runCatching {
            val dir = File(context.filesDir, HISTORY_DIR)
            if (!dir.exists()) dir.mkdirs()

            val histArray = JSONArray()
            record.history.forEach { entry ->
                histArray.put(JSONObject().apply {
                    put("recordedAtMillis", entry.recordedAtMillis)
                    put("actionLabel", entry.actionLabel)
                    if (entry.sourcePhotoName != null) put("sourcePhotoName", entry.sourcePhotoName)
                    if (entry.customDateTimeMillis != null) put("customDateTimeMillis", entry.customDateTimeMillis)
                    if (entry.customLocationTitle != null) put("customLocationTitle", entry.customLocationTitle)
                    if (entry.customAddress != null) put("customAddress", entry.customAddress)
                    if (entry.customLat != null) put("customLat", entry.customLat)
                    if (entry.customLon != null) put("customLon", entry.customLon)
                })
            }

            val json = JSONObject().apply {
                put("fileName", record.fileName)
                if (record.sourcePhotoName != null) put("sourcePhotoName", record.sourcePhotoName)
                if (record.originalCaptureMillis != null) put("originalCaptureMillis", record.originalCaptureMillis)
                if (record.originalLatitude != null) put("originalLatitude", record.originalLatitude)
                if (record.originalLongitude != null) put("originalLongitude", record.originalLongitude)
                if (record.originalLocationTitle != null) put("originalLocationTitle", record.originalLocationTitle)
                if (record.originalAddress != null) put("originalAddress", record.originalAddress)
                if (record.cameraInfo != null) put("cameraInfo", record.cameraInfo)
                put("watermarkDateTimeMillis", record.watermarkDateTimeMillis)
                put("watermarkLocationTitle", record.watermarkLocationTitle)
                put("watermarkAddress", record.watermarkAddress)
                if (record.watermarkLatitude != null) put("watermarkLatitude", record.watermarkLatitude)
                if (record.watermarkLongitude != null) put("watermarkLongitude", record.watermarkLongitude)
                put("isCustomTimestamp", record.isCustomTimestamp)
                put("isCustomLocation", record.isCustomLocation)
                put("history", histArray)
            }

            val safeKey = sanitizeCacheKey(record.fileName)
            File(dir, "$safeKey.json").writeText(json.toString())

            val files = dir.listFiles { f -> f.name.endsWith(".json") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
            if (files.size > MAX_HISTORY_RECORDS) {
                files.drop(MAX_HISTORY_RECORDS).forEach { it.delete() }
            }
        }
    }

    private fun loadHistoryRecordByName(context: Context, displayName: String): PhotoMetadataRecord? {
        return runCatching {
            val dir = File(context.filesDir, HISTORY_DIR)
            val file = File(dir, "${sanitizeCacheKey(displayName)}.json")
            if (!file.exists()) return null
            val obj = JSONObject(file.readText())
            val histArr = obj.optJSONArray("history") ?: JSONArray()
            val histList = mutableListOf<PhotoEditHistoryEntry>()
            for (i in 0 until histArr.length()) {
                val item = histArr.optJSONObject(i) ?: continue
                histList.add(
                    PhotoEditHistoryEntry(
                        recordedAtMillis = item.optLong("recordedAtMillis", 0L),
                        actionLabel = item.optString("actionLabel", "Custom Watermark"),
                        sourcePhotoName = item.optString("sourcePhotoName").ifBlank { null },
                        customDateTimeMillis = if (item.has("customDateTimeMillis")) item.optLong("customDateTimeMillis") else null,
                        customLocationTitle = item.optString("customLocationTitle").ifBlank { null },
                        customAddress = item.optString("customAddress").ifBlank { null },
                        customLat = if (item.has("customLat")) item.optDouble("customLat") else null,
                        customLon = if (item.has("customLon")) item.optDouble("customLon") else null
                    )
                )
            }
            PhotoMetadataRecord(
                fileName = obj.optString("fileName", displayName),
                sourcePhotoName = obj.optString("sourcePhotoName").ifBlank { null },
                originalCaptureMillis = if (obj.has("originalCaptureMillis")) obj.optLong("originalCaptureMillis") else null,
                originalLatitude = if (obj.has("originalLatitude")) obj.optDouble("originalLatitude") else null,
                originalLongitude = if (obj.has("originalLongitude")) obj.optDouble("originalLongitude") else null,
                originalLocationTitle = obj.optString("originalLocationTitle").ifBlank { null },
                originalAddress = obj.optString("originalAddress").ifBlank { null },
                cameraInfo = obj.optString("cameraInfo").ifBlank { null },
                watermarkDateTimeMillis = obj.optLong("watermarkDateTimeMillis", System.currentTimeMillis()),
                watermarkLocationTitle = obj.optString("watermarkLocationTitle", ""),
                watermarkAddress = obj.optString("watermarkAddress", ""),
                watermarkLatitude = if (obj.has("watermarkLatitude")) obj.optDouble("watermarkLatitude") else null,
                watermarkLongitude = if (obj.has("watermarkLongitude")) obj.optDouble("watermarkLongitude") else null,
                isCustomTimestamp = obj.optBoolean("isCustomTimestamp", false),
                isCustomLocation = obj.optBoolean("isCustomLocation", false),
                history = histList
            )
        }.getOrNull()
    }

    private fun persistCleanSourceBackup(
        context: Context,
        displayName: String,
        cleanSourceBitmap: Bitmap,
        config: WatermarkConfig,
        gps: GpsSnapshot,
        displayMillis: Long,
        originalCaptureMillis: Long
    ) {
        runCatching {
            if (cleanSourceBitmap.isRecycled) return@runCatching
            val dir = File(context.filesDir, SOURCE_CACHE_DIR)
            if (!dir.exists()) dir.mkdirs()

            val safeKey = sanitizeCacheKey(displayName)
            val imgFile = File(dir, "$safeKey.jpg")
            FileOutputStream(imgFile).use { out ->
                cleanSourceBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }

            val metaJson = JSONObject().apply {
                put("displayMillis", displayMillis)
                put("originalCaptureMillis", originalCaptureMillis)
                put("gps", JSONObject().apply {
                    put("hasFix", gps.hasFix)
                    put("latitude", gps.latitude)
                    put("longitude", gps.longitude)
                    put("accuracyMeters", gps.accuracyMeters.toDouble())
                    put("altitudeMeters", gps.altitudeMeters)
                    put("gpsTimestampMillis", gps.gpsTimestampMillis)
                    put("city", gps.city)
                    put("district", gps.district)
                    put("province", gps.province)
                    put("country", gps.country)
                    put("streetAddress", gps.streetAddress)
                    put("isOfflineAddress", gps.isOfflineAddress)
                    put("locationSource", gps.locationSource.name)
                })
                put("config", JSONObject().apply {
                    put("organizationName", config.organizationName)
                    put("customLogoPath", config.customLogoPath ?: "")
                    put("is24HourFormat", config.is24HourFormat)
                    put("dateFormatPattern", config.dateFormatPattern)
                    put("watermarkSize", config.watermarkSize.name)
                    put("watermarkOpacity", config.watermarkOpacity)
                    put("fontScale", config.fontScale.toDouble())
                    put("watermarkPosition", config.watermarkPosition.name)
                    put("showLocationTitle", config.showLocationTitle)
                    put("showFullAddress", config.showFullAddress)
                    put("showCoordinates", config.showCoordinates)
                    put("showDateTime", config.showDateTime)
                    put("showLogo", config.showLogo)
                    put("coordFormat", config.coordFormat.name)
                    put("jpegQuality", config.jpegQuality)
                })
            }
            File(dir, "$safeKey.json").writeText(metaJson.toString())

            val metaFiles = dir.listFiles { f -> f.name.endsWith(".json") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
            if (metaFiles.size > MAX_CACHED_SOURCES) {
                metaFiles.drop(MAX_CACHED_SOURCES).forEach { oldMeta ->
                    val stem = oldMeta.name.removeSuffix(".json")
                    oldMeta.delete()
                    File(dir, "$stem.jpg").delete()
                }
            }
        }
    }

    private fun jsonToGps(obj: JSONObject?): GpsSnapshot {
        if (obj == null) return GpsSnapshot()
        val sourceStr = obj.optString("locationSource", LocationSource.LIVE_GPS.name)
        return GpsSnapshot(
            hasFix = obj.optBoolean("hasFix", false),
            latitude = obj.optDouble("latitude", 0.0),
            longitude = obj.optDouble("longitude", 0.0),
            accuracyMeters = obj.optDouble("accuracyMeters", 0.0).toFloat(),
            altitudeMeters = obj.optDouble("altitudeMeters", 0.0),
            gpsTimestampMillis = obj.optLong("gpsTimestampMillis", 0L),
            city = obj.optString("city", ""),
            district = obj.optString("district", ""),
            province = obj.optString("province", ""),
            country = obj.optString("country", ""),
            streetAddress = obj.optString("streetAddress", ""),
            isOfflineAddress = obj.optBoolean("isOfflineAddress", false),
            locationSource = runCatching { LocationSource.valueOf(sourceStr) }
                .getOrDefault(LocationSource.LIVE_GPS)
        )
    }

    private fun jsonToConfig(obj: JSONObject?, fallback: WatermarkConfig): WatermarkConfig {
        if (obj == null) return fallback
        val sizeStr = obj.optString("watermarkSize", fallback.watermarkSize.name)
        val posStr = obj.optString("watermarkPosition", fallback.watermarkPosition.name)
        val coordStr = obj.optString("coordFormat", fallback.coordFormat.name)
        val logoPath = obj.optString("customLogoPath", "").ifBlank { null }
        return fallback.copy(
            organizationName = obj.optString("organizationName", fallback.organizationName),
            customLogoPath = logoPath,
            is24HourFormat = obj.optBoolean("is24HourFormat", fallback.is24HourFormat),
            dateFormatPattern = obj.optString("dateFormatPattern", fallback.dateFormatPattern),
            watermarkSize = runCatching { WatermarkSize.valueOf(sizeStr) }.getOrDefault(fallback.watermarkSize),
            watermarkOpacity = obj.optInt("watermarkOpacity", fallback.watermarkOpacity).coerceIn(30, 95),
            fontScale = obj.optDouble("fontScale", fallback.fontScale.toDouble()).toFloat().coerceIn(0.8f, 1.35f),
            watermarkPosition = runCatching { WatermarkPosition.valueOf(posStr) }.getOrDefault(fallback.watermarkPosition),
            showLocationTitle = obj.optBoolean("showLocationTitle", fallback.showLocationTitle),
            showFullAddress = obj.optBoolean("showFullAddress", fallback.showFullAddress),
            showCoordinates = obj.optBoolean("showCoordinates", fallback.showCoordinates),
            showDateTime = obj.optBoolean("showDateTime", fallback.showDateTime),
            showLogo = obj.optBoolean("showLogo", fallback.showLogo),
            coordFormat = runCatching { CoordFormat.valueOf(coordStr) }.getOrDefault(fallback.coordFormat),
            jpegQuality = obj.optInt("jpegQuality", fallback.jpegQuality).coerceIn(70, 100)
        )
    }
}
