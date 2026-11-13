package com.shamail.gpscam

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.shamail.gpscam.data.AppPreferences
import com.shamail.gpscam.data.AuthManager
import com.shamail.gpscam.data.TimestampMode
import com.shamail.gpscam.databinding.ActivityPhotoDetailBinding
import com.shamail.gpscam.storage.EditablePhotoContext
import com.shamail.gpscam.storage.PhotoStorageManager
import com.shamail.gpscam.ui.SequentialDateTimePicker
import com.shamail.gpscam.watermark.WatermarkRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PhotoDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhotoDetailBinding
    private lateinit var prefs: AppPreferences

    private lateinit var currentPhotoUri: Uri
    private var currentPhotoName: String = "SHAMAIL_GPS.jpg"
    private var currentFilePath: String? = null
    private var currentProvenance: String = "Verified Capture Time"

    private var editableContext: EditablePhotoContext? = null
    private var previewBitmap: Bitmap? = null
    private var pendingEditedMillis: Long? = null
    private var isBusy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPreferences(this)

        val uriStr = intent.getStringExtra("photo_uri") ?: run {
            finish()
            return
        }
        currentPhotoUri = Uri.parse(uriStr)
        currentPhotoName = intent.getStringExtra("photo_name") ?: "SHAMAIL_GPS.jpg"
        currentFilePath = intent.getStringExtra("photo_path")
        currentProvenance = intent.getStringExtra("photo_provenance") ?: "Verified Capture Time"

        refreshHeaderAndPhotoView()
        applyAdminVisibility()

        binding.btnBackDetail.setOnClickListener { finish() }

        binding.btnSharePhoto.setOnClickListener {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, currentPhotoUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share GPS Watermarked Photo"))
        }

        binding.btnDeletePhoto.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Delete Photo?")
                .setMessage("Permanently remove $currentPhotoName from ${PhotoStorageManager.RELATIVE_PICTURES_PATH}?")
                .setPositiveButton("Delete") { _, _ ->
                    val deleted = PhotoStorageManager.deletePhoto(this, currentPhotoUri, currentFilePath)
                    if (deleted) {
                        Toast.makeText(this, "Photo deleted", Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        Toast.makeText(this, "Unable to delete photo on this Android version", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnEditPhotoDateTime.setOnClickListener {
            if (!AuthManager.isAdmin(this)) return@setOnClickListener
            startDateTimeEditFlow()
        }

        binding.btnViewMetadataHistory.setOnClickListener {
            showMetadataAndHistoryDialog()
        }

        binding.tvDetailMeta.setOnClickListener {
            showMetadataAndHistoryDialog()
        }

        binding.btnChangeEditedTime.setOnClickListener {
            if (!AuthManager.isAdmin(this)) return@setOnClickListener
            val ctx = editableContext ?: return@setOnClickListener
            val appCfg = prefs.loadConfig()
            SequentialDateTimePicker.launch(
                activity = this,
                initialMillis = pendingEditedMillis ?: ctx.originalDisplayMillis,
                is24HourFormat = appCfg.is24HourFormat,
                dateFormatPattern = appCfg.dateFormatPattern
            ) { confirmedMillis ->
                renderEditPreview(confirmedMillis)
            }
        }

        binding.btnCancelEditPreview.setOnClickListener {
            cancelEditPreview()
        }

        binding.btnSaveEditedCopy.setOnClickListener {
            if (!AuthManager.isAdmin(this)) return@setOnClickListener
            savePreviewedCopy()
        }
    }

    override fun onResume() {
        super.onResume()
        applyAdminVisibility()
        if (!AuthManager.isAdmin(this) && pendingEditedMillis != null) {
            cancelEditPreview()
        }
    }

    private fun applyAdminVisibility() {
        val isAdmin = AuthManager.isAdmin(this)
        binding.btnEditPhotoDateTime.visibility = if (isAdmin) View.VISIBLE else View.GONE
        binding.btnEditPhotoDateTime.isEnabled = isAdmin
    }

    private fun refreshHeaderAndPhotoView() {
        binding.tvDetailTitle.text = currentPhotoName
        binding.tvDetailMeta.text = "${PhotoStorageManager.RELATIVE_PICTURES_PATH} · $currentProvenance"
        binding.ivFullPhoto.setImageURI(currentPhotoUri)
    }

    private fun startDateTimeEditFlow() {
        if (!AuthManager.isAdmin(this) || isBusy) return
        val existingCtx = editableContext
        val appCfg = prefs.loadConfig()
        if (existingCtx != null) {
            SequentialDateTimePicker.launch(
                activity = this,
                initialMillis = pendingEditedMillis ?: existingCtx.originalDisplayMillis,
                is24HourFormat = appCfg.is24HourFormat,
                dateFormatPattern = appCfg.dateFormatPattern
            ) { confirmedMillis ->
                renderEditPreview(confirmedMillis)
            }
            return
        }

        isBusy = true
        binding.btnEditPhotoDateTime.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            val loaded = PhotoStorageManager.loadEditablePhotoContext(
                context = this@PhotoDetailActivity,
                photoUri = currentPhotoUri,
                displayName = currentPhotoName,
                filePath = currentFilePath,
                fallbackConfig = appCfg
            )
            withContext(Dispatchers.Main) {
                isBusy = false
                binding.btnEditPhotoDateTime.isEnabled = AuthManager.isAdmin(this@PhotoDetailActivity)
                if (loaded == null) {
                    Toast.makeText(
                        this@PhotoDetailActivity,
                        "Unable to load photo for Date & Time editing",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@withContext
                }
                editableContext = loaded
                SequentialDateTimePicker.launch(
                    activity = this@PhotoDetailActivity,
                    initialMillis = loaded.originalDisplayMillis,
                    is24HourFormat = appCfg.is24HourFormat,
                    dateFormatPattern = appCfg.dateFormatPattern
                ) { confirmedMillis ->
                    renderEditPreview(confirmedMillis)
                }
            }
        }
    }

    private fun renderEditPreview(confirmedMillis: Long) {
        if (!AuthManager.isAdmin(this)) return
        val ctx = editableContext ?: return
        val appCfg = prefs.loadConfig()
        val previewCfg = ctx.config.copy(
            timestampMode = TimestampMode.MANUAL,
            manualTimestampMillis = confirmedMillis,
            is24HourFormat = appCfg.is24HourFormat,
            dateFormatPattern = appCfg.dateFormatPattern,
            showWatermark = true
        )

        isBusy = true
        binding.btnSaveEditedCopy.isEnabled = false
        lifecycleScope.launch(Dispatchers.Default) {
            val logoBmp = WatermarkRenderer.loadLogoBitmap(this@PhotoDetailActivity, previewCfg)
            val rendered = WatermarkRenderer.renderOntoBitmap(
                source = ctx.sourceBitmap,
                config = previewCfg,
                gps = ctx.gps,
                logoBitmap = logoBmp,
                captureMillis = confirmedMillis,
                forceOpaqueBackground = !ctx.hasCleanOriginal
            )
            val formattedStamp = previewCfg.formatTimestamp(ctx.gps, confirmedMillis)
            withContext(Dispatchers.Main) {
                isBusy = false
                binding.btnSaveEditedCopy.isEnabled = true
                pendingEditedMillis = confirmedMillis
                val oldPreview = previewBitmap
                previewBitmap = rendered
                binding.ivFullPhoto.setImageBitmap(rendered)
                if (oldPreview != null && oldPreview != rendered && oldPreview != ctx.sourceBitmap && !oldPreview.isRecycled) {
                    oldPreview.recycle()
                }
                binding.tvPreviewBanner.text = "Preview: $formattedStamp · Original will not be changed"
                binding.tvDetailMeta.text = "Previewing new Date & Time copy (not yet saved)"
                binding.layoutPreviewConfirmBar.visibility = View.VISIBLE
            }
        }
    }

    private fun cancelEditPreview() {
        pendingEditedMillis = null
        binding.layoutPreviewConfirmBar.visibility = View.GONE
        refreshHeaderAndPhotoView()
        val old = previewBitmap
        previewBitmap = null
        if (old != null && !old.isRecycled) {
            old.recycle()
        }
    }

    private fun savePreviewedCopy() {
        if (!AuthManager.isAdmin(this) || isBusy) return
        val ctx = editableContext ?: return
        val editedMillis = pendingEditedMillis ?: return
        val appCfg = prefs.loadConfig()

        isBusy = true
        binding.btnSaveEditedCopy.isEnabled = false
        binding.btnChangeEditedTime.isEnabled = false
        binding.btnCancelEditPreview.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            val savedPair = PhotoStorageManager.saveEditedDateTimeCopy(
                context = this@PhotoDetailActivity,
                editable = ctx,
                editedMillis = editedMillis,
                is24HourFormat = appCfg.is24HourFormat,
                dateFormatPattern = appCfg.dateFormatPattern
            )
            withContext(Dispatchers.Main) {
                isBusy = false
                binding.btnSaveEditedCopy.isEnabled = true
                binding.btnChangeEditedTime.isEnabled = true
                binding.btnCancelEditPreview.isEnabled = true

                if (savedPair != null) {
                    val (newUri, newName) = savedPair
                    currentPhotoUri = newUri
                    currentPhotoName = newName
                    currentFilePath = null
                    currentProvenance = "Custom Date & Time"
                    pendingEditedMillis = null
                    binding.layoutPreviewConfirmBar.visibility = View.GONE
                    refreshHeaderAndPhotoView()

                    val oldPreview = previewBitmap
                    previewBitmap = null
                    if (oldPreview != null && !oldPreview.isRecycled) {
                        oldPreview.recycle()
                    }
                    editableContext?.let { oldCtx ->
                        if (!oldCtx.sourceBitmap.isRecycled) oldCtx.sourceBitmap.recycle()
                    }
                    editableContext = null

                    Toast.makeText(
                        this@PhotoDetailActivity,
                        "Saved separate copy: $newName",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        this@PhotoDetailActivity,
                        "Failed to save new photo copy",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showMetadataAndHistoryDialog() {
        val uriSnapshot = currentPhotoUri
        val nameSnapshot = currentPhotoName
        val pathSnapshot = currentFilePath

        lifecycleScope.launch(Dispatchers.IO) {
            val record = PhotoStorageManager.loadPhotoMetadataRecord(
                context = this@PhotoDetailActivity,
                photoUri = uriSnapshot,
                displayName = nameSnapshot,
                filePath = pathSnapshot
            )
            withContext(Dispatchers.Main) {
                val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.US)
                val sb = StringBuilder()

                sb.append("ORIGINAL CAPTURE INFO (EXIF PRESERVED)\n")
                sb.append("• Capture Date & Time: ")
                sb.append(record.originalCaptureMillis?.let { fmt.format(Date(it)) } ?: "Not embedded in source")
                sb.append("\n• Original GPS Coordinates: ")
                if (record.originalLatitude != null && record.originalLongitude != null) {
                    sb.append(String.format(Locale.US, "%.6f°, %.6f°", record.originalLatitude, record.originalLongitude))
                } else {
                    sb.append("Not recorded")
                }
                if (!record.originalLocationTitle.isNullOrBlank()) {
                    sb.append("\n• Original Place: ${record.originalLocationTitle}")
                }
                if (!record.cameraInfo.isNullOrBlank()) {
                    sb.append("\n• Camera Device: ${record.cameraInfo}")
                }
                if (!record.sourcePhotoName.isNullOrBlank()) {
                    sb.append("\n• Source Photo: ${record.sourcePhotoName}")
                }

                sb.append("\n\nWATERMARK DISPLAY INFO\n")
                sb.append("• Displayed Time (${if (record.isCustomTimestamp) "Custom Info" else "Original Capture"}): ")
                sb.append(fmt.format(Date(record.watermarkDateTimeMillis)))
                if (record.watermarkLocationTitle.isNotBlank() || record.watermarkLatitude != null) {
                    sb.append("\n• Displayed Location (${if (record.isCustomLocation) "Custom Info" else "Original GPS"}): ")
                    sb.append(record.watermarkLocationTitle.ifBlank { "Coordinates attached" })
                }
                if (record.watermarkAddress.isNotBlank()) {
                    sb.append("\n• Displayed Address: ${record.watermarkAddress}")
                }
                if (record.watermarkLatitude != null && record.watermarkLongitude != null) {
                    sb.append(
                        String.format(
                            Locale.US,
                            "\n• Displayed Coords: %.6f°, %.6f°",
                            record.watermarkLatitude,
                            record.watermarkLongitude
                        )
                    )
                }

                sb.append("\n\nEDIT HISTORY\n")
                if (record.history.isEmpty()) {
                    sb.append("• Original photo · No custom watermark edits recorded.")
                } else {
                    record.history.forEachIndexed { idx, entry ->
                        sb.append("${idx + 1}. ${entry.actionLabel}\n")
                        sb.append("   Recorded: ${fmt.format(Date(entry.recordedAtMillis))}\n")
                        if (!entry.sourcePhotoName.isNullOrBlank()) {
                            sb.append("   Source: ${entry.sourcePhotoName}\n")
                        }
                        if (entry.customDateTimeMillis != null) {
                            sb.append("   Custom Time: ${fmt.format(Date(entry.customDateTimeMillis))}\n")
                        }
                        if (!entry.customLocationTitle.isNullOrBlank()) {
                            sb.append("   Custom Place: ${entry.customLocationTitle}\n")
                        }
                    }
                }

                AlertDialog.Builder(this@PhotoDetailActivity)
                    .setTitle("Photo Info & Edit History")
                    .setMessage(sb.toString().trim())
                    .setPositiveButton("Close", null)
                    .show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.ivFullPhoto.setImageDrawable(null)
        previewBitmap?.let { if (!it.isRecycled) it.recycle() }
        editableContext?.let { if (!it.sourceBitmap.isRecycled) it.sourceBitmap.recycle() }
    }
}
