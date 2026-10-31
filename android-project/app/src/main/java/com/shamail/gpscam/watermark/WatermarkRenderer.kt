package com.shamail.gpscam.watermark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import com.shamail.gpscam.R
import com.shamail.gpscam.data.CoordFormat
import com.shamail.gpscam.data.GpsSnapshot
import com.shamail.gpscam.data.WatermarkConfig
import com.shamail.gpscam.data.WatermarkPosition
import com.shamail.gpscam.data.WatermarkSize
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min

object WatermarkRenderer {

    private const val CREATED_BY_LABEL = "Created by:"
    private const val URDU_FOOTER_TEXT = "شمائل احمد رندھاوا) پیرا فورس سرائے عالمگیر"

    @Volatile
    private var assetUrduTypeface: Typeface? = null

    @Volatile
    private var assetUrduChecked: Boolean = false

    private val systemUrduTypeface: Typeface by lazy {
        val fontPaths = listOf(
            "/system/fonts/JameelNooriNastaleeq.ttf",
            "/system/fonts/NotoNastaliqUrdu-Regular.ttf",
            "/product/fonts/NotoNastaliqUrdu-Regular.ttf",
            "/system/fonts/NotoNastaliqUrduUI-Regular.ttf",
            "/system/fonts/NotoNaskhArabic-Regular.ttf",
            "/system/fonts/NotoSansArabic-Regular.ttf"
        )
        for (path in fontPaths) {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                runCatching { Typeface.createFromFile(file) }.getOrNull()?.let { return@lazy it }
            }
        }
        Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }

    private val isoCountryByName: Map<String, String> by lazy {
        val map = HashMap<String, String>(300)
        for (iso in Locale.getISOCountries()) {
            val name = Locale("", iso).getDisplayCountry(Locale.ENGLISH).trim()
            if (name.isNotEmpty()) {
                map[name.lowercase(Locale.US)] = iso.uppercase(Locale.US)
            }
        }
        map["pakistan"] = "PK"
        map["uae"] = "AE"
        map["united arab emirates"] = "AE"
        map["uk"] = "GB"
        map["united kingdom"] = "GB"
        map["usa"] = "US"
        map["united states"] = "US"
        map["ksa"] = "SA"
        map["saudi arabia"] = "SA"
        map
    }

    private fun resolveUrduTypeface(): Typeface {
        return assetUrduTypeface ?: systemUrduTypeface
    }

    fun loadLogoBitmap(context: Context, config: WatermarkConfig): Bitmap? {
        if (!assetUrduChecked) {
            assetUrduChecked = true
            val candidates = listOf(
                "fonts/JameelNooriNastaleeq.ttf",
                "fonts/jameel_noori_nastaleeq.ttf",
                "fonts/NotoNastaliqUrdu-Regular.ttf"
            )
            for (assetPath in candidates) {
                val tf = runCatching { Typeface.createFromAsset(context.assets, assetPath) }.getOrNull()
                if (tf != null) {
                    assetUrduTypeface = tf
                    break
                }
            }
        }

        if (!config.showLogo) return null
        val customPath = config.customLogoPath
        if (!customPath.isNullOrBlank()) {
            val f = File(customPath)
            if (f.exists()) {
                BitmapFactory.decodeFile(f.absolutePath)?.let { return it }
            }
        }
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeResource(context.resources, R.drawable.default_org_logo, bounds)
            var sample = 1
            val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
            while (maxDim / sample > 512) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeResource(context.resources, R.drawable.default_org_logo, decodeOpts)
        }.getOrNull()
    }

    fun renderOntoBitmap(
        source: Bitmap,
        config: WatermarkConfig,
        gps: GpsSnapshot,
        logoBitmap: Bitmap?,
        captureMillis: Long = System.currentTimeMillis()
    ): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        if (!config.showWatermark) {
            return output
        }
        val canvas = Canvas(output)
        drawWatermarkOverlay(
            canvas = canvas,
            containerWidth = output.width.toFloat(),
            containerHeight = output.height.toFloat(),
            config = config,
            gps = gps,
            logoBitmap = logoBitmap,
            nowMillis = captureMillis,
            anchorToBottom = true
        )
        return output
    }

    fun drawWatermarkOverlay(
        canvas: Canvas?,
        containerWidth: Float,
        containerHeight: Float,
        config: WatermarkConfig,
        gps: GpsSnapshot,
        logoBitmap: Bitmap?,
        nowMillis: Long = System.currentTimeMillis(),
        anchorToBottom: Boolean = true
    ): Float {
        if (!config.showWatermark || containerWidth <= 10f) return 0f

        val outerMargin = (containerWidth * 0.028f).coerceAtLeast(8f)
        val availableWidth = (containerWidth - outerMargin * 2f).coerceAtLeast(120f)
        val baseFraction = when (config.watermarkSize) {
            WatermarkSize.SMALL -> 0.76f
            WatermarkSize.MEDIUM -> 0.90f
            WatermarkSize.LARGE -> 0.96f
        }
        val isFullLandscapePhoto =
            anchorToBottom && containerHeight >= containerWidth * 0.45f && containerWidth > containerHeight
        val cardWidth = if (isFullLandscapePhoto) {
            min(availableWidth * baseFraction, containerHeight * 1.50f).coerceAtLeast(160f)
        } else {
            availableWidth * baseFraction
        }

        val unit = (cardWidth / 460f) * config.fontScale
        val padX = (18f * unit).coerceAtLeast(10f)
        val padTop = (15f * unit).coerceAtLeast(9f)
        val padBottom = (10f * unit).coerceAtLeast(7f)
        val cornerRadius = (16f * unit).coerceAtLeast(10f)

        val hasLogo = config.showLogo
        val logoSize = if (hasLogo) (68f * unit).coerceAtLeast(42f) else 0f
        val logoOverhang = if (hasLogo) logoSize * 0.40f else 0f
        val logoRightInset = 14f * unit
        val logoInsideDrop = logoSize - logoOverhang

        val shadowRadius = (1.6f * unit).coerceAtLeast(0.8f)
        val shadowDy = (0.7f * unit).coerceAtLeast(0.4f)
        val shadowColor = Color.argb(120, 0, 0, 0)

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 22.5f * unit
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setShadowLayer(shadowRadius, 0f, shadowDy, shadowColor)
        }

        val detailPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#D8DCE2")
            textSize = 14.5f * unit
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            setShadowLayer(shadowRadius, 0f, shadowDy, shadowColor)
        }

        val fullInnerWidth = (cardWidth - padX * 2f).toInt().coerceAtLeast(90)
        val logoClearWidth = if (hasLogo) {
            (cardWidth - padX - logoRightInset - logoSize - (12f * unit)).toInt().coerceAtLeast(80)
        } else {
            fullInnerWidth
        }

        var contentY = padTop
        val titleGap = 5.5f * unit
        val detailGap = 4.2f * unit

        val locationLayout = if (config.showLocationTitle) {
            buildLtrLayout(buildLocationHeadlineWithFlag(gps), titlePaint, logoClearWidth, 2)
        } else null
        if (locationLayout != null) {
            contentY += locationLayout.height + titleGap
        }

        val addressWidth = if (hasLogo && contentY < logoInsideDrop + (2f * unit)) {
            logoClearWidth
        } else {
            fullInnerWidth
        }
        val addressLayout = if (config.showFullAddress) {
            buildLtrLayout(gps.buildFullAddressLine(), detailPaint, addressWidth, 3)
        } else null
        if (addressLayout != null) {
            contentY += addressLayout.height + detailGap
        }

        val coordWidth = if (hasLogo && contentY < logoInsideDrop + (2f * unit)) {
            logoClearWidth
        } else {
            fullInnerWidth
        }
        val coordLayout = if (config.showCoordinates) {
            buildLtrLayout(formatWatermarkCoordinatesLine(config, gps), detailPaint, coordWidth, 2)
        } else null
        if (coordLayout != null) {
            contentY += coordLayout.height + detailGap
        }

        val dateTimeWidth = if (hasLogo && contentY < logoInsideDrop + (2f * unit)) {
            logoClearWidth
        } else {
            fullInnerWidth
        }
        val dateTimeLayout = if (config.showDateTime) {
            buildLtrLayout(formatWatermarkDateTimeLine(config, gps, nowMillis), detailPaint, dateTimeWidth, 2)
        } else null
        if (dateTimeLayout != null) {
            contentY += dateTimeLayout.height
        }

        val minPreDividerBottom = if (hasLogo) {
            maxOf(contentY, logoInsideDrop + (6f * unit))
        } else {
            contentY
        }
        val dividerGapTop = 10f * unit
        val dividerRelY = minPreDividerBottom + dividerGapTop

        // Prepare Created by + RTL Urdu footer measurements so they always fit inside cardWidth
        var enTextSize = 13.5f * unit
        var urduTextSize = 14.5f * unit
        val labelUrduGap = 6.5f * unit

        val enFooterPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = enTextSize
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            setShadowLayer(shadowRadius, 0f, shadowDy, shadowColor)
        }
        val urduFooterPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = urduTextSize
            typeface = resolveUrduTypeface()
            setShadowLayer(shadowRadius, 0f, shadowDy, shadowColor)
        }

        val maxFooterTextWidth = (cardWidth - padX * 2f).coerceAtLeast(90f)
        val rawEnW = Layout.getDesiredWidth(CREATED_BY_LABEL, enFooterPaint)
        val rawUrduW = Layout.getDesiredWidth(URDU_FOOTER_TEXT, urduFooterPaint)
        val rawCombinedW = rawEnW + labelUrduGap + rawUrduW
        if (rawCombinedW > maxFooterTextWidth && rawCombinedW > 1f) {
            val shrink = (maxFooterTextWidth / rawCombinedW).coerceIn(0.55f, 1f)
            enTextSize *= shrink
            urduTextSize *= shrink
            enFooterPaint.textSize = enTextSize
            urduFooterPaint.textSize = urduTextSize
        }

        val enLayoutWidth = ceil(Layout.getDesiredWidth(CREATED_BY_LABEL, enFooterPaint)).toInt().coerceAtLeast(1)
        val enFooterLayout = StaticLayout.Builder.obtain(
            CREATED_BY_LABEL,
            0,
            CREATED_BY_LABEL.length,
            enFooterPaint,
            enLayoutWidth
        )
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(TextDirectionHeuristics.LTR)
            .setIncludePad(true)
            .setMaxLines(1)
            .build()

        val urduLayoutWidth = ceil(Layout.getDesiredWidth(URDU_FOOTER_TEXT, urduFooterPaint)).toInt().coerceAtLeast(1)
        val urduFooterLayout = StaticLayout.Builder.obtain(
            URDU_FOOTER_TEXT,
            0,
            URDU_FOOTER_TEXT.length,
            urduFooterPaint,
            urduLayoutWidth
        )
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(TextDirectionHeuristics.RTL)
            .setIncludePad(true)
            .setMaxLines(1)
            .build()

        val enAscent = -enFooterPaint.fontMetrics.ascent
        val enDescent = enFooterPaint.fontMetrics.descent
        val urduAscent = -urduFooterPaint.fontMetrics.ascent
        val urduDescent = urduFooterPaint.fontMetrics.descent
        val maxFooterAscent = maxOf(enAscent, urduAscent)
        val maxFooterDescent = maxOf(enDescent, urduDescent)
        val footerZoneHeight = maxOf(26f * unit, maxFooterAscent + maxFooterDescent + (8f * unit))

        val cardHeight = dividerRelY + footerZoneHeight + (padBottom * 0.35f)
        val totalVisualHeight = logoOverhang + cardHeight

        if (canvas == null) {
            return totalVisualHeight + if (anchorToBottom) outerMargin * 2f else 0f
        }

        val cardLeft = when (config.watermarkPosition) {
            WatermarkPosition.BOTTOM_LEFT -> outerMargin
            WatermarkPosition.BOTTOM_CENTER -> (containerWidth - cardWidth) / 2f
            WatermarkPosition.BOTTOM_RIGHT -> containerWidth - outerMargin - cardWidth
        }
        val cardTop = if (anchorToBottom) {
            (containerHeight - outerMargin - cardHeight).coerceAtLeast(logoOverhang + 4f)
        } else {
            logoOverhang
        }
        val cardRect = RectF(cardLeft, cardTop, cardLeft + cardWidth, cardTop + cardHeight)

        // Semi-transparent dark charcoal background (no gold or yellow tint)
        val alphaInt = ((config.watermarkOpacity.coerceIn(30, 95) / 100f) * 255f).toInt()
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(alphaInt, 20, 22, 24)
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, bgPaint)

        // Subtle, thin neutral gray border
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(56, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = (1.1f * unit).coerceAtLeast(1f)
        }
        canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, borderPaint)

        // Draw dynamic GPS text stack in exact order
        val textStartX = cardRect.left + padX
        var drawY = cardRect.top + padTop

        if (locationLayout != null) {
            canvas.save()
            canvas.translate(textStartX, drawY)
            locationLayout.draw(canvas)
            canvas.restore()
            drawY += locationLayout.height + titleGap
        }

        if (addressLayout != null) {
            canvas.save()
            canvas.translate(textStartX, drawY)
            addressLayout.draw(canvas)
            canvas.restore()
            drawY += addressLayout.height + detailGap
        }

        if (coordLayout != null) {
            canvas.save()
            canvas.translate(textStartX, drawY)
            coordLayout.draw(canvas)
            canvas.restore()
            drawY += coordLayout.height + detailGap
        }

        if (dateTimeLayout != null) {
            canvas.save()
            canvas.translate(textStartX, drawY)
            dateTimeLayout.draw(canvas)
            canvas.restore()
        }

        // Thin, subtle horizontal gray divider line above footer
        val dividerY = cardRect.top + dividerRelY
        val dividerStartX = cardRect.left + (10f * unit)
        val dividerEndX = cardRect.right - (10f * unit)
        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (1.0f * unit).coerceAtLeast(1f)
            shader = LinearGradient(
                dividerStartX,
                dividerY,
                dividerEndX,
                dividerY,
                intArrayOf(
                    Color.argb(0, 255, 255, 255),
                    Color.argb(115, 235, 238, 242),
                    Color.argb(115, 235, 238, 242),
                    Color.argb(0, 255, 255, 255)
                ),
                floatArrayOf(0f, 0.06f, 0.94f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawLine(dividerStartX, dividerY, dividerEndX, dividerY, dividerPaint)

        // Draw Created by: (LTR) + exact Urdu text (RTL) centered with subtle side gray lines
        val enLineLeft = enFooterLayout.getLineLeft(0)
        val enVisualWidth = (enFooterLayout.getLineRight(0) - enLineLeft).coerceAtLeast(1f)
        val urduLineLeft = urduFooterLayout.getLineLeft(0)
        val urduVisualWidth = (urduFooterLayout.getLineRight(0) - urduLineLeft).coerceAtLeast(1f)

        val totalFooterTextWidth = enVisualWidth + labelUrduGap + urduVisualWidth
        val footerGroupLeft = cardRect.left + (cardWidth - totalFooterTextWidth) / 2f
        val footerBaselineY =
            dividerY + ((footerZoneHeight - (maxFooterAscent + maxFooterDescent)) / 2f) + maxFooterAscent

        val enDrawX = footerGroupLeft - enLineLeft
        val enDrawY = footerBaselineY - enFooterLayout.getLineBaseline(0)
        canvas.save()
        canvas.translate(enDrawX, enDrawY)
        enFooterLayout.draw(canvas)
        canvas.restore()

        val urduVisualStartX = footerGroupLeft + enVisualWidth + labelUrduGap
        val urduDrawX = urduVisualStartX - urduLineLeft
        val urduDrawY = footerBaselineY - urduFooterLayout.getLineBaseline(0)
        canvas.save()
        canvas.translate(urduDrawX, urduDrawY)
        urduFooterLayout.draw(canvas)
        canvas.restore()

        // Short thin gray decorative lines beside the footer when space permits
        val sideGap = 12f * unit
        val availSideSpace = footerGroupLeft - (cardRect.left + padX) - sideGap
        if (availSideSpace >= 14f * unit) {
            val sideLineLen = min(38f * unit, availSideSpace)
            val sideLineY = footerBaselineY - (enAscent * 0.36f)
            val sideLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(135, 226, 232, 240)
                style = Paint.Style.STROKE
                strokeWidth = (1.1f * unit).coerceAtLeast(1f)
            }
            val leftLineEnd = footerGroupLeft - sideGap
            val leftLineStart = leftLineEnd - sideLineLen
            canvas.drawLine(leftLineStart, sideLineY, leftLineEnd, sideLineY, sideLinePaint)

            val footerGroupRight = footerGroupLeft + totalFooterTextWidth
            val rightLineStart = footerGroupRight + sideGap
            val rightLineEnd = rightLineStart + sideLineLen
            canvas.drawLine(rightLineStart, sideLineY, rightLineEnd, sideLineY, sideLinePaint)
        }

        // Draw circular SHAMAIL GPS logo at upper-right corner of the watermark panel
        if (hasLogo) {
            val logoRight = cardRect.right - logoRightInset
            val logoLeft = logoRight - logoSize
            val logoTop = cardRect.top - logoOverhang
            val logoRect = RectF(logoLeft, logoTop, logoRight, logoTop + logoSize)
            drawUpperRightCircularLogo(canvas, logoRect, logoBitmap, unit)
        }

        return totalVisualHeight
    }

    private fun drawUpperRightCircularLogo(
        canvas: Canvas,
        rect: RectF,
        logoBitmap: Bitmap?,
        unit: Float
    ) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val radius = rect.width() / 2f

        val circleBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0D0F12")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, radius, circleBg)

        if (logoBitmap != null && !logoBitmap.isRecycled && logoBitmap.width > 0 && logoBitmap.height > 0) {
            canvas.save()
            val clipPath = Path().apply {
                addCircle(cx, cy, radius, Path.Direction.CW)
            }
            canvas.clipPath(clipPath)
            val minSide = minOf(logoBitmap.width, logoBitmap.height)
            val srcLeft = (logoBitmap.width - minSide) / 2
            val srcTop = (logoBitmap.height - minSide) / 2
            val srcRect = Rect(srcLeft, srcTop, srcLeft + minSide, srcTop + minSide)
            val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(logoBitmap, srcRect, rect, bmpPaint)
            canvas.restore()
        }

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(48, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = (1.0f * unit).coerceAtLeast(1f)
        }
        canvas.drawCircle(cx, cy, radius, ringPaint)
    }

    private fun buildLocationHeadlineWithFlag(gps: GpsSnapshot): String {
        val base = gps.buildLocationHeadline()
        if (!gps.hasFix || containsFlagEmoji(base)) return base

        val countryCandidate = gps.country.trim().ifEmpty {
            base.substringAfterLast(',', "").trim()
        }
        if (countryCandidate.isEmpty()) return base

        val iso = if (countryCandidate.length == 2 && countryCandidate.all { it.isLetter() }) {
            countryCandidate.uppercase(Locale.US)
        } else {
            isoCountryByName[countryCandidate.lowercase(Locale.US)]
        } ?: return base

        val flag = isoToFlagEmoji(iso)
        return if (flag.isNotEmpty()) "$base $flag" else base
    }

    private fun containsFlagEmoji(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (cp in 0x1F1E6..0x1F1FF) return true
            i += Character.charCount(cp)
        }
        return false
    }

    private fun isoToFlagEmoji(isoCode: String): String {
        if (isoCode.length != 2) return ""
        val first = isoCode[0].uppercaseChar()
        val second = isoCode[1].uppercaseChar()
        if (first !in 'A'..'Z' || second !in 'A'..'Z') return ""
        val cp1 = 0x1F1E6 + (first - 'A')
        val cp2 = 0x1F1E6 + (second - 'A')
        return String(Character.toChars(cp1)) + String(Character.toChars(cp2))
    }

    private fun formatWatermarkCoordinatesLine(config: WatermarkConfig, gps: GpsSnapshot): String {
        if (!gps.hasFix) return "Lat --.------° Long --.------°"
        return when (config.coordFormat) {
            CoordFormat.DECIMAL -> String.format(
                Locale.US,
                "Lat %.6f° Long %.6f°",
                gps.latitude,
                gps.longitude
            )
            CoordFormat.DMS -> {
                val latDms = formatSingleDms(gps.latitude, isLatitude = true)
                val lonDms = formatSingleDms(gps.longitude, isLatitude = false)
                "Lat $latDms Long $lonDms"
            }
        }
    }

    private fun formatSingleDms(coord: Double, isLatitude: Boolean): String {
        val hemisphere = if (isLatitude) {
            if (coord >= 0) "N" else "S"
        } else {
            if (coord >= 0) "E" else "W"
        }
        val absolute = abs(coord)
        val degrees = absolute.toInt()
        val minutesFull = (absolute - degrees) * 60.0
        val minutes = minutesFull.toInt()
        val seconds = (minutesFull - minutes) * 60.0
        return String.format(Locale.US, "%d°%02d'%04.1f\"%s", degrees, minutes, seconds, hemisphere)
    }

    private fun formatWatermarkDateTimeLine(
        config: WatermarkConfig,
        gps: GpsSnapshot,
        nowMillis: Long
    ): String {
        val targetMillis = config.resolveTimestampMillis(gps, nowMillis)
        val targetDate = Date(targetMillis)
        val tz = TimeZone.getDefault()

        val datePattern = when (config.dateFormatPattern) {
            "yyyy-MM-dd" -> "EEEE, yyyy-MM-dd"
            "EEE, dd MMM yyyy" -> "EEE, dd MMM yyyy"
            "dd/MM/yyyy", "dd MMM yyyy" -> "EEEE, dd/MM/yyyy"
            else -> "EEEE, dd/MM/yyyy"
        }
        val timePattern = if (config.is24HourFormat) "HH:mm" else "hh:mm a"

        val datePart = runCatching {
            SimpleDateFormat(datePattern, Locale.US).apply { timeZone = tz }.format(targetDate)
        }.getOrElse {
            SimpleDateFormat("EEEE, dd/MM/yyyy", Locale.US).apply { timeZone = tz }.format(targetDate)
        }

        val timePart = SimpleDateFormat(timePattern, Locale.US)
            .apply { timeZone = tz }
            .format(targetDate)
            .uppercase(Locale.US)

        val offsetMs = tz.getOffset(targetMillis)
        val sign = if (offsetMs >= 0) "+" else "-"
        val totalMinutes = abs(offsetMs) / 60000
        val gmtPart = String.format(Locale.US, "GMT %s%02d:%02d", sign, totalMinutes / 60, totalMinutes % 60)

        return "$datePart $timePart $gmtPart"
    }

    private fun buildLtrLayout(
        text: String,
        paint: TextPaint,
        width: Int,
        maxLines: Int
    ): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(TextDirectionHeuristics.LTR)
            .setLineSpacing(0f, 1.10f)
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
    }
}
