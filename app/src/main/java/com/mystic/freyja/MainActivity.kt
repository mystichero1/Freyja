package com.mystic.freyja

import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.mystic.freyja.databinding.ActivityMainBinding
import com.mystic.freyja.odin.FlashRunner
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val selectedFiles = mutableMapOf<String, String>()
    private var activeSlotTarget: String? = null

    private lateinit var usbManager: UsbManager
    private val handler = Handler(Looper.getMainLooper())
    private var isDetecting = false
    private var isFlashing = false
    private val ACTION_USB_PERMISSION = "com.mystic.freyja.USB_PERMISSION"

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }

                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            device?.let {
                                appendLog("USB permission granted for [ID: ${it.deviceId}]")
                            }
                        } else {
                            appendLog("USB permission denied by user.")
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    appendLog("USB device attached.")
                    checkForSamsungDevice()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    appendLog("USB device detached.")
                    checkForSamsungDevice()
                }
            }
        }
    }

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { sourceUri ->
            activeSlotTarget?.let { slotName ->
                val fileName = queryFileName(sourceUri) ?: "unknown_file"

                val isValid = if (slotName == "PIT") {
                    fileName.endsWith(".pit", ignoreCase = true)
                } else {
                    fileName.endsWith(".img", ignoreCase = true)
                }

                if (!isValid) {
                    val requiredExt = if (slotName == "PIT") ".pit" else ".img"
                    appendLog("Error: $slotName requires a $requiredExt file (selected: $fileName)")
                    return@let
                }

                appendLog("Copying $fileName into app storage...")

                Thread {
                    val cachedFile = copyUriToCache(sourceUri, "$slotName${if (slotName == "PIT") ".pit" else ".img"}")
                    handler.post {
                        if (cachedFile != null) {
                            selectedFiles[slotName] = cachedFile.absolutePath
                            updateSlotUI(slotName, fileName)
                            appendLog("Selected $slotName: $fileName")
                        }
                    }
                }.start()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }

        setupClickListeners()
        checkForSamsungDevice()
        showStartupWarning()
        AppUpdater(this).checkForUpdate()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(usbReceiver)
    }

    private fun setupClickListeners() {
        binding.btnBoot.setOnClickListener { openPicker("BOOT") }
        binding.btnRecovery.setOnClickListener { openPicker("RECOVERY") }
        binding.btnSuper.setOnClickListener { openPicker("SUPER") }
        binding.btnDtbo.setOnClickListener { openPicker("DTBO") }
        binding.btnVbmeta.setOnClickListener { openPicker("VBMETA") }
        binding.btnPit.setOnClickListener { openPicker("PIT") }

        binding.btnDetect.setOnClickListener {
            runDetectScan()
        }

        binding.btnStart.setOnClickListener {
            runFlash()
        }

        binding.btnReset.setOnClickListener {
            selectedFiles.clear()
            resetSlotUI()
            binding.txtConsole.text = "Freyja engine ready.\nFreyja Native Engine Active\n"
            binding.progressFlash.progress = 0
            appendLog("Freyja states cleared. Engine reset.")
        }

        binding.btnExit.setOnClickListener {
            finish()
        }
    }

    /**
     * User-initiated scan: shows a real "detecting" state (button disabled,
     * dot neutral, short delay) instead of instantly flipping straight to
     * the same idle text - previously this made Detect look like it did
     * nothing at all.
     */
    private fun runDetectScan() {
        if (isDetecting) return
        isDetecting = true

        binding.btnDetect.isEnabled = false
        binding.statusDot.setBackgroundColor(Color.parseColor("#FFC107"))
        binding.txtStatus.text = "ID:COM Detecting..."
        appendLog("Executing device detection scan...")

        handler.postDelayed({
            checkForSamsungDevice()
            binding.btnDetect.isEnabled = true
            isDetecting = false
        }, 500)
    }

    /**
     * Wires btnStart to FlashRunner. Kicks the Heimdall-style flash choreography
     * off on a background thread and streams log lines / progress to the UI.
     */
    private fun runFlash() {
        if (isFlashing) return

        if (selectedFiles.isEmpty()) {
            appendLog("Error: No partition files selected to flash.")
            return
        }

        val device = usbManager.deviceList.values.firstOrNull { it.vendorId == 1256 }
            ?: run {
                appendLog("Error: No Samsung device in Download Mode detected.")
                return
            }

        if (!usbManager.hasPermission(device)) {
            appendLog("Error: USB permission not granted. Press DETECT to request it.")
            return
        }

        isFlashing = true
        binding.btnStart.isEnabled = false
        binding.btnDetect.isEnabled = false
        binding.progressFlash.progress = 0
        appendLog("Initializing flashing sequence with ${selectedFiles.size} entries...")

        Thread {
            val onLog: (String) -> Unit = { message ->
                handler.post { appendLog(message) }
            }
            val onProgress: (Int) -> Unit = { percent ->
                handler.post { binding.progressFlash.progress = percent }
            }

            val runner = FlashRunner(
                usbManager, device, selectedFiles.toMap(), onLog, onProgress,
                noReboot = binding.chkNoReboot.isChecked,
                verbose = binding.chkVerbose.isChecked
            )
            val success = runner.execute()

            handler.post {
                if (success) {
                    appendLog("Flash complete. Device reboot initiated.")
                } else {
                    appendLog("Flash aborted.")
                }
                binding.btnStart.isEnabled = true
                binding.btnDetect.isEnabled = true
                isFlashing = false
            }
        }.start()
    }

    private fun openPicker(slotName: String) {
        activeSlotTarget = slotName
        filePickerLauncher.launch(arrayOf("*/*"))
    }

    private fun queryFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        result = it.getString(nameIndex)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != -1 && cut != null) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }

    private fun copyUriToCache(uri: Uri, targetFileName: String): File? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val outputFile = File(cacheDir, targetFileName)
            val outputStream = FileOutputStream(outputFile)
            inputStream.copyTo(outputStream)
            inputStream.close()
            outputStream.close()
            outputFile
        } catch (e: Exception) {
            handler.post { appendLog("File copy error: ${e.localizedMessage}") }
            null
        }
    }

    private fun updateSlotUI(slot: String, fileName: String) {
        when (slot) {
            "BOOT" -> binding.txtBoot.text = fileName
            "RECOVERY" -> binding.txtRecovery.text = fileName
            "SUPER" -> binding.txtSuper.text = fileName
            "DTBO" -> binding.txtDtbo.text = fileName
            "VBMETA" -> binding.txtVbmeta.text = fileName
            "PIT" -> binding.txtPit.text = fileName
        }
    }

    private fun resetSlotUI() {
        binding.txtBoot.text = "(no file selected)"
        binding.txtRecovery.text = "(no file selected)"
        binding.txtSuper.text = "(no file selected)"
        binding.txtDtbo.text = "(no file selected)"
        binding.txtVbmeta.text = "(no file selected)"
        binding.txtPit.text = "(no file selected)"
    }

    private fun checkForSamsungDevice() {
        val deviceList = usbManager.deviceList
        var found = false

        for (device in deviceList.values) {
            // Samsung Vendor ID is 0x04E8 (1256)
            if (device.vendorId == 1256) {
                found = true
                binding.statusDot.setBackgroundColor(Color.GREEN)
                binding.txtStatus.text = "ID:COM Connected [Device ID: ${device.deviceId}]"
                appendLog("Samsung device detected in download mode (VID: 0x04E8).")

                if (!usbManager.hasPermission(device)) {
                    val flags = when {
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE or
                                PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                        else ->
                            PendingIntent.FLAG_UPDATE_CURRENT
                    }
                    val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
                    usbManager.requestPermission(device, permissionIntent)
                    appendLog("Requesting USB access permission...")
                }
                break
            }
        }

        if (!found) {
            binding.statusDot.setBackgroundColor(Color.RED)
            binding.txtStatus.text = "ID:COM No device detected."
            appendLog("No Samsung devices in Download Mode found.")
        }
    }

    private fun appendLog(message: String) {
        val currentText = binding.txtConsole.text.toString()
        binding.txtConsole.text = "$currentText\n$message"
    }

    private fun showStartupWarning() {
        AlertDialog.Builder(this)
            .setTitle("Before you continue")
            .setMessage(
                "1. Make sure USB OTG is enabled in your phone's settings " +
                "(Settings > Additional settings > OTG connection on some devices) " +
                "before connecting the target device.\n\n" +
                "2. Flashing firmware can permanently damage or brick your device " +
                "if interrupted or if the wrong file is used for the wrong model. " +
                "It may also trip Knox, void your warranty, and wipe data.\n\n" +
                "Proceed only if you understand the risks and have selected the " +
                "correct files for the exact device you're flashing."
            )
            .setPositiveButton("I Understand") { dialog, _ -> dialog.dismiss() }
            .setCancelable(false)
            .show()
    }
}