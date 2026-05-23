package com.example.telescopeapp

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.hardware.camera2.params.*
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.telescopeapp.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

enum class CameraMode { AUTO, PRO, HDR }
enum class ManualParameter { ISO, SHUTTER, APERTURE, WB, EV, FOCUS, CONTRAST, SATURATION }

class MainActivity : AppCompatActivity() {

    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var cameraManager: CameraManager

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var previewSurface: Surface? = null
    private var imageReader: ImageReader? = null
    private var mediaRecorder: android.media.MediaRecorder? = null
    private var isRecording = false
    private var videoUri: android.net.Uri? = null
    private var mediaActionSound: android.media.MediaActionSound? = null
    private var lastMediaUri: android.net.Uri? = null
    private var bestPreviewSize: Size? = null
    private var bestJpegSize: Size? = null
    private var bestRawSize: Size? = null
    private var rawImageReader: ImageReader? = null
    private var currentCharacteristics: CameraCharacteristics? = null
    private var supportedOisModes: IntArray? = null
    private var supportedEisModes: IntArray? = null
    private var supportedAfModes: IntArray? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val lensTextViews = mutableListOf<TextView>()
    private var currentCameraId: String? = null
    
    // Camera Control State
    private var currentCameraMode = CameraMode.AUTO
    private var currentManualParam: ManualParameter? = null  // H slider param
    private var currentVParam: ManualParameter? = null       // V slider param

    // Parameter Ranges
    private var isoRange: android.util.Range<Int>? = null
    private var exposureRange: android.util.Range<Long>? = null
    private var apertureList: FloatArray? = null
    private var evRange: android.util.Range<Int>? = null
    private var evStep: android.util.Rational? = null
    private var minFocusDistance: Float = 0f

    // Current Parameter Values
    private var currentIso: Int = 100
    private var currentExposureNs: Long = 10000000L // 1/100s
    private var currentAperture: Float? = null
    private var currentWbMode: Int = CaptureRequest.CONTROL_AWB_MODE_AUTO
    private var currentEv: Int = 0
    private var manualFocusDistance: Float = 0f

    private val paramTextViews = mutableListOf<TextView>()
    
    private var histogramRunnable: java.lang.Runnable? = null
    private var isHistogramEnabled = false
    private var isGridLinesEnabled = false
    private var isHdrEnabled = false
    private var timerMode = 0 // 0, 3, 10
    private var isFlipEnabled = true
    private var isTopMenuExpanded = false
    private var currentExpandedMenuId: Int = -1
    private var isUpdatingSlider = false  // 防止水平/垂直滑桿雙向更新循環
    private var hSliderCallback: ((Float) -> Unit)? = null  // 通用水平滑桿 callback
    private var vSliderCallback: ((Float) -> Unit)? = null  // 通用垂直滑桿 callback
    private var isVoiceControlEnabled = false
    private var isRawEnabled = false
    private var isSuperHdrEnabled = false
    private var currentStabMode = 3 // 0: OFF, 1: OIS, 2: STD (OIS+EIS), 3: PRO (OIS+EIS+Preview)
    private var isDisplayInfoEnabled = false
    private var superHdrMinIso = 50
    private var superHdrMaxIso = 800
    // EV 補償
    private var isEvEnabled = false
    private var currentEvOffset = 0f  // -3.0 ~ +3.0
    private val EV_SAFE_SHUTTER_NS = 33_333_333L  // 1/30s
    private val EV_MAX_SHUTTER_NS  = 125_000L      // 1/8000s
    private var currentStyleIndex = 0 // Style LUT
    private val styleNames = arrayOf("None", "Vivid", "Film", "B&W", "Cool")
    private var isProGradingEnabled = false
    private var gradingContrast = 1.0f
    private var gradingSaturation = 1.0f
    private var gradingExposure = 1.0f // Brightness multiplier
    
    private var isPeakingEnabled = false
    private var isLevelEnabled = false
    private var customLutBitmap: Bitmap? = null
    private var customLutSize: Int = 0
    private var currentLutName: String? = null
    private var customMediaList = listOf<MediaItem>()
    private var customMediaIndex = 0
    private var currentZoomLabel = "1x"
    
    private val zoomConfigs = mutableListOf<ZoomConfig>()
    
    private fun getSupportedIsoPresets(): List<Int> {
        val min = isoRange?.lower ?: 50
        val max = isoRange?.upper ?: 3200
        val baseList = listOf(50, 100, 200, 320, 400, 800, 1600, 3200, 6400, 12800)
        val filtered = baseList.filter { it in min..max }.toMutableList()
        if (filtered.isEmpty()) {
            filtered.add(min)
            filtered.add(max)
        }
        return filtered.distinct().sorted()
    }

    private fun getSupportedShutterPresets(): List<Double> {
        val minNs = exposureRange?.lower ?: 1000000L
        val maxNs = exposureRange?.upper ?: 1000000000L
        val baseList = listOf(
            1.0/8000, 1.0/4000, 1.0/2000, 1.0/1000, 1.0/500, 1.0/250, 1.0/125, 
            1.0/60, 1.0/30, 1.0/15, 1.0/8, 1.0/4, 1.0/2, 1.0, 2.0, 4.0, 8.0, 15.0, 30.0
        )
        val filtered = baseList.filter { sec ->
            val ns = (sec * 1_000_000_000).toLong()
            ns in minNs..maxNs
        }.toMutableList()
        if (filtered.isEmpty()) {
            filtered.add(minNs / 1_000_000_000.0)
            filtered.add(maxNs / 1_000_000_000.0)
        }
        return filtered.distinct().sorted()
    }

    private fun getJpegOrientation(deviceOrientationDegrees: Int): Int {
        val sensorOrientation = currentCharacteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val deviceOrientation = (deviceOrientationDegrees + 45) / 90 * 90
        val facingFront = currentCharacteristics?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        val sign = if (facingFront) -1 else 1
        var rotation = (sensorOrientation + deviceOrientation * sign + 360) % 360
        if (isFlipEnabled) {
            rotation = (rotation + 180) % 360
        }
        return rotation
    }

    private fun getStandardJpegOrientation(deviceOrientationDegrees: Int): Int {
        val sensorOrientation = currentCharacteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val deviceOrientation = (deviceOrientationDegrees + 45) / 90 * 90
        val facingFront = currentCharacteristics?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        val sign = if (facingFront) -1 else 1
        return (sensorOrientation + deviceOrientation * sign + 360) % 360
    }

    private fun getExifRotation(bytes: ByteArray): Int {
        try {
            val inputStream = java.io.ByteArrayInputStream(bytes)
            val exifInterface = android.media.ExifInterface(inputStream)
            val orientation = exifInterface.getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL
            )
            return when (orientation) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read EXIF orientation", e)
            return 0
        }
    }

    private fun initializeZoomConfigs() {
        val backCameras = mutableListOf<Pair<String, Float>>()
        val potentialIds = (0..20).map { it.toString() }
        for (id in potentialIds) {
            try {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    val primaryFocalLength = focalLengths?.firstOrNull() ?: 0f
                    if (primaryFocalLength > 0f) {
                        backCameras.add(Pair(id, primaryFocalLength))
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        val uniqueBackCameras = backCameras.sortedBy { it.second }
            .distinctBy { it.second }

        zoomConfigs.clear()
        if (uniqueBackCameras.isNotEmpty()) {
            val uw = uniqueBackCameras[0]
            zoomConfigs.add(ZoomConfig("0.5x", uw.first))

            val main = uniqueBackCameras.getOrNull(1) ?: uw
            zoomConfigs.add(ZoomConfig("1x", main.first))

            zoomConfigs.add(ZoomConfig("2x", main.first, false, 2.0f))

            val tele32 = uniqueBackCameras.getOrNull(2)
            if (tele32 != null) {
                zoomConfigs.add(ZoomConfig("3.2x", tele32.first, true))
            } else {
                zoomConfigs.add(ZoomConfig("3.2x", main.first, false, 3.2f))
            }

            val tele5 = uniqueBackCameras.getOrNull(3)
            if (tele5 != null) {
                zoomConfigs.add(ZoomConfig("5x", tele5.first, true))
            } else if (tele32 != null) {
                zoomConfigs.add(ZoomConfig("5x", tele32.first, true, 1.56f))
            } else {
                zoomConfigs.add(ZoomConfig("5x", main.first, true, 5.0f))
            }
        } else {
            zoomConfigs.addAll(listOf(
                ZoomConfig("0.5x", "2"),
                ZoomConfig("1x", "0"),
                ZoomConfig("2x", "0", false, 2.0f),
                ZoomConfig("3.2x", "3"),
                ZoomConfig("5x", "4", true)
            ))
        }
    }

    private val lensFlipSettings = mutableMapOf<String, Boolean>()
    private var lastCaptureRotation = 0
    private var currentDeviceOrientation = 0
    private var orientationEventListener: android.view.OrientationEventListener? = null

    // 縮時攝影與不活動偵測
    private var timeLapseIntervalMs: Long = 0
    private val timeLapseHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val inactivityHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastActivityTime: Long = System.currentTimeMillis()
    private val INACTIVITY_TIMEOUT_MS = 10 * 60 * 1000L // 10 分鐘
    
    private lateinit var sensorManager: android.hardware.SensorManager
    private var accelerometer: android.hardware.Sensor? = null
    private var magnetometer: android.hardware.Sensor? = null
    private var gravityValues = FloatArray(3)
    private var magneticValues = FloatArray(3)
    
    private val lutPickerLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        uri?.let { loadCustomLut(it) }
    }
    
    private val hdrImageBuffer = Collections.synchronizedList(mutableListOf<ByteArray>())
    private var lastAutoIso: Int = 100
    private var lastAutoExposureNs: Long = 10000000L
    private var speechRecognizer: android.speech.SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        // 全螢幕沉浸模式 (隱藏狀態列與導覽列)
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
        )

        // 保持螢幕長亮，防止相機開啟時系統自動變暗或休眠
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        orientationEventListener = object : android.view.OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                currentDeviceOrientation = when {
                    orientation >= 315 || orientation < 45 -> 0
                    orientation in 45..134 -> 90
                    orientation in 135..224 -> 180
                    else -> 270
                }
            }
        }

        if (allPermissionsGranted()) {
            setupDynamicLenses()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        viewBinding.imageCaptureButton.setOnClickListener {
            resetInactivityTimer()
            if (timerMode > 0) {
                startCountdown()
            } else {
                takePhoto()
            }
        }
        viewBinding.videoCaptureButton.setOnClickListener {
            if (isRecording) stopRecordingVideo() else startRecordingVideo()
        }

        // Top Menu Setup
        viewBinding.btnExpandMenu.setOnClickListener {
            isTopMenuExpanded = !isTopMenuExpanded
            viewBinding.topMenuScroll.visibility = if (isTopMenuExpanded) android.view.View.VISIBLE else android.view.View.GONE
            viewBinding.btnExpandMenu.animate().rotation(if (isTopMenuExpanded) 180f else 0f).setDuration(300).start()
        }

        // --- Top Menu Listeners ---
        // 純開關型：直接 toggle，不展開次選單
        viewBinding.btnToggleGrid.setOnClickListener {
            resetInactivityTimer(); closeSubmenu()
            isGridLinesEnabled = !isGridLinesEnabled
            viewBinding.gridLinesLayout.visibility = if (isGridLinesEnabled) android.view.View.VISIBLE else android.view.View.GONE
            updateTopMenuUI()
        }

        viewBinding.btnToggleHistogram.setOnClickListener {
            resetInactivityTimer(); closeSubmenu()
            isHistogramEnabled = !isHistogramEnabled
            if (isHistogramEnabled) {
                viewBinding.histogramView.visibility = android.view.View.VISIBLE
                startHistogramAnalysis()
            } else {
                viewBinding.histogramView.visibility = android.view.View.GONE
                stopHistogramAnalysis()
            }
            updateTopMenuUI()
        }

        // 模式切換：有意義的兩選 → 次選單
        viewBinding.btnToggleHdr.setOnClickListener {
            showInlineSubmenu(R.id.btn_toggle_hdr, arrayOf("標準 (AUTO)", "高動態 (HDR)")) { which ->
                currentCameraMode = if (which == 1) CameraMode.HDR else CameraMode.AUTO
                updateModeUI()
                updateTopMenuUI()
                createCameraPreviewSession()
            }
        }

        // 倒數：3 選 → 次選單
        viewBinding.btnToggleTimer.setOnClickListener {
            showInlineSubmenu(R.id.btn_toggle_timer, arrayOf("關閉", "3秒", "10秒")) { which ->
                timerMode = when(which) { 1 -> 3; 2 -> 10; else -> 0 }
                updateTopMenuUI()
            }
        }

        viewBinding.btnToggleFlip.setOnClickListener {
            resetInactivityTimer(); closeSubmenu()
            isFlipEnabled = !isFlipEnabled
            currentCameraId?.let { lensFlipSettings[it] = isFlipEnabled }
            configureTransform(viewBinding.viewFinder.width, viewBinding.viewFinder.height)
            updateTopMenuUI()
        }

        viewBinding.btnToggleVoice.setOnClickListener {
            resetInactivityTimer(); closeSubmenu()
            isVoiceControlEnabled = !isVoiceControlEnabled
            if (isVoiceControlEnabled) startVoiceListening() else stopVoiceListening()
            updateTopMenuUI()
        }

        viewBinding.btnToggleRaw.setOnClickListener {
            resetInactivityTimer(); closeSubmenu()
            isRawEnabled = !isRawEnabled
            updateTopMenuUI()
            createCameraPreviewSession()
        }

        // Super HDR：3 選（含設定）→ 次選單
        viewBinding.btnToggleSuperHdr.setOnClickListener {
            showInlineSubmenu(R.id.btn_toggle_super_hdr, arrayOf("關閉", "開啟", "設定範圍...")) { which ->
                when (which) {
                    0 -> isSuperHdrEnabled = false
                    1 -> { isSuperHdrEnabled = true; Toast.makeText(this, "Super HDR Enabled", Toast.LENGTH_SHORT).show() }
                    2 -> showSuperHdrSettingsDialog()
                }
                updateTopMenuUI()
            }
        }

        viewBinding.btnToggleInfo.setOnClickListener {
            showInlineSubmenu(R.id.btn_toggle_info, arrayOf("關閉", "開啟")) { which ->
                isDisplayInfoEnabled = (which == 1)
                updateModeUI()
                updateTopMenuUI()
                updateInfoOverlay()
            }
        }

        viewBinding.btnToggleStyle.setOnClickListener {
            closeSubmenu()
            showLutManagerDialog()
        }

        viewBinding.btnToggleGrading.setOnClickListener {
            resetInactivityTimer(); closeSubmenu()
            isProGradingEnabled = !isProGradingEnabled
            updateLutEffect()
            updateTopMenuUI()
            setupManualParameters()
        }

        viewBinding.btnTogglePeaking.setOnClickListener {
            resetInactivityTimer(); closeSubmenu()
            isPeakingEnabled = !isPeakingEnabled
            updateLutEffect()
            updateTopMenuUI()
        }

        viewBinding.btnToggleLevel.setOnClickListener {
            resetInactivityTimer(); closeSubmenu()
            isLevelEnabled = !isLevelEnabled
            viewBinding.levelContainer.visibility = if (isLevelEnabled) android.view.View.VISIBLE else android.view.View.GONE
            updateTopMenuUI()
        }

        // 穩定：4 選 → 次選單
        viewBinding.btnToggleStab.setOnClickListener {
            showInlineSubmenu(R.id.btn_toggle_stab,
                arrayOf("關閉", "僅硬體 (OIS)", "標準 (OIS+EIS)", "專業穩定 (Pro)")) { which ->
                currentStabMode = which
                updateTopMenuUI()
                createCameraPreviewSession()
            }
        }

        // 縮時：6 選 → 次選單
        viewBinding.btnToggleInterval.setOnClickListener {
            showInlineSubmenu(R.id.btn_toggle_interval,
                arrayOf("關閉", "5秒", "10秒", "30秒", "1分鐘", "5分鐘")) { which ->
                timeLapseIntervalMs = when(which) {
                    1 -> 5000L; 2 -> 10000L; 3 -> 30000L; 4 -> 60000L; 5 -> 300000L; else -> 0L
                }
                updateTimeLapseLogic()
                updateTopMenuUI()
            }
        }

        viewBinding.viewFinder.setOnTouchListener { _, event ->
            resetInactivityTimer()
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                triggerTouchToFocus(event.x, event.y)
                true
            } else false
        }

        viewBinding.parameterSlider.addOnChangeListener { _, value, _ ->
            resetInactivityTimer()
            if (currentManualParam != null) {
                viewBinding.parameterValueText.text = formatParameterValue(currentManualParam!!, value)
            }
            onParameterSliderChanged(value)
        }

        viewBinding.btnParamMinus.setOnClickListener {
            resetInactivityTimer()
            if (currentManualParam == null) return@setOnClickListener
            val step = when(currentManualParam) {
                ManualParameter.ISO -> 50f
                ManualParameter.SHUTTER -> 2f
                ManualParameter.EV -> 1f
                ManualParameter.FOCUS -> 0.5f
                else -> 1f
            }
            try {
                viewBinding.parameterSlider.value = (viewBinding.parameterSlider.value - step)
                    .coerceIn(viewBinding.parameterSlider.valueFrom, viewBinding.parameterSlider.valueTo)
            } catch (e: Exception) {}
        }
        
        viewBinding.btnParamPlus.setOnClickListener {
            resetInactivityTimer()
            if (currentManualParam == null) return@setOnClickListener
            val step = when(currentManualParam) {
                ManualParameter.ISO -> 50f
                ManualParameter.SHUTTER -> 2f
                ManualParameter.EV -> 1f
                ManualParameter.FOCUS -> 0.5f
                else -> 1f
            }
            try {
                viewBinding.parameterSlider.value = (viewBinding.parameterSlider.value + step)
                    .coerceIn(viewBinding.parameterSlider.valueFrom, viewBinding.parameterSlider.valueTo)
            } catch (e: Exception) {}
        }

        viewBinding.parameterValueText.setOnClickListener {
            if (currentManualParam == null) return@setOnClickListener
            val input = android.widget.EditText(this)
            input.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            input.hint = getString(R.string.param_input_hint)
            android.app.AlertDialog.Builder(this)
                .setTitle("手動輸入")
                .setView(input)
                .setPositiveButton("確定") { _, _ ->
                    try {
                        val v = input.text.toString().toFloat()
                        val sliderVal = when (currentManualParam) {
                            ManualParameter.SHUTTER -> {
                                val sec = if (v > 10) 1.0 / v else v.toDouble()
                                val minNs = exposureRange?.lower ?: 1000000L
                                val maxNs = exposureRange?.upper ?: 1000000000L
                                val expNs = (sec * 1_000_000_000).toLong().coerceIn(minNs, maxNs)
                                val ratio = (Math.log(expNs.toDouble()) - Math.log(minNs.toDouble())) / (Math.log(maxNs.toDouble()) - Math.log(minNs.toDouble()))
                                (ratio * 100).toFloat()
                            }
                            else -> v
                        }
                        viewBinding.parameterSlider.value = sliderVal.coerceIn(viewBinding.parameterSlider.valueFrom, viewBinding.parameterSlider.valueTo)
                    } catch (e: Exception) {}
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // Removed old histogram toggle button logic

        var dX = 0f
        var dY = 0f
        viewBinding.histogramView.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    dX = view.x - event.rawX
                    dY = view.y - event.rawY
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    view.x = event.rawX + dX
                    view.y = event.rawY + dY
                    true
                }
                else -> false
            }
        }

        setupModeSelectors()
        setupManualParameters()

        mediaActionSound = android.media.MediaActionSound().apply {
            load(android.media.MediaActionSound.SHUTTER_CLICK)
        }
        
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        accelerometer = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        magnetometer = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_MAGNETIC_FIELD)

        viewBinding.thumbnailView.setOnClickListener {
            resetInactivityTimer()
            openCustomPreviewOverlay()
        }

        // --- Custom Media Preview Overlay Setup ---
        viewBinding.btnPreviewClose.setOnClickListener {
            resetInactivityTimer()
            viewBinding.customPreviewOverlay.visibility = android.view.View.GONE
            if (viewBinding.previewVideoView.isPlaying) {
                viewBinding.previewVideoView.stopPlayback()
            }
        }
        
        viewBinding.btnPreviewShare.setOnClickListener {
            resetInactivityTimer()
            if (customMediaIndex in customMediaList.indices) {
                val item = customMediaList[customMediaIndex]
                val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = if (item.isVideo) "video/*" else "image/*"
                    putExtra(android.content.Intent.EXTRA_STREAM, item.uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(android.content.Intent.createChooser(shareIntent, "分享媒體"))
            }
        }
        
        viewBinding.btnPreviewDelete.setOnClickListener {
            resetInactivityTimer()
            if (customMediaIndex in customMediaList.indices) {
                val item = customMediaList[customMediaIndex]
                android.app.AlertDialog.Builder(this)
                    .setTitle("刪除檔案")
                    .setMessage("確定要永久刪除此檔案嗎？")
                    .setPositiveButton("刪除") { _, _ ->
                        try {
                            contentResolver.delete(item.uri, null, null)
                            Toast.makeText(this, "已刪除檔案", Toast.LENGTH_SHORT).show()
                            
                            // Refresh list
                            Thread {
                                customMediaList = fetchAllTelescopeMedia()
                                runOnUiThread {
                                    if (customMediaList.isEmpty()) {
                                        viewBinding.customPreviewOverlay.visibility = android.view.View.GONE
                                        viewBinding.thumbnailView.setImageDrawable(null)
                                        lastMediaUri = null
                                    } else {
                                        customMediaIndex = customMediaIndex.coerceAtMost(customMediaList.size - 1)
                                        loadMediaInPreview(customMediaIndex)
                                        // Update camera thumbnail
                                        lastMediaUri = customMediaList[0].uri
                                        loadLatestThumbnail()
                                    }
                                }
                            }.start()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to delete media", e)
                            Toast.makeText(this, "刪除失敗", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
        
        // Setup Swipe Gestures for customMediaList switching
        var startX = 0f
        viewBinding.previewMediaContainer.setOnTouchListener { _, event ->
            resetInactivityTimer()
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val diffX = event.x - startX
                    if (Math.abs(diffX) > 100) {
                        if (diffX > 0) {
                            // Swipe Right -> Previous (Newer) item
                            if (customMediaIndex > 0) {
                                customMediaIndex--
                                loadMediaInPreview(customMediaIndex)
                            } else {
                                Toast.makeText(this, "已是最新的相片/影片", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            // Swipe Left -> Next (Older) item
                            if (customMediaIndex < customMediaList.size - 1) {
                                customMediaIndex++
                                loadMediaInPreview(customMediaIndex)
                            } else {
                                Toast.makeText(this, "已是最舊的相片/影片", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }

        // EV 按鈕 toggle
        viewBinding.btnEvToggle.setOnClickListener {
            resetInactivityTimer()
            isEvEnabled = !isEvEnabled
            if (isEvEnabled) {
                viewBinding.evPanel.visibility = android.view.View.VISIBLE
                viewBinding.btnEvToggle.setBackgroundResource(R.drawable.bg_pill_button_active)
                viewBinding.btnEvToggle.setTextColor(android.graphics.Color.BLACK)
            } else {
                viewBinding.evPanel.visibility = android.view.View.GONE
                viewBinding.btnEvToggle.setBackgroundResource(R.drawable.bg_pill_button)
                viewBinding.btnEvToggle.setTextColor(android.graphics.Color.WHITE)
                currentEvOffset = 0f
                currentEv = 0
                viewBinding.evSlider.value = 0f
                viewBinding.evValueText.text = "0 EV"
                updatePreview()
            }
        }

        viewBinding.btnSwapSliders.setOnClickListener {
            resetInactivityTimer()
            swapSliders()
        }

        // EV 滑桂
        viewBinding.evSlider.addOnChangeListener { _, value, _ ->
            currentEvOffset = value
            val label = if (value >= 0f) "+%.1f EV".format(value) else "%.1f EV".format(value)
            viewBinding.evValueText.text = label
            if (isEvEnabled) updatePreview()
        }

        // EV 滑桂 +/- 按鈕
        viewBinding.btnEvMinus.setOnClickListener {
            viewBinding.evSlider.value = (viewBinding.evSlider.value - 0.3f).coerceAtLeast(-3f)
        }
        viewBinding.btnEvPlus.setOnClickListener {
            viewBinding.evSlider.value = (viewBinding.evSlider.value + 0.3f).coerceAtMost(3f)
        }

        // 垂直滑桿 +/- 按鈕（向上 = + 即更大的值）
        viewBinding.btnVParamPlus.setOnClickListener {
            val v = viewBinding.verticalParamSlider
            val step = if (v.stepSize > 0f) v.stepSize else (v.valueTo - v.valueFrom) * 0.01f
            v.value = (v.value + step).coerceAtMost(v.valueTo)
        }
        viewBinding.btnVParamMinus.setOnClickListener {
            val v = viewBinding.verticalParamSlider
            val step = if (v.stepSize > 0f) v.stepSize else (v.valueTo - v.valueFrom) * 0.01f
            v.value = (v.value - step).coerceAtLeast(v.valueFrom)
        }

        // 隱藏滑桿按鈕
        viewBinding.btnParamHide.setOnClickListener {
            resetInactivityTimer()
            currentManualParam = null
            updateModeUI()
        }
        viewBinding.btnVParamHide.setOnClickListener {
            resetInactivityTimer()
            currentVParam = null
            updateModeUI()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaActionSound?.release()
        stopVoiceListening()
        speechRecognizer?.destroy()
    }

    private fun loadLatestThumbnail() {
        backgroundHandler?.post {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DISPLAY_NAME
            )
            val selection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE 'Telescope_%'"
            val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"
            val queryUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            
            try {
                contentResolver.query(queryUri, projection, selection, null, sortOrder)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                        val id = cursor.getLong(idColumn)
                        val contentUri = android.content.ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            id
                        )
                        lastMediaUri = contentUri
                        
                        val thumbnail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            try {
                                contentResolver.loadThumbnail(contentUri, Size(128, 128), null)
                            } catch (e: Exception) {
                                null
                            }
                        } else {
                            null
                        }
                        
                        runOnUiThread {
                            viewBinding.thumbnailView.setPadding(0, 0, 0, 0)
                            if (thumbnail != null) {
                                viewBinding.thumbnailView.setImageBitmap(thumbnail)
                            } else {
                                viewBinding.thumbnailView.setImageURI(contentUri)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading latest thumbnail", e)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resetInactivityTimer()
        accelerometer?.let { sensorManager.registerListener(sensorListener, it, android.hardware.SensorManager.SENSOR_DELAY_UI) }
        magnetometer?.let { sensorManager.registerListener(sensorListener, it, android.hardware.SensorManager.SENSOR_DELAY_UI) }
        startBackgroundThread()
        loadLatestThumbnail()
        orientationEventListener?.enable()
        if (viewBinding.viewFinder.isAvailable) {
            openCamera(currentCameraId ?: return)
        } else {
            viewBinding.viewFinder.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        super.onPause()
        orientationEventListener?.disable()
        timeLapseHandler.removeCallbacksAndMessages(null)
        inactivityHandler.removeCallbacksAndMessages(null)
        sensorManager.unregisterListener(sensorListener)
        closeCamera()
        stopBackgroundThread()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            configureTransform(width, height)
            if (currentCameraId != null) openCamera(currentCameraId!!)
        }
        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            configureTransform(width, height)
        }
        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val matrix = Matrix()
        
        // 1. 取得預覽與 TextureView 的尺寸
        val previewSize = bestPreviewSize ?: return
        val rotation = display?.rotation ?: Surface.ROTATION_0
        
        // 2. 計算視圖中心與縮放，校正由於感光元件方向導致的扁平 (squishing) 比例問題
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = Math.max(
                viewHeight.toFloat() / previewSize.height,
                viewWidth.toFloat() / previewSize.width
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180f, centerX, centerY)
        }
        
        // 3. 在基礎方向校正之上，如果開啟望遠鏡修正，額外物理旋轉 180 度
        if (isFlipEnabled) {
            matrix.postRotate(180f, centerX, centerY)
        }
        
        viewBinding.viewFinder.setTransform(matrix)
    }

    private fun openCamera(cameraId: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return

        try {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            currentCharacteristics = chars
            supportedOisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            supportedEisModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            supportedAfModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)

            // Read ranges
            isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            apertureList = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
            evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            evStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            minFocusDistance = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

            // Set initial values
            currentIso = isoRange?.lower ?: 100
            currentExposureNs = exposureRange?.lower?.coerceAtLeast(1000000L) ?: 10000000L
            currentAperture = apertureList?.firstOrNull()

            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (map != null) {
                val jpegSizes = map.getOutputSizes(ImageFormat.JPEG)
                val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR)
                val previewSizes = map.getOutputSizes(SurfaceTexture::class.java)
                val standard1080p = Size(1920, 1080)
                
                if (jpegSizes != null && previewSizes != null) {
                    // JPEG 拍照解析度：優先選擇小於等於 12.5MP (約 1200 萬畫素，如 4096x3072) 的安全最大尺寸，避免副鏡頭高畫素導致 Capture Session 配置失敗，保證所有鏡頭 100% 成功儲存
                    val safeJpegSizes = jpegSizes.filter { it.width * it.height <= 4200 * 3200 }
                    bestJpegSize = safeJpegSizes.maxByOrNull { it.width * it.height } ?: jpegSizes.maxByOrNull { it.width * it.height } ?: standard1080p
                    
                    // 預覽視圖解析度：在小於等於 1080p 的安全區間內挑選與最大照片比例最契合的尺寸，保證流暢與無變形
                    val targetRatio = bestJpegSize!!.width.toDouble() / bestJpegSize!!.height
                    val safePreviewSizes = previewSizes.filter { it.width * it.height <= 1920 * 1080 }.sortedByDescending { it.width * it.height }
                    
                    bestPreviewSize = safePreviewSizes.firstOrNull { previewSize ->
                        val previewRatio = previewSize.width.toDouble() / previewSize.height
                        Math.abs(targetRatio - previewRatio) < 0.05
                    } ?: safePreviewSizes.firstOrNull() ?: previewSizes.firstOrNull() ?: standard1080p
                    
                    bestRawSize = rawSizes?.maxByOrNull { it.width * it.height }
                } else {
                    bestJpegSize = Size(1280, 720)
                    bestPreviewSize = Size(1280, 720)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get characteristics for $cameraId", e)
        }

        closeCamera()
        
        // 給硬體一點時間釋放上一個鏡頭，避免切換太快造成 Configuration Failed
        backgroundHandler?.postDelayed({
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                try {
                    cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            Log.d(TAG, "Camera $cameraId opened")
                            cameraDevice = camera
                            createCameraPreviewSession()
                        }
                        override fun onDisconnected(camera: CameraDevice) {
                            Log.w(TAG, "Camera $cameraId disconnected")
                            camera.close()
                            cameraDevice = null
                        }
                        override fun onError(camera: CameraDevice, error: Int) {
                            Log.e(TAG, "Camera $cameraId error: $error")
                            camera.close()
                            cameraDevice = null
                            runOnUiThread { Toast.makeText(this@MainActivity, "鏡頭啟動失敗 (代碼: $error)", Toast.LENGTH_SHORT).show() }
                        }
                    }, backgroundHandler)
                } catch (e: Exception) {
                    Log.e(TAG, "Cannot open camera $cameraId", e)
                    runOnUiThread { Toast.makeText(this@MainActivity, "無法開啟相機", Toast.LENGTH_SHORT).show() }
                }
            }
        }, 250)
    }

    private fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        rawImageReader?.close()
        rawImageReader = null
        mediaRecorder?.release()
        mediaRecorder = null
    }

    private fun setUpMediaRecorder(width: Int, height: Int) {
        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            android.media.MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            android.media.MediaRecorder()
        }

        mediaRecorder?.apply {
            setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            setVideoSource(android.media.MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            
            val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.mp4")
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Camera")
                }
            }
            videoUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
            val fileDescriptor = contentResolver.openFileDescriptor(videoUri!!, "rw")?.fileDescriptor
            setOutputFile(fileDescriptor)
            
            setVideoEncodingBitRate(10000000)
            setVideoFrameRate(30)
            setVideoSize(width, height)
            setVideoEncoder(android.media.MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            // --- 核心修正：錄影方向視情況翻轉 ---
            setOrientationHint(if (isFlipEnabled) 180 else 0)
            prepare()
        }
    }

    private fun startRecordingVideo() {
        if (cameraDevice == null || !viewBinding.viewFinder.isAvailable) return
        try {
            captureSession?.close()
            captureSession = null
            
            val previewW = bestPreviewSize?.width ?: 1920
            val previewH = bestPreviewSize?.height ?: 1080
            setUpMediaRecorder(previewW, previewH)
            
            val texture = viewBinding.viewFinder.surfaceTexture ?: return
            texture.setDefaultBufferSize(previewW, previewH)
            val previewSurface = Surface(texture)
            val recorderSurface = mediaRecorder!!.surface
            
            val surfaces = ArrayList<Surface>().apply {
                add(previewSurface)
                add(recorderSurface)
                if (imageReader != null) add(imageReader!!.surface)
            }

            previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(previewSurface)
                addTarget(recorderSurface)
            }

            cameraDevice!!.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    updatePreview()
                    runOnUiThread {
                        isRecording = true
                        viewBinding.videoCaptureButton.setImageResource(android.R.drawable.presence_busy)
                    }
                    mediaRecorder?.start()
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Toast.makeText(this@MainActivity, "Recording configuration failed", Toast.LENGTH_SHORT).show()
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
        }
    }

    private fun stopRecordingVideo() {
        isRecording = false
        try {
            captureSession?.stopRepeating()
            captureSession?.abortCaptures()
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to stop captures", e)
        }
        
        try {
            mediaRecorder?.stop()
        } catch (e: RuntimeException) {
            videoUri?.let { contentResolver.delete(it, null, null) }
        }
        mediaRecorder?.reset()
        
        runOnUiThread {
            viewBinding.videoCaptureButton.setImageResource(android.R.drawable.presence_video_online)
            videoUri?.let {
                sendBroadcast(android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, it))
                lastMediaUri = it
                try {
                    viewBinding.thumbnailView.setPadding(0, 0, 0, 0)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val thumbnail = contentResolver.loadThumbnail(it, android.util.Size(128, 128), null)
                        viewBinding.thumbnailView.setImageBitmap(thumbnail)
                    } else {
                        viewBinding.thumbnailView.setImageURI(it)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load video thumbnail", e)
                }
            }
            Toast.makeText(this@MainActivity, "Video saved", Toast.LENGTH_SHORT).show()
        }
        createCameraPreviewSession()
    }

    private fun createCameraPreviewSession() {
        try {
            val previewW = bestPreviewSize?.width ?: 1920
            val previewH = bestPreviewSize?.height ?: 1080
            val jpegW = bestJpegSize?.width ?: 1920
            val jpegH = bestJpegSize?.height ?: 1080

            val texture = viewBinding.viewFinder.surfaceTexture ?: return
            texture.setDefaultBufferSize(previewW, previewH)
            
            // 釋放舊的 Surface，建立並重用同一個成員變數以防止 Binder Surface 遺失
            previewSurface?.release()
            previewSurface = Surface(texture)

            imageReader = ImageReader.newInstance(jpegW, jpegH, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader ->
                    backgroundHandler?.post {
                        try {
                            val image = reader.acquireLatestImage() ?: return@post
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            image.close()
                            
                            if (isSuperHdrEnabled) {
                                hdrImageBuffer.add(bytes)
                                if (hdrImageBuffer.size >= 2) {
                                    mergeAndSaveHdr(hdrImageBuffer[0], hdrImageBuffer[1])
                                    hdrImageBuffer.clear()
                                }
                            } else {
                                saveImage(bytes)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "ImageReader callback failed", e)
                            runOnUiThread { Toast.makeText(this@MainActivity, "讀取影像失敗: ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
                        }
                    }
                }, backgroundHandler)
            }

            previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface!!)
            }

            val surfaces = mutableListOf(previewSurface!!, imageReader!!.surface)
            
            if (isRawEnabled && bestRawSize != null) {
                rawImageReader = ImageReader.newInstance(bestRawSize!!.width, bestRawSize!!.height, ImageFormat.RAW_SENSOR, 2)
                surfaces.add(rawImageReader!!.surface)
            }

            cameraDevice!!.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = session
                        configureTransform(viewBinding.viewFinder.width, viewBinding.viewFinder.height)
                        updatePreview()
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Toast.makeText(this@MainActivity, "Configuration failed", Toast.LENGTH_SHORT).show()
                    }
                }, null
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to start preview", e)
        }
    }

    private fun applyCameraSettings(builder: CaptureRequest.Builder) {
        builder.apply {
            when (currentCameraMode) {
                CameraMode.AUTO -> {
                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
                }
                CameraMode.HDR -> {
                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE)
                    set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_HDR)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }
                CameraMode.PRO -> {
                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposureNs)
                    currentAperture?.let { set(CaptureRequest.LENS_APERTURE, it) }
                    set(CaptureRequest.CONTROL_AWB_MODE, currentWbMode)
                    
                    if (manualFocusDistance > 0) {
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                        set(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocusDistance)
                    } else {
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    }
                }
            }
            
            // EV 補償：統一使用系統 AE 補償
            if (currentCameraMode != CameraMode.PRO) {
                val step = evStep ?: android.util.Rational(1, 3)
                val compensationIndex = if (isEvEnabled) {
                    // 將 Float (-3.0 ~ 3.0) 轉為系統 Index
                    Math.round(currentEvOffset * step.denominator.toFloat() / step.numerator.toFloat())
                } else {
                    currentEv
                }
                val clampedIndex = compensationIndex.coerceIn(evRange?.lower ?: -12, evRange?.upper ?: 12)
                set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clampedIndex)
                
                // 同步更新 currentEv 以供 HUD 顯示
                currentEv = clampedIndex
            }
            
            // 防手震設定 (OIS + EIS)
            when (currentStabMode) {
                0 -> { // OFF
                    set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                    set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                }
                1 -> { // OIS ONLY
                    set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                    if (supportedOisModes?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) == true) {
                        set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
                    }
                }
                2 -> { // STD (OIS + EIS)
                    if (supportedEisModes?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true) {
                        set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                    }
                    if (supportedOisModes?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) == true) {
                        set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
                    }
                }
                3 -> { // PRO (OIS + EIS + Preview)
                    if (supportedEisModes?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true) {
                        set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && 
                        supportedEisModes?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION) == true) {
                        set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION)
                    }
                    if (supportedOisModes?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) == true) {
                        set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
                    }
                }
            }

            /*
            // 開啟人臉偵測 (0: OFF, 1: SIMPLE, 2: FULL)
            val maxFaces = currentCharacteristics?.get(CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT) ?: 0
            if (maxFaces > 0) {
                set(CaptureRequest.STATISTICS_FACE_DETECTION_MODE, 2) 
            }
            */

            // 數位變焦裁切 (根據 ZoomConfig 的 zoomRatio)
            val activeRect = currentCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            if (activeRect != null) {
                val activeConfig = zoomConfigs.firstOrNull { it.label == currentZoomLabel }
                val zoomFactor = activeConfig?.zoomRatio ?: 1.0f
                if (zoomFactor > 1.0f) {
                    val cropW = (activeRect.width() / zoomFactor).toInt()
                    val cropH = (activeRect.height() / zoomFactor).toInt()
                    val centerX = activeRect.centerX()
                    val centerY = activeRect.centerY()
                    val cropRect = android.graphics.Rect(
                        centerX - cropW / 2,
                        centerY - cropH / 2,
                        centerX + cropW / 2,
                        centerY + cropH / 2
                    )
                    set(CaptureRequest.SCALER_CROP_REGION, cropRect)
                }
            }
        }
    }


    private fun updatePreview() {
        if (cameraDevice == null || previewRequestBuilder == null) return
        try {
            applyCameraSettings(previewRequestBuilder!!)
            captureSession?.setRepeatingRequest(previewRequestBuilder!!.build(), captureCallback, backgroundHandler)
            runOnUiThread { updateProStatusBar() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update preview", e)
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            super.onCaptureCompleted(session, request, result)
            if (currentCameraMode == CameraMode.AUTO || currentCameraMode == CameraMode.HDR) {
                lastAutoIso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: lastAutoIso
                lastAutoExposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: lastAutoExposureNs
                if (isDisplayInfoEnabled) {
                    runOnUiThread { updateInfoOverlay() }
                }
            }
        }
    }

    private fun updateInfoOverlay() {
        if (!isDisplayInfoEnabled) return
        val iso = if (currentCameraMode == CameraMode.PRO) currentIso else lastAutoIso
        val expNs = if (currentCameraMode == CameraMode.PRO) currentExposureNs else lastAutoExposureNs
        
        val sec = expNs / 1_000_000_000.0
        val shutterStr = if (sec >= 1.0) String.format(Locale.US, "%.1fs", sec) else "1/${Math.round(1.0 / sec)}s"
        
        val wbStr = if (currentCameraMode == CameraMode.PRO) {
            when (currentWbMode) {
                CaptureRequest.CONTROL_AWB_MODE_AUTO -> "AUTO"
                CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT -> "DAY"
                CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "CLD"
                CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT -> "FLU"
                CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT -> "INC"
                else -> "AUTO"
            }
        } else "AUTO"

        val evStr = if (currentCameraMode == CameraMode.PRO) {
            if (currentEv >= 0) "+$currentEv" else "$currentEv"
        } else {
            if (isEvEnabled) {
                val step = evStep ?: android.util.Rational(1, 3)
                val compensationIndex = Math.round(currentEvOffset * step.denominator.toFloat() / step.numerator.toFloat())
                if (compensationIndex >= 0) "+$compensationIndex" else "$compensationIndex"
            } else {
                if (currentEv >= 0) "+$currentEv" else "$currentEv"
            }
        }

        viewBinding.hudShutter.text = shutterStr
        viewBinding.hudIso.text = iso.toString()
        viewBinding.hudWb.text = wbStr
        viewBinding.hudEv.text = evStr
    }

    private fun updateLutEffect() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        var finalEffect: android.graphics.RenderEffect? = null

        // 1. 一級校正 (Primary Correction)
        val primaryMatrix = ColorMatrix().apply {
            // Exposure (Brightness)
            if (gradingExposure != 1.0f) {
                val scale = gradingExposure
                postConcat(ColorMatrix(floatArrayOf(
                    scale, 0f, 0f, 0f, 0f,
                    0f, scale, 0f, 0f, 0f,
                    0f, 0f, scale, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            // Contrast
            if (gradingContrast != 1.0f) {
                val scale = gradingContrast
                val translate = (-.5f * scale + .5f) * 255f
                postConcat(ColorMatrix(floatArrayOf(
                    scale, 0f, 0f, 0f, translate,
                    0f, scale, 0f, 0f, translate,
                    0f, 0f, scale, 0f, translate,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            // Saturation
            if (gradingSaturation != 1.0f) {
                val satMatrix = ColorMatrix()
                satMatrix.setSaturation(gradingSaturation)
                postConcat(satMatrix)
            }
        }
        val primaryEffect = android.graphics.RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(primaryMatrix))
        finalEffect = primaryEffect

        // 2. 套用還原 LUT (Technical LUT: Log to Rec.709)
        if (isProGradingEnabled) {
            // 這裡模擬一個 Log 轉 709 的 S 型曲線 (S-Curve) 與色彩恢復
            val logTo709Matrix = ColorMatrix(floatArrayOf(
                1.2f, 0f, 0f, 0f, -20f,
                0f, 1.2f, 0f, 0f, -20f,
                0f, 0f, 1.2f, 0f, -20f,
                0f, 0f, 0f, 1f, 0f
            ))
            val techEffect = android.graphics.RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(logTo709Matrix))
            finalEffect = android.graphics.RenderEffect.createChainEffect(techEffect, finalEffect!!)
        }

        // 3. 疊加風格 LUT (Creative Style LUT)
        if (currentStyleIndex > 0) {
            val styleMatrix = ColorMatrix()
            when (currentStyleIndex) {
                1 -> styleMatrix.setSaturation(1.4f) // Vivid
                2 -> { // Film
                    styleMatrix.setSaturation(0.8f)
                    styleMatrix.postConcat(ColorMatrix(floatArrayOf(
                        1.1f, 0f, 0f, 0f, 5f,
                        0f, 1.0f, 0f, 0f, 0f,
                        0f, 0f, 0.9f, 0f, -5f,
                        0f, 0f, 0f, 1f, 0f
                    )))
                }
                3 -> styleMatrix.setSaturation(0f) // B&W
                4 -> styleMatrix.postConcat(ColorMatrix(floatArrayOf( // Cool
                    0.9f, 0f, 0f, 0f, -5f,
                    0f, 1.0f, 0f, 0f, 0f,
                    0f, 0f, 1.2f, 0f, 10f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            val creativeEffect = android.graphics.RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(styleMatrix))
            finalEffect = if (finalEffect != null) {
                android.graphics.RenderEffect.createChainEffect(creativeEffect, finalEffect)
            } else {
                creativeEffect
            }
        }

        // 4. 客製化 .cube LUT
        if (customLutBitmap != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val lutShaderCode = """
                uniform shader content;
                uniform shader lut;
                uniform float lutSize;
                half4 main(float2 fragCoord) {
                    half4 color = content.eval(fragCoord);
                    float r = clamp(color.r, 0.0, 1.0) * (lutSize - 1.0);
                    float g = clamp(color.g, 0.0, 1.0) * (lutSize - 1.0);
                    float b = clamp(color.b, 0.0, 1.0) * (lutSize - 1.0);
                    float blue_i = floor(b);
                    float blue_f = b - blue_i;
                    auto sampleLut = [&](float ri, float gi, float bi) {
                        float quad_x = mod(bi, sqrt(lutSize)) * lutSize + ri;
                        float quad_y = floor(bi / sqrt(lutSize)) * lutSize + gi;
                        return lut.eval(float2(quad_x, quad_y));
                    };
                    return mix(sampleLut(r, g, blue_i), sampleLut(r, g, blue_i + 1.0), blue_f);
                }
            """.trimIndent()
            val lutShader = android.graphics.RuntimeShader(lutShaderCode)
            lutShader.setFloatUniform("lutSize", customLutSize.toFloat())
            lutShader.setInputBuffer("lut", android.graphics.BitmapShader(customLutBitmap!!, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP))
            val lutEffect = android.graphics.RenderEffect.createRuntimeShaderEffect(lutShader, "content")
            finalEffect = if (finalEffect != null) android.graphics.RenderEffect.createChainEffect(lutEffect, finalEffect) else lutEffect
        }

        // 5. 峰值對焦 (Focus Peaking)
        if (isPeakingEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val peakingShaderCode = """
                uniform shader content;
                half4 main(float2 fragCoord) {
                    half4 color = content.eval(fragCoord);
                    float2 uv = fragCoord;
                    float offset = 1.0;
                    float edge = abs(content.eval(uv + float2(0, -offset)).g - content.eval(uv + float2(0, offset)).g) +
                                 abs(content.eval(uv + float2(-offset, 0)).g - content.eval(uv + float2(offset, 0)).g);
                    if (edge > 0.08) return half4(0.0, 1.0, 0.0, 1.0);
                    return color;
                }
            """.trimIndent()
            val peakingShader = android.graphics.RuntimeShader(peakingShaderCode)
            val peakingEffect = android.graphics.RenderEffect.createRuntimeShaderEffect(peakingShader, "content")
            finalEffect = if (finalEffect != null) android.graphics.RenderEffect.createChainEffect(peakingEffect, finalEffect) else peakingEffect
        }

        viewBinding.viewFinder.setRenderEffect(finalEffect)
    }

    private fun triggerShutterEffect() {
        runOnUiThread {
            viewBinding.flashOverlay.visibility = android.view.View.VISIBLE
            viewBinding.flashOverlay.alpha = 1f
            viewBinding.flashOverlay.animate()
                .alpha(0f)
                .setDuration(150)
                .withEndAction { viewBinding.flashOverlay.visibility = android.view.View.GONE }
                .start()
        }
    }

    private fun startCountdown() {
        var timeLeft = timerMode
        viewBinding.countdownText.visibility = android.view.View.VISIBLE
        viewBinding.countdownText.text = timeLeft.toString()
        
        val timer = object : android.os.CountDownTimer((timerMode * 1000).toLong(), 1000) {
            override fun onTick(millisUntilFinished: Long) {
                timeLeft = (millisUntilFinished / 1000).toInt() + 1
                viewBinding.countdownText.text = timeLeft.toString()
                // You could play a beep sound here
            }
            override fun onFinish() {
                viewBinding.countdownText.visibility = android.view.View.GONE
                takePhoto()
            }
        }
        timer.start()
    }

    private fun takePhoto() {
        val device = cameraDevice ?: return
        val reader = imageReader ?: return
        val session = captureSession ?: return

        lastCaptureRotation = getStandardJpegOrientation(currentDeviceOrientation)

        triggerShutterEffect()
        mediaActionSound?.play(android.media.MediaActionSound.SHUTTER_CLICK)
        
        try {
            val requests = mutableListOf<CaptureRequest>()
            
            // 統一使用官方標準的 TEMPLATE_STILL_CAPTURE 以確保所有鏡頭能成功儲存 JPEG
            val templateType = CameraDevice.TEMPLATE_STILL_CAPTURE
            
            
            if (isSuperHdrEnabled) {
                // AUTO 模式下先同步參數以避免黑圖
                if (currentCameraMode == CameraMode.AUTO || currentCameraMode == CameraMode.HDR) {
                    currentIso = lastAutoIso
                    currentExposureNs = lastAutoExposureNs
                }

                // Super HDR: 2 shots with different ISOs
                // Shot 1: Min ISO (Protect Highlights)
                requests.add(device.createCaptureRequest(templateType).apply {
                    addTarget(reader.surface)
                    previewSurface?.let { addTarget(it) }
                    applyCameraSettings(this)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_SENSITIVITY, superHdrMinIso.coerceIn(isoRange?.lower ?: 50, isoRange?.upper ?: 3200))
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, (currentExposureNs / 2).coerceAtLeast(exposureRange?.lower ?: 1000L))
                    set(CaptureRequest.JPEG_ORIENTATION, lastCaptureRotation)
                }.build())
                
                // Shot 2: High ISO (Brighten Shadows/Face)
                requests.add(device.createCaptureRequest(templateType).apply {
                    addTarget(reader.surface)
                    previewSurface?.let { addTarget(it) }
                    applyCameraSettings(this)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_SENSITIVITY, superHdrMaxIso.coerceIn(isoRange?.lower ?: 50, isoRange?.upper ?: 3200))
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposureNs)
                    set(CaptureRequest.JPEG_ORIENTATION, lastCaptureRotation)
                }.build())
                
                hdrImageBuffer.clear()
            } else {
                // Normal shot
                requests.add(device.createCaptureRequest(templateType).apply {
                    addTarget(reader.surface)
                    previewSurface?.let { addTarget(it) }
                    if (isRawEnabled && rawImageReader != null) addTarget(rawImageReader!!.surface)
                    applyCameraSettings(this)
                    set(CaptureRequest.JPEG_ORIENTATION, lastCaptureRotation)
                }.build())
            }

            session.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    if (isRawEnabled && rawImageReader != null) {
                        val rawImage = rawImageReader?.acquireLatestImage()
                        if (rawImage != null) {
                            val rawRotation = getJpegOrientation(currentDeviceOrientation)
                            saveRawImage(rawImage, result, rawRotation)
                        }
                    }
                    if (!isSuperHdrEnabled) {
                        runOnUiThread { Toast.makeText(this@MainActivity, getString(R.string.photo_saved), Toast.LENGTH_SHORT).show() }
                    }
                }
                
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    val reason = failure.reason
                    Log.e(TAG, "Capture failed! Reason: $reason")
                    runOnUiThread { Toast.makeText(this@MainActivity, "拍照失敗: Reason $reason", Toast.LENGTH_SHORT).show() }
                }

                override fun onCaptureSequenceCompleted(session: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
                    if (isSuperHdrEnabled) {
                        runOnUiThread { Toast.makeText(this@MainActivity, "Super HDR processing...", Toast.LENGTH_SHORT).show() }
                        // Processing handled in ImageReader listener for Super HDR (simple implementation)
                    }
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Capture failed", e)
            runOnUiThread { Toast.makeText(this@MainActivity, "拍照出錯: ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
        }
    }

    private fun mergeAndSaveHdr(bytes1: ByteArray, bytes2: ByteArray) {
        try {
            val bmp1 = android.graphics.BitmapFactory.decodeByteArray(bytes1, 0, bytes1.size)
            val bmp2 = android.graphics.BitmapFactory.decodeByteArray(bytes2, 0, bytes2.size)
            
            if (bmp1 == null || bmp2 == null) {
                Log.e(TAG, "Failed to decode bitmaps for HDR: bmp1=$bmp1, bmp2=$bmp2")
                bmp1?.recycle()
                bmp2?.recycle()
                return
            }
            
            // 合成：使用 Alpha 疊加 (簡單曝光融合)
            val result = android.graphics.Bitmap.createBitmap(bmp1.width, bmp1.height, bmp1.config)
            val canvas = android.graphics.Canvas(result)
            val paint = android.graphics.Paint()
            
            // 先畫暗部照片 (Protect Highlights)
            canvas.drawBitmap(bmp1, 0f, 0f, paint)
            // 疊加亮部照片 (Brighten Shadows)
            paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN)
            paint.alpha = 100 // 約 40% 權重
            canvas.drawBitmap(bmp2, 0f, 0f, paint)
            
            val stream = java.io.ByteArrayOutputStream()
            result.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, stream)
            val finalBytes = stream.toByteArray()
            
            saveImage(finalBytes)
            
            bmp1.recycle()
            bmp2.recycle()
            result.recycle()
            
            runOnUiThread { Toast.makeText(this, "Super HDR 合成完成", Toast.LENGTH_SHORT).show() }
        } catch (e: Exception) {
            Log.e(TAG, "Merge failed", e)
        }
    }

    private fun saveRawImage(image: android.media.Image, result: TotalCaptureResult, rawRotation: Int) {
        val chars = currentCharacteristics ?: return
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.dng")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
            }
        }
        
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            contentResolver.openOutputStream(it)?.use { output ->
                DngCreator(chars, result).use { dngCreator ->
                    val exifOrientation = when (rawRotation) {
                        90 -> android.media.ExifInterface.ORIENTATION_ROTATE_90
                        180 -> android.media.ExifInterface.ORIENTATION_ROTATE_180
                        270 -> android.media.ExifInterface.ORIENTATION_ROTATE_270
                        else -> android.media.ExifInterface.ORIENTATION_NORMAL
                    }
                    dngCreator.setOrientation(exifOrientation)
                    dngCreator.writeImage(output, image)
                }
            }
            image.close()
        }
    }

    private fun saveImage(bytes: ByteArray) {
        backgroundHandler?.post {
            try {
                // 1. 讀取 JPEG 原始位元組中的 EXIF 旋轉角度 (由硬體 HAL 寫入)
                val exifRotation = getExifRotation(bytes)
                
                // 2. 計算最終物理旋轉量
                // 如果開啟望遠鏡修正 (isFlipEnabled)，除了原本的 EXIF 方向，必須額外物理旋轉 180 度
                val needFlip = isFlipEnabled
                val needProcessing = isProGradingEnabled || currentStyleIndex > 0 || customLutBitmap != null
                
                val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.jpg")
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
                    }
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    ?: throw java.io.IOException("Failed to insert MediaStore entry")
                
                uri.let { targetUri ->
                    // 如果不需要任何旋轉（EXIF 是 0 且沒開啟 Flip）且不需要 LUT 處理，直接寫入原始位元組以提昇效能
                    if (exifRotation == 0 && !needFlip && !needProcessing) {
                        contentResolver.openOutputStream(targetUri)?.use { output ->
                            output.write(bytes)
                        }
                    } else {
                        // 解碼 Bitmap
                        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap == null) {
                            throw java.io.IOException("BitmapFactory failed to decode JPEG bytes")
                        }
                        
                        // 計算要套用的旋轉矩陣：
                        // 先旋轉 EXIF 指示的角度使相片直立，如果開啟了 Flip，再旋轉 180 度
                        val matrix = android.graphics.Matrix()
                        if (exifRotation != 0) {
                            matrix.postRotate(exifRotation.toFloat())
                        }
                        if (needFlip) {
                            matrix.postRotate(180f)
                        }
                        
                        if (exifRotation != 0 || needFlip) {
                            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                            if (rotated != bitmap) {
                                bitmap.recycle()
                                bitmap = rotated
                            }
                        }
                        
                        // 套用 LUT / grading 處理
                        if (needProcessing) {
                            val resultBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                            val canvas = Canvas(resultBitmap)
                            val cm = ColorMatrix()
                            if (isProGradingEnabled) {
                                cm.setScale(gradingExposure, gradingExposure, gradingExposure, 1f)
                                val sat = ColorMatrix()
                                sat.setSaturation(gradingSaturation)
                                cm.postConcat(sat)
                            }
                            val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
                            canvas.drawBitmap(bitmap, 0f, 0f, paint)
                            
                            bitmap.recycle()
                            bitmap = resultBitmap
                        }
                        
                        // 寫入儲存
                        contentResolver.openOutputStream(targetUri)?.use { output ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
                        }
                        
                        bitmap.recycle()
                    }
                    
                    // 強制通知系統相簿掃描並更新該 URI，使其立即在系統相簿中顯示
                    sendBroadcast(android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, targetUri))
                    
                    lastMediaUri = targetUri
                    runOnUiThread {
                        try {
                            viewBinding.thumbnailView.setPadding(0, 0, 0, 0)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                val thumbnail = contentResolver.loadThumbnail(targetUri, android.util.Size(128, 128), null)
                                viewBinding.thumbnailView.setImageBitmap(thumbnail)
                            } else {
                                viewBinding.thumbnailView.setImageURI(targetUri)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to load image thumbnail", e)
                        }
                    }
                }
                runOnUiThread { Toast.makeText(this@MainActivity, getString(R.string.photo_saved), Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save image", e)
                runOnUiThread { Toast.makeText(this@MainActivity, "儲存失敗: ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                takePhoto()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun setupDynamicLenses() {
        Thread {
            initializeZoomConfigs()

            // 為每個配置初始化 lensFlipSettings 偏好
            zoomConfigs.forEach {
                if (!lensFlipSettings.containsKey(it.cameraId)) {
                    lensFlipSettings[it.cameraId] = it.isTelephoto
                }
            }

            runOnUiThread {
                if (currentCameraId == null) {
                    val mainConfig = zoomConfigs.firstOrNull { it.label == "1x" } ?: zoomConfigs.firstOrNull()
                    currentCameraId = mainConfig?.cameraId ?: "0"
                    currentZoomLabel = mainConfig?.label ?: "1x"
                }
                isFlipEnabled = lensFlipSettings[currentCameraId!!] ?: false

                viewBinding.zoomLayout.removeAllViews()
                lensTextViews.clear()

                val colorActive = android.graphics.Color.parseColor("#000000") // 黑字
                val colorInactive = android.graphics.Color.parseColor("#FFFFFF") // 白字

                for (config in zoomConfigs) {
                    val tv = TextView(this).apply {
                        text = config.label
                        textSize = 14f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        
                        val isActive = (currentZoomLabel == config.label)
                        setTextColor(if (isActive) colorActive else colorInactive)
                        setBackgroundResource(if (isActive) R.drawable.bg_pill_button_active else R.drawable.bg_pill_button)
                        
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(140, 90).apply { 
                            setMargins(16, 0, 16, 0) 
                        }

                        setOnClickListener {
                            resetInactivityTimer()
                            currentCameraId = config.cameraId
                            currentZoomLabel = config.label
                            isFlipEnabled = lensFlipSettings[config.cameraId] ?: false
                            
                            // 重置所有按鈕樣式
                            lensTextViews.forEach { 
                                it.setTextColor(colorInactive)
                                it.setBackgroundResource(R.drawable.bg_pill_button)
                            }
                            // 設置當前按鈕樣式
                            this.setTextColor(colorActive)
                            this.setBackgroundResource(R.drawable.bg_pill_button_active)
                            
                            openCamera(currentCameraId!!)
                        }
                    }
                    viewBinding.zoomLayout.addView(tv)
                    lensTextViews.add(tv)
                }

                if (viewBinding.viewFinder.isAvailable && currentCameraId != null) {
                    openCamera(currentCameraId!!)
                }
            }
        }.start()
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS && allPermissionsGranted()) {
            setupDynamicLenses()
        }
    }

    private fun setupModeSelectors() {
        viewBinding.modeAuto.setOnClickListener {
            currentCameraMode = CameraMode.AUTO
            // 離開 PRO 模式時清除參數選擇
            currentManualParam = null
            currentVParam = null
            updateModeUI()
        }
        viewBinding.modeManual.setOnClickListener {
            // 進入 PRO 模式時，帶入最後一次 AUTO 的參數
            if (currentCameraMode != CameraMode.PRO) {
                currentIso = lastAutoIso
                currentExposureNs = lastAutoExposureNs
                // 預設：S 在水平滑桿，ISO 在垂直滑桿
                currentManualParam = ManualParameter.SHUTTER
                currentVParam = ManualParameter.ISO
            }
            currentCameraMode = CameraMode.PRO
            updateModeUI()
        }
        updateModeUI()
        setupStatusBlockListeners()
    }

    private fun toggleOrSelectParam(param: ManualParameter) {
        resetInactivityTimer()
        when {
            currentManualParam == param -> {
                currentManualParam = null
            }
            currentVParam == param -> {
                currentVParam = null
            }
            else -> {
                if (currentManualParam == null) {
                    currentManualParam = param
                } else if (currentVParam == null) {
                    currentVParam = param
                } else {
                    currentManualParam = param
                }
            }
        }
        updateModeUI()
    }

    private fun setupStatusBlockListeners() {
        // 為專業狀態列的參數區塊添加點擊切換功能
        viewBinding.statusEvBlock.setOnClickListener { toggleOrSelectParam(ManualParameter.EV) }
        viewBinding.statusABlock.setOnClickListener { toggleOrSelectParam(ManualParameter.APERTURE) }
        viewBinding.statusSBlock.setOnClickListener { toggleOrSelectParam(ManualParameter.SHUTTER) }
        viewBinding.statusIsoBlock.setOnClickListener { toggleOrSelectParam(ManualParameter.ISO) }
        viewBinding.statusWbBlock.setOnClickListener { toggleOrSelectParam(ManualParameter.WB) }
        viewBinding.statusFBlock.setOnClickListener { toggleOrSelectParam(ManualParameter.FOCUS) }

        // --- AUTO HUD 快速接管 ---
        viewBinding.hudSBlock.setOnClickListener {
            resetInactivityTimer()
            if (currentCameraMode != CameraMode.PRO) {
                currentIso = lastAutoIso; currentExposureNs = lastAutoExposureNs
                currentCameraMode = CameraMode.PRO
            }
            currentManualParam = ManualParameter.SHUTTER
            updateModeUI()
        }
        viewBinding.hudIsoBlock.setOnClickListener {
            resetInactivityTimer()
            if (currentCameraMode != CameraMode.PRO) {
                currentIso = lastAutoIso; currentExposureNs = lastAutoExposureNs
                currentCameraMode = CameraMode.PRO
            }
            currentManualParam = ManualParameter.ISO
            updateModeUI()
        }
        viewBinding.hudWbBlock.setOnClickListener {
            resetInactivityTimer()
            if (currentCameraMode != CameraMode.PRO) {
                currentIso = lastAutoIso; currentExposureNs = lastAutoExposureNs
                currentCameraMode = CameraMode.PRO
            }
            currentManualParam = ManualParameter.WB
            updateModeUI()
        }
        viewBinding.hudEvBlock.setOnClickListener {
            resetInactivityTimer()
            if (currentCameraMode != CameraMode.PRO) {
                // AUTO 下點擊 EV 優先使用專屬面板，除非切換模式
                isEvEnabled = true
                updateModeUI()
            } else {
                currentManualParam = ManualParameter.EV
                updateModeUI()
            }
        }
    }

    private fun updateModeUI() {
        val colorActive = android.graphics.Color.parseColor("#000000")
        val colorInactive = android.graphics.Color.parseColor("#FFFFFF")

        viewBinding.modeAuto.setTextColor(if (currentCameraMode == CameraMode.AUTO || currentCameraMode == CameraMode.HDR) colorActive else colorInactive)
        viewBinding.modeAuto.setBackgroundResource(if (currentCameraMode == CameraMode.AUTO || currentCameraMode == CameraMode.HDR) R.drawable.bg_pill_button_active else 0)
        
        viewBinding.modeManual.setTextColor(if (currentCameraMode == CameraMode.PRO) colorActive else colorInactive)
        viewBinding.modeManual.setBackgroundResource(if (currentCameraMode == CameraMode.PRO) R.drawable.bg_pill_button_active else 0)
        
        // Update Top Menu HDR status dot
        viewBinding.dotHdr.setBackgroundColor(if (currentCameraMode == CameraMode.HDR) android.graphics.Color.parseColor("#FFD700") else android.graphics.Color.WHITE)

        // PRO mode: hide redundant parameter tabs — proStatusBar already handles selection
        viewBinding.parameterScrollView.visibility = android.view.View.GONE
        
        // 同步 EV 面板狀態
        if (currentCameraMode != CameraMode.PRO && isEvEnabled) {
            viewBinding.evPanel.visibility = android.view.View.VISIBLE
        } else {
            viewBinding.evPanel.visibility = android.view.View.GONE
        }

        if (currentCameraMode == CameraMode.PRO) {
            setupManualParameters()
            // PRO 模式：主動建立 H/V 滑桿
            if (currentManualParam != null) {
                updateSliderForParameter()
                viewBinding.parameterControlPanel.visibility = android.view.View.VISIBLE
            } else {
                viewBinding.parameterControlPanel.visibility = android.view.View.GONE
            }
            currentVParam?.let { setupVSliderForParam(it) }
                ?: run { viewBinding.verticalSliderContainer.visibility = android.view.View.GONE }
        } else {
            // AUTO/HDR 模式：只用 ev_panel，不用主滑桿和垂直滑桿
            currentManualParam = null
            currentVParam = null
            viewBinding.parameterControlPanel.visibility = android.view.View.GONE
            viewBinding.verticalSliderContainer.visibility = android.view.View.GONE
        }
        
        // 直方圖狀態由其獨立開關控制，不受模式影響
        if (isHistogramEnabled) {
            viewBinding.histogramView.visibility = android.view.View.VISIBLE
            startHistogramAnalysis()
        } else {
            viewBinding.histogramView.visibility = android.view.View.GONE
            stopHistogramAnalysis()
        }
        updateTopMenuUI()
        // PRO mode status bar
        viewBinding.proStatusBar.visibility =
            if (currentCameraMode == CameraMode.PRO) android.view.View.VISIBLE
            else android.view.View.GONE
        updateProStatusBar()
        // AUTO 模式下的 HUD 顯示邏輯
        viewBinding.autoStatusHud.visibility = 
            if (currentCameraMode != CameraMode.PRO && isDisplayInfoEnabled) android.view.View.VISIBLE 
            else android.view.View.GONE
        
        updatePreview()
    }

    private fun swapSliders() {
        if (currentCameraMode != CameraMode.PRO) return
        val temp = currentManualParam
        currentManualParam = currentVParam
        currentVParam = temp
        
        // 更新 UI
        if (currentManualParam != null) {
            updateSliderForParameter()
            viewBinding.parameterControlPanel.visibility = android.view.View.VISIBLE
        } else {
            viewBinding.parameterControlPanel.visibility = android.view.View.GONE
        }
        
        if (currentVParam != null) {
            setupVSliderForParam(currentVParam!!)
            viewBinding.verticalSliderContainer.visibility = android.view.View.VISIBLE
        } else {
            viewBinding.verticalSliderContainer.visibility = android.view.View.GONE
        }
        
        setupManualParameters() // 重新刷新顏色
    }

    private fun updateTopMenuUI() {
        val colorActiveText = android.graphics.Color.parseColor("#000000")
        val colorInactiveText = android.graphics.Color.parseColor("#FFFFFF")
        val colorDotActive = android.graphics.Color.parseColor("#FFD700")
        val colorDotInactive = android.graphics.Color.parseColor("#FFFFFF")

        fun setTileStyle(tile: android.view.View, isActive: Boolean, dot: android.view.View? = null) {
            tile.setBackgroundResource(if (isActive) R.drawable.bg_pill_button_active else R.drawable.bg_menu_tile)
            if (tile is android.view.ViewGroup) {
                for (i in 0 until tile.childCount) {
                    val child = tile.getChildAt(i)
                    if (child is android.widget.TextView) {
                        child.setTextColor(if (isActive) colorActiveText else colorInactiveText)
                    }
                }
            }
            dot?.setBackgroundColor(if (isActive) colorDotActive else colorDotInactive)
        }

        setTileStyle(viewBinding.btnToggleGrid, isGridLinesEnabled, viewBinding.dotGrid)
        setTileStyle(viewBinding.btnToggleHistogram, isHistogramEnabled, viewBinding.dotHistogram)
        setTileStyle(viewBinding.btnToggleHdr, currentCameraMode == CameraMode.HDR, viewBinding.dotHdr)
        setTileStyle(viewBinding.btnToggleTimer, timerMode > 0, viewBinding.dotTimer)
        setTileStyle(viewBinding.btnToggleFlip, isFlipEnabled, viewBinding.dotFlip)
        setTileStyle(viewBinding.btnToggleVoice, isVoiceControlEnabled, viewBinding.dotVoice)
        setTileStyle(viewBinding.btnToggleRaw, isRawEnabled, viewBinding.dotRaw)
        setTileStyle(viewBinding.btnToggleInfo, isDisplayInfoEnabled, viewBinding.dotInfo)
        setTileStyle(viewBinding.btnToggleStyle, currentStyleIndex > 0, viewBinding.dotStyle)
        setTileStyle(viewBinding.btnToggleGrading, isProGradingEnabled, viewBinding.dotGrading)
        setTileStyle(viewBinding.btnTogglePeaking, isPeakingEnabled, viewBinding.dotPeaking)
        setTileStyle(viewBinding.btnToggleLevel, isLevelEnabled, viewBinding.dotLevel)
        
        // Super HDR tile has a slightly different structure (nested)
        viewBinding.btnToggleSuperHdr.parent?.let { parent ->
            if (parent is android.view.ViewGroup) {
                parent.setBackgroundResource(if (isSuperHdrEnabled) R.drawable.bg_pill_button_active else R.drawable.bg_menu_tile)
                // Update children text and dots
                for (i in 0 until viewBinding.btnToggleSuperHdr.childCount) {
                    val v = viewBinding.btnToggleSuperHdr.getChildAt(i)
                    if (v is android.widget.TextView) v.setTextColor(if (isSuperHdrEnabled) colorActiveText else colorInactiveText)
                    if (v.id == R.id.dot_super_hdr) v.setBackgroundColor(if (isSuperHdrEnabled) colorDotActive else colorDotInactive)
                }
                // Also handle the settings part text (the triangle)
                for (i in 0 until viewBinding.btnSuperHdrSettings.childCount) {
                    val v = viewBinding.btnSuperHdrSettings.getChildAt(i)
                    if (v is android.widget.TextView) v.setTextColor(if (isSuperHdrEnabled) colorActiveText else colorInactiveText)
                }
                // The settings part background should be semi-transparent even when active
                viewBinding.btnSuperHdrSettings.setBackgroundColor(if (isSuperHdrEnabled) android.graphics.Color.parseColor("#20000000") else android.graphics.Color.parseColor("#40000000"))
            }
        }

        setTileStyle(viewBinding.btnToggleStab, currentStabMode > 0, viewBinding.dotStab)
        setTileStyle(viewBinding.btnToggleInterval, timeLapseIntervalMs > 0, viewBinding.dotInterval)

        // Update Stab Text based on mode
        viewBinding.textStab.text = when(currentStabMode) {
            0 -> "Stab Off"
            1 -> "OIS Only"
            2 -> "STD Stab"
            3 -> "Pro Stab"
            else -> "Stab"
        }

        // Update Interval Text
        viewBinding.textInterval.text = if (timeLapseIntervalMs == 0L) "縮時關閉" else "${timeLapseIntervalMs/1000}s 縮時"
    }

    private fun setupManualParameters() {
        if (currentCameraMode != CameraMode.PRO) {
            currentManualParam = null
            currentVParam = null
            viewBinding.parameterControlPanel.visibility = android.view.View.GONE
            viewBinding.verticalSliderContainer.visibility = android.view.View.GONE
            viewBinding.parameterScrollView.visibility = android.view.View.GONE
            return
        }

        val colorInactive = android.graphics.Color.parseColor("#FFFFFF")

        val params = mutableListOf(
            Pair(ManualParameter.ISO,     "ISO"),
            Pair(ManualParameter.SHUTTER, "S"),
            Pair(ManualParameter.APERTURE,"F"),
            Pair(ManualParameter.WB,      "WB"),
            Pair(ManualParameter.EV,      "EV"),
            Pair(ManualParameter.FOCUS,   "AF/MF")
        ).apply {
            if (apertureList == null || apertureList!!.size <= 1) {
                removeIf { it.first == ManualParameter.APERTURE }
            }
            if (isProGradingEnabled) {
                add(Pair(ManualParameter.CONTRAST,   "CON"))
                add(Pair(ManualParameter.SATURATION, "SAT"))
            }
        }

        viewBinding.parameterLayout.removeAllViews()
        paramTextViews.clear()

        for (p in params) {
            val tv = TextView(this).apply {
                text = p.second
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(colorInactive)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(120, 90).apply { setMargins(8, 0, 8, 0) }

                // 單擊：toggle H slider（金色）
                setOnClickListener {
                    val param = p.first
                    if (currentManualParam == param) {
                        currentManualParam = null
                        hideHorizontalSlider()
                    } else {
                        currentManualParam = param
                        updateSliderForParameter()
                    }
                    refreshParamPillColors(params)
                }
            }
            viewBinding.parameterLayout.addView(tv)
            paramTextViews.add(tv)
        }
    }

    /** 根據目前 H/V 選中狀態刷新 pill 顏色：金=H，青=V，白=未選 */
    private fun refreshParamPillColors(params: List<Pair<ManualParameter, String>>) {
        val colorH       = android.graphics.Color.parseColor("#FFD700")
        val colorV       = android.graphics.Color.parseColor("#00CFFF")
        val colorInactive = android.graphics.Color.WHITE
        paramTextViews.forEachIndexed { i, view ->
            if (i < params.size) {
                view.setTextColor(when (params[i].first) {
                    currentManualParam -> colorH
                    currentVParam      -> colorV
                    else               -> colorInactive
                })
            }
        }
        updateProStatusBar()
    }

    private fun formatParameterValue(param: ManualParameter, value: Float): String {
        return when (param) {
            ManualParameter.ISO -> {
                val presets = getSupportedIsoPresets()
                val idx = value.toInt().coerceIn(0, presets.size - 1)
                "ISO ${presets[idx]}"
            }
            ManualParameter.SHUTTER -> {
                val presets = getSupportedShutterPresets()
                val idx = value.toInt().coerceIn(0, presets.size - 1)
                val sec = presets[idx]
                if (sec >= 1.0) {
                    String.format(Locale.US, "S %.1fs", sec)
                } else {
                    "S 1/${Math.round(1.0 / sec)}s"
                }
            }
            ManualParameter.APERTURE -> {
                apertureList?.let {
                    val idx = value.toInt().coerceIn(0, it.size - 1)
                    "F${it[idx]}"
                } ?: "F--"
            }
            ManualParameter.WB -> {
                when (value.toInt()) {
                    0 -> "WB AUTO"
                    1 -> "DAYLIGHT"
                    2 -> "CLOUDY"
                    3 -> "FLUORESCENT"
                    4 -> "INCANDESCENT"
                    else -> "WB AUTO"
                }
            }
            ManualParameter.EV -> {
                val ev = value.toInt()
                if (ev > 0) "+$ev EV" else "$ev EV"
            }
            ManualParameter.FOCUS -> {
                if (value == 0f) "AF" else String.format(Locale.US, "MF %.2f", value)
            }
            ManualParameter.CONTRAST -> "CON ${String.format(Locale.US, "%.1f", value)}"
            ManualParameter.SATURATION -> "SAT ${String.format(Locale.US, "%.1f", value)}"
        }
    }

    private fun populatePresets(param: ManualParameter) {
        viewBinding.presetLayout.removeAllViews()
        val presets = mutableListOf<Pair<String, Float>>()
        
        when (param) {
            ManualParameter.ISO -> {
                val list = getSupportedIsoPresets()
                list.forEachIndexed { index, iso ->
                    presets.add(Pair(iso.toString(), index.toFloat()))
                }
            }
            ManualParameter.SHUTTER -> {
                val list = getSupportedShutterPresets()
                list.forEachIndexed { index, sec ->
                    val label = if (sec >= 1.0) {
                        String.format(Locale.US, "%.1fs", sec)
                    } else {
                        "1/${Math.round(1.0/sec)}s"
                    }
                    presets.add(Pair(label, index.toFloat()))
                }
            }
            ManualParameter.WB -> {
                presets.add(Pair("AUTO", 0f))
                presets.add(Pair("DAY", 1f))
                presets.add(Pair("CLD", 2f))
                presets.add(Pair("FLU", 3f))
                presets.add(Pair("INC", 4f))
            }
            ManualParameter.EV -> {
                listOf(-2f, -1f, 0f, 1f, 2f).forEach { presets.add(Pair(if(it>0) "+${it.toInt()}" else it.toInt().toString(), it)) }
            }
            ManualParameter.FOCUS -> {
                presets.add(Pair("AF", 0f))
                presets.add(Pair("MACRO", 0.1f))
                presets.add(Pair("INF", 10f))
            }
            ManualParameter.CONTRAST -> {
                presets.add(Pair("Low", 0.8f))
                presets.add(Pair("Std", 1.0f))
                presets.add(Pair("High", 1.3f))
            }
            ManualParameter.SATURATION -> {
                presets.add(Pair("B&W", 0.0f))
                presets.add(Pair("Std", 1.0f))
                presets.add(Pair("Vivid", 1.5f))
            }
            ManualParameter.APERTURE -> {
                apertureList?.forEachIndexed { index, f ->
                    presets.add(Pair(String.format(Locale.US, "f/%.1f", f), index.toFloat()))
                }
            }
            else -> {}
        }
        
        for (p in presets) {
            val tv = TextView(this).apply {
                text = p.first
                setTextColor(android.graphics.Color.WHITE)
                textSize = 12f
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_pill_button)
                layoutParams = LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 70).apply { setMargins(8, 0, 8, 0) }
                setPadding(32, 0, 32, 0)
                setOnClickListener {
                    try {
                        viewBinding.parameterSlider.value = p.second.coerceIn(viewBinding.parameterSlider.valueFrom, viewBinding.parameterSlider.valueTo)
                    } catch (e: Exception) {}
                }
            }
            viewBinding.presetLayout.addView(tv)
        }
    }

    private fun updateSliderForParameter() {
        val isGradingParam = currentManualParam == ManualParameter.CONTRAST || currentManualParam == ManualParameter.SATURATION
        if ((currentCameraMode != CameraMode.PRO && !isGradingParam && currentManualParam != ManualParameter.EV) || currentManualParam == null) {
            viewBinding.parameterControlPanel.visibility = android.view.View.GONE
            viewBinding.verticalSliderContainer.visibility = android.view.View.GONE
            return
        }
        viewBinding.parameterControlPanel.visibility = android.view.View.VISIBLE
        viewBinding.parameterSlider.clearOnChangeListeners()
        
        try {
            when (currentManualParam) {
                ManualParameter.ISO -> {
                    val presets = getSupportedIsoPresets()
                    viewBinding.parameterSlider.valueFrom = 0f
                    viewBinding.parameterSlider.valueTo = (presets.size - 1).toFloat()
                    viewBinding.parameterSlider.stepSize = 1f
                    val closestIdx = presets.mapIndexed { idx, v -> Pair(idx, Math.abs(v - currentIso)) }
                        .minByOrNull { it.second }?.first ?: 0
                    viewBinding.parameterSlider.value = closestIdx.toFloat()
                }
                ManualParameter.SHUTTER -> {
                    val presets = getSupportedShutterPresets()
                    viewBinding.parameterSlider.valueFrom = 0f
                    viewBinding.parameterSlider.valueTo = (presets.size - 1).toFloat()
                    viewBinding.parameterSlider.stepSize = 1f
                    val currentSec = currentExposureNs / 1_000_000_000.0
                    val closestIdx = presets.mapIndexed { idx, v -> Pair(idx, Math.abs(v - currentSec)) }
                        .minByOrNull { it.second }?.first ?: 0
                    viewBinding.parameterSlider.value = closestIdx.toFloat()
                }
                ManualParameter.APERTURE -> {
                    if (apertureList != null && apertureList!!.size > 1) {
                        viewBinding.parameterSlider.valueFrom = 0f
                        viewBinding.parameterSlider.valueTo = (apertureList!!.size - 1).toFloat()
                        viewBinding.parameterSlider.stepSize = 1f
                        val target = currentAperture ?: apertureList!![0]
                        val idx = apertureList!!.indexOfFirst { it == target }
                        viewBinding.parameterSlider.value = Math.max(0, idx).toFloat()
                    } else {
                        viewBinding.parameterControlPanel.visibility = android.view.View.GONE
                    }
                }
                ManualParameter.WB -> {
                    viewBinding.parameterSlider.valueFrom = 0f
                    viewBinding.parameterSlider.valueTo = 4f
                    viewBinding.parameterSlider.stepSize = 1f
                    viewBinding.parameterSlider.value = when(currentWbMode) {
                        CaptureRequest.CONTROL_AWB_MODE_AUTO -> 0f
                        CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT -> 1f
                        CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> 2f
                        CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT -> 3f
                        CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT -> 4f
                        else -> 0f
                    }
                }
                ManualParameter.EV -> {
                    viewBinding.parameterSlider.valueFrom = (evRange?.lower?.toFloat() ?: -4f)
                    viewBinding.parameterSlider.valueTo = (evRange?.upper?.toFloat() ?: 4f)
                    viewBinding.parameterSlider.stepSize = 1f
                    viewBinding.parameterSlider.value = currentEv.toFloat().coerceIn(viewBinding.parameterSlider.valueFrom, viewBinding.parameterSlider.valueTo)
                }
                ManualParameter.FOCUS -> {
                    viewBinding.parameterSlider.valueFrom = 0f
                    viewBinding.parameterSlider.valueTo = if (minFocusDistance > 0) minFocusDistance else 10f
                    viewBinding.parameterSlider.stepSize = 0f
                    viewBinding.parameterSlider.value = manualFocusDistance.coerceIn(viewBinding.parameterSlider.valueFrom, viewBinding.parameterSlider.valueTo)
                }
                ManualParameter.CONTRAST -> {
                    viewBinding.parameterSlider.valueFrom = 0.5f
                    viewBinding.parameterSlider.valueTo = 2.0f
                    viewBinding.parameterSlider.stepSize = 0f
                    viewBinding.parameterSlider.value = gradingContrast
                }
                ManualParameter.SATURATION -> {
                    viewBinding.parameterSlider.valueFrom = 0.0f
                    viewBinding.parameterSlider.valueTo = 2.0f
                    viewBinding.parameterSlider.stepSize = 0f
                    viewBinding.parameterSlider.value = gradingSaturation
                }
                else -> {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating slider", e)
        }
        
        viewBinding.parameterSlider.setLabelFormatter { valVal ->
            currentManualParam?.let { formatParameterValue(it, valVal) } ?: valVal.toString()
        }
        
        populatePresets(currentManualParam!!)
        val displayText = formatParameterValue(currentManualParam!!, viewBinding.parameterSlider.value)
        viewBinding.parameterValueText.text = displayText

        // 同步設定垂直滑桿（與水平同範圍）
        syncVerticalSliderSetup()

        viewBinding.parameterSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !isUpdatingSlider) {
                isUpdatingSlider = true
                viewBinding.parameterValueText.text = formatParameterValue(currentManualParam!!, value)
                syncVerticalSliderValue(value)
                onParameterSliderChanged(value)
                updateInfoOverlay()
                isUpdatingSlider = false
            }
        }
    }

    /** 垂直滑桿設定：複製水平滑桿的 from/to/step/value */
    private fun syncVerticalSliderSetup() {
        val h = viewBinding.parameterSlider
        val v = viewBinding.verticalParamSlider
        val paramName = when (currentManualParam) {
            ManualParameter.ISO -> "ISO"
            ManualParameter.SHUTTER -> "S"
            ManualParameter.APERTURE -> "A"
            ManualParameter.WB -> "WB"
            ManualParameter.EV -> "EV"
            ManualParameter.FOCUS -> "MF"
            ManualParameter.CONTRAST -> "CON"
            ManualParameter.SATURATION -> "SAT"
            else -> ""
        }
        viewBinding.vParamLabel.text = paramName
        try {
            v.clearOnChangeListeners()
            v.valueFrom = h.valueFrom
            v.valueTo = h.valueTo
            v.stepSize = h.stepSize
            v.value = h.value.coerceIn(h.valueFrom, h.valueTo)
            v.setLabelFormatter { valVal ->
                currentManualParam?.let { formatParameterValue(it, valVal) } ?: valVal.toString()
            }
        } catch (e: Exception) { Log.e(TAG, "verticalSlider setup", e) }

        // 顯示垂直滑桿
        viewBinding.verticalSliderContainer.visibility = android.view.View.VISIBLE

        // 動態調整 Slider 寬度 = container 高度（旋轉後變視覺高度）
        viewBinding.vSliderFrame.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                viewBinding.vSliderFrame.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val frameH = viewBinding.vSliderFrame.height
                if (frameH > 0) {
                    val lp = viewBinding.verticalParamSlider.layoutParams
                    lp.width = frameH
                    viewBinding.verticalParamSlider.layoutParams = lp
                }
            }
        })

        // 垂直滑桿改變 → 同步水平
        v.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !isUpdatingSlider) {
                isUpdatingSlider = true
                try { viewBinding.parameterSlider.value = value.coerceIn(h.valueFrom, h.valueTo) } catch (e: Exception) {}
                viewBinding.parameterValueText.text = formatParameterValue(currentManualParam!!, value)
                onParameterSliderChanged(value)
                updateInfoOverlay()
                isUpdatingSlider = false
            }
        }
    }

    private fun syncVerticalSliderValue(value: Float) {
        try {
            val v = viewBinding.verticalParamSlider
            viewBinding.verticalParamSlider.value = value.coerceIn(v.valueFrom, v.valueTo)
        } catch (e: Exception) {}
    }

    /**
     * 為指定參數設定垂直滑桿（独立於 H slider）
     * 呼叫 showVerticalSlider() 通用 API，不和 H slider 雙向同步。
     */
    private fun setupVSliderForParam(param: ManualParameter) {
        val label = when(param) {
            ManualParameter.ISO -> "ISO"
            ManualParameter.SHUTTER -> "S"
            ManualParameter.APERTURE -> "A"
            ManualParameter.WB -> "WB"
            ManualParameter.EV -> "EV"
            ManualParameter.FOCUS -> "MF"
            ManualParameter.CONTRAST -> "CON"
            ManualParameter.SATURATION -> "SAT"
            else -> param.name
        }
        var from = 0f; var to = 100f; var step = 0f; var value = 0f
        try {
            when(param) {
                ManualParameter.ISO -> {
                    val presets = getSupportedIsoPresets()
                    from = 0f
                    to = (presets.size - 1).toFloat()
                    step = 1f
                    val closestIdx = presets.mapIndexed { idx, v -> Pair(idx, Math.abs(v - currentIso)) }
                        .minByOrNull { it.second }?.first ?: 0
                    value = closestIdx.toFloat()
                }
                ManualParameter.SHUTTER -> {
                    val presets = getSupportedShutterPresets()
                    from = 0f
                    to = (presets.size - 1).toFloat()
                    step = 1f
                    val currentSec = currentExposureNs / 1_000_000_000.0
                    val closestIdx = presets.mapIndexed { idx, v -> Pair(idx, Math.abs(v - currentSec)) }
                        .minByOrNull { it.second }?.first ?: 0
                    value = closestIdx.toFloat()
                }
                ManualParameter.APERTURE -> {
                    if (apertureList != null && apertureList!!.size > 1) {
                        from = 0f; to = (apertureList!!.size - 1).toFloat(); step = 1f
                        val idx = apertureList!!.indexOfFirst { it == currentAperture }
                        value = Math.max(0, idx).toFloat()
                    } else return
                }
                ManualParameter.WB -> {
                    from = 0f; to = 4f; step = 1f
                    value = when(currentWbMode) {
                        CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT -> 1f
                        CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> 2f
                        CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT -> 3f
                        CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT -> 4f
                        else -> 0f
                    }
                }
                ManualParameter.EV -> {
                    from = evRange?.lower?.toFloat() ?: -4f
                    to   = evRange?.upper?.toFloat() ?: 4f; step = 1f
                    value = currentEv.toFloat().coerceIn(from, to)
                }
                ManualParameter.FOCUS -> {
                    from = 0f; to = if (minFocusDistance > 0) minFocusDistance else 10f
                    value = manualFocusDistance.coerceIn(from, to)
                }
                ManualParameter.CONTRAST -> { from = 0.5f; to = 2.0f; value = gradingContrast }
                ManualParameter.SATURATION -> { from = 0.0f; to = 2.0f; value = gradingSaturation }
                else -> {}
            }
        } catch (e: Exception) { Log.e(TAG, "setupVSliderForParam", e); return }

        showVerticalSlider(param, label, from, to, step, value) { v -> onParamChanged(param, v) }
    }

    /** 參數值變更的統一入口（H slider 和 V slider 都呼叫此函式） */
    private fun onParamChanged(param: ManualParameter, value: Float) {
        when (param) {
            ManualParameter.ISO -> {
                val presets = getSupportedIsoPresets()
                val idx = value.toInt().coerceIn(0, presets.size - 1)
                currentIso = presets[idx]
            }
            ManualParameter.SHUTTER -> {
                val presets = getSupportedShutterPresets()
                val idx = value.toInt().coerceIn(0, presets.size - 1)
                val sec = presets[idx]
                currentExposureNs = (sec * 1_000_000_000).toLong()
            }
            ManualParameter.APERTURE -> apertureList?.let {
                currentAperture = it[value.toInt().coerceIn(0, it.size - 1)]
            }
            ManualParameter.WB -> currentWbMode = when(value.toInt()) {
                1 -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
                2 -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
                3 -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
                4 -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
                else -> CaptureRequest.CONTROL_AWB_MODE_AUTO
            }
            ManualParameter.EV -> currentEv = value.toInt()
            ManualParameter.CONTRAST -> gradingContrast = value
            ManualParameter.SATURATION -> gradingSaturation = value
            ManualParameter.FOCUS -> manualFocusDistance = value
            else -> {}
        }
        updateLutEffect()
        updatePreview()
        updateProStatusBar()
    }

    /**
     * 更新 PRO 模式數值列：將所有參數的當前傀寫入對應 TextView。
     * 當前選中的 H/V 參數顯示金色，其餘為白色。
     */
    private fun updateProStatusBar() {
        val colorH       = android.graphics.Color.parseColor("#FFD700")
        val colorV       = android.graphics.Color.parseColor("#00CFFF")
        val colorDefault = android.graphics.Color.WHITE

        fun colorFor(param: ManualParameter) = when (param) {
            currentManualParam -> colorH
            currentVParam      -> colorV
            else               -> colorDefault
        }

        // --- 共用數值格式化 ---
        val shutterStr = run {
            val sec = currentExposureNs / 1_000_000_000.0
            if (sec >= 1.0) String.format(java.util.Locale.US, "%.1fs", sec)
            else "1/${Math.round(1.0 / sec)}s"
        }
        val isoStr = currentIso.toString()
        val evStr = run {
            val step = evStep ?: android.util.Rational(1, 3)
            val evValue = currentEv.toFloat() * step.numerator.toFloat() / step.denominator.toFloat()
            if (evValue >= 0) String.format(java.util.Locale.US, "+%.1f", evValue)
            else String.format(java.util.Locale.US, "%.1f", evValue)
        }
        val wbStr = when (currentWbMode) {
            CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT         -> "5600K"
            CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT  -> "6500K"
            CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT      -> "4000K"
            CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT     -> "3200K"
            else                                             -> "AUTO"
        }

        // --- 更新 AUTO HUD ---
        viewBinding.hudShutter.text = shutterStr
        viewBinding.hudIso.text = isoStr
        viewBinding.hudWb.text = wbStr
        viewBinding.hudEv.text = evStr

        // --- 更新 PRO Status Bar ---
        if (currentCameraMode == CameraMode.PRO) {
            viewBinding.statusEv.text = evStr
            viewBinding.statusEv.setTextColor(colorFor(ManualParameter.EV))

            if (apertureList != null && apertureList!!.size > 1) {
                viewBinding.statusABlock.visibility = android.view.View.VISIBLE
                val ap = currentAperture ?: apertureList!![0]
                viewBinding.statusAperture.text = String.format(java.util.Locale.US, "F%.1f", ap)
                viewBinding.statusAperture.setTextColor(colorFor(ManualParameter.APERTURE))
            } else {
                viewBinding.statusABlock.visibility = android.view.View.GONE
            }

            viewBinding.statusShutter.text = shutterStr
            viewBinding.statusShutter.setTextColor(colorFor(ManualParameter.SHUTTER))

            viewBinding.statusIso.text = isoStr
            viewBinding.statusIso.setTextColor(colorFor(ManualParameter.ISO))

            viewBinding.statusWb.text = wbStr
            viewBinding.statusWb.setTextColor(colorFor(ManualParameter.WB))

            viewBinding.statusFocus.text = if (manualFocusDistance == 0f) "AF" else
                String.format(java.util.Locale.US, "%.2f", manualFocusDistance)
            viewBinding.statusFocus.setTextColor(colorFor(ManualParameter.FOCUS))
        }
    }

    // =========================================================
    // 通用滑桿 API — 任何功能都可直接呼叫
    // =========================================================

    /**
     * 顯示水平滑桿並接管 callback。
     * 不影響 PRO mode 參數系統；若在 PRO mode 下呼叫並不想和參數 tab 衝突，
     * 先呼叫 hideHorizontalSlider() 再呼叫此函式。
     */
    fun showHorizontalSlider(
        label: String,
        valueFrom: Float,
        valueTo: Float,
        stepSize: Float = 0f,
        value: Float,
        onChange: (Float) -> Unit
    ) {
        hSliderCallback = onChange
        val slider = viewBinding.parameterSlider
        try {
            slider.clearOnChangeListeners()
            slider.valueFrom = valueFrom
            slider.valueTo = valueTo
            slider.stepSize = stepSize
            slider.value = value.coerceIn(valueFrom, valueTo)
        } catch (e: Exception) { Log.e(TAG, "showHorizontalSlider", e) }
        viewBinding.parameterValueText.text = label
        slider.addOnChangeListener { _, v, fromUser ->
            if (fromUser && !isUpdatingSlider) {
                isUpdatingSlider = true
                viewBinding.parameterValueText.text = label
                hSliderCallback?.invoke(v)
                isUpdatingSlider = false
            }
        }
        viewBinding.parameterControlPanel.visibility = android.view.View.VISIBLE
    }

    /**
     * 顯示垂直滑桿並接管 callback。
     * 垂直滑桿旋轉 270°：上滑 = 從 valueFrom 到 valueTo（說明標譤會顯示 +/-）。
     */
    fun showVerticalSlider(
        param: ManualParameter,
        label: String,
        valueFrom: Float,
        valueTo: Float,
        stepSize: Float = 0f,
        value: Float,
        onChange: (Float) -> Unit
    ) {
        vSliderCallback = onChange
        viewBinding.vParamLabel.text = label
        val v = viewBinding.verticalParamSlider
        try {
            v.clearOnChangeListeners()
            v.valueFrom = valueFrom
            v.valueTo = valueTo
            v.stepSize = stepSize
            v.value = value.coerceIn(valueFrom, valueTo)
            v.setLabelFormatter { valVal ->
                formatParameterValue(param, valVal)
            }
        } catch (e: Exception) { Log.e(TAG, "showVerticalSlider", e) }
        v.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !isUpdatingSlider) {
                isUpdatingSlider = true
                vSliderCallback?.invoke(value)
                isUpdatingSlider = false
            }
        }
        viewBinding.verticalSliderContainer.visibility = android.view.View.VISIBLE
        // 動態調整旋轉後的視覺高度
        viewBinding.vSliderFrame.viewTreeObserver.addOnGlobalLayoutListener(
            object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    viewBinding.vSliderFrame.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    val h = viewBinding.vSliderFrame.height
                    if (h > 0 && viewBinding.verticalParamSlider.layoutParams.width != h) {
                        viewBinding.verticalParamSlider.layoutParams.width = h
                        viewBinding.verticalParamSlider.requestLayout()
                    }
                }
            }
        )
    }

    /** 隐藏水平滑桿並釋放 callback */
    fun hideHorizontalSlider() {
        viewBinding.parameterControlPanel.visibility = android.view.View.GONE
        viewBinding.parameterSlider.clearOnChangeListeners()
        hSliderCallback = null
    }

    /** 隐藏垂直滑桿並釋放 callback */
    fun hideVerticalSlider() {
        viewBinding.verticalSliderContainer.visibility = android.view.View.GONE
        viewBinding.verticalParamSlider.clearOnChangeListeners()
        vSliderCallback = null
    }

    // =========================================================

    private fun onParameterSliderChanged(value: Float) {
        when (currentManualParam) {
            ManualParameter.ISO -> {
                val presets = getSupportedIsoPresets()
                val idx = value.toInt().coerceIn(0, presets.size - 1)
                currentIso = presets[idx]
            }
            ManualParameter.SHUTTER -> {
                val presets = getSupportedShutterPresets()
                val idx = value.toInt().coerceIn(0, presets.size - 1)
                val sec = presets[idx]
                currentExposureNs = (sec * 1_000_000_000).toLong()
            }
            ManualParameter.APERTURE -> {
                apertureList?.let {
                    val idx = value.toInt().coerceIn(0, it.size - 1)
                    currentAperture = it[idx]
                }
            }
            ManualParameter.WB -> {
                currentWbMode = when(value.toInt()) {
                    0 -> CaptureRequest.CONTROL_AWB_MODE_AUTO
                    1 -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
                    2 -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
                    3 -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
                    4 -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
                    else -> CaptureRequest.CONTROL_AWB_MODE_AUTO
                }
            }
            ManualParameter.EV -> {
                currentEv = value.toInt()
            }
            ManualParameter.CONTRAST -> gradingContrast = value
            ManualParameter.SATURATION -> gradingSaturation = value
            ManualParameter.FOCUS -> manualFocusDistance = value
            else -> {}
        }
        updateLutEffect()
        updatePreview()
        updateProStatusBar()
    }

    private fun startHistogramAnalysis() {
        if (histogramRunnable != null) return
        histogramRunnable = object : java.lang.Runnable {
            override fun run() {
                if (!isHistogramEnabled) {
                    histogramRunnable = null
                    return
                }
                
                if (viewBinding.viewFinder.isAvailable) {
                    val bitmap = viewBinding.viewFinder.getBitmap(64, 64)
                    if (bitmap != null) {
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        
                        val histogram = IntArray(256)
                        for (pixel in pixels) {
                            val r = (pixel shr 16) and 0xFF
                            val g = (pixel shr 8) and 0xFF
                            val b = pixel and 0xFF
                            val y = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                            if (y in 0..255) {
                                histogram[y]++
                            }
                        }
                        
                        val numBins = 64
                        val bins = IntArray(numBins)
                        val binSize = 256 / numBins
                        for (i in 0..255) {
                            bins[i / binSize] += histogram[i]
                        }
                        
                        runOnUiThread {
                            viewBinding.histogramView.setHistogramData(bins)
                        }
                    }
                }
                backgroundHandler?.postDelayed(this, 100)
            }
        }
        backgroundHandler?.post(histogramRunnable!!)
    }

    private fun stopHistogramAnalysis() {
        histogramRunnable?.let { backgroundHandler?.removeCallbacks(it) }
        histogramRunnable = null
    }

    private fun triggerTouchToFocus(x: Float, y: Float) {
        val sensorArraySize = currentCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        
        // 1. 顯示對焦環動畫
        viewBinding.focusRing.apply {
            translationX = x - width / 2
            translationY = y - height / 2
            visibility = android.view.View.VISIBLE
            alpha = 1f
            scaleX = 1.5f
            scaleY = 1.5f
            animate().alpha(0f).scaleX(1f).scaleY(1f).setDuration(800).withEndAction { visibility = android.view.View.GONE }.start()
        }

        // 2. 計算對焦區域 (簡化版：映射 View 座標到 Sensor 座標)
        val viewWidth = viewBinding.viewFinder.width
        val viewHeight = viewBinding.viewFinder.height
        val halfRectWidth = 100
        val halfRectHeight = 100
        
        val centerX = (x / viewWidth * sensorArraySize.width()).toInt()
        val centerY = (y / viewHeight * sensorArraySize.height()).toInt()
        
        val focusRegion = MeteringRectangle(
            Math.max(centerX - halfRectWidth, 0),
            Math.max(centerY - halfRectHeight, 0),
            halfRectWidth * 2,
            halfRectHeight * 2,
            MeteringRectangle.METERING_WEIGHT_MAX
        )

        try {
            captureSession?.stopRepeating()
            
            // 取消之前的對焦
            previewRequestBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            captureSession?.capture(previewRequestBuilder!!.build(), null, backgroundHandler)
            
            // 設置新的對焦區域
            previewRequestBuilder?.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(focusRegion))
            previewRequestBuilder?.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(focusRegion))
            previewRequestBuilder?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            previewRequestBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            previewRequestBuilder?.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            
            captureSession?.capture(previewRequestBuilder!!.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    super.onCaptureCompleted(session, request, result)
                    previewRequestBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                    updatePreview()
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Touch to focus failed", e)
        }
    }

    private fun startVoiceListening() {
        runOnUiThread {
            if (speechRecognizer == null) {
                speechRecognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this)
                speechRecognizer?.setRecognitionListener(object : android.speech.RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) {
                        if (isVoiceControlEnabled) {
                            // 延遲重試以避免循環報錯
                            viewBinding.root.postDelayed({ if (isVoiceControlEnabled) startVoiceListening() }, 1000)
                        }
                    }
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                        if (matches != null) {
                            for (match in matches) {
                                if (match.contains("拍") || match.contains("拍照") || match.contains("cheese") || match.contains("茄子")) {
                                    takePhoto()
                                    break
                                }
                            }
                        }
                        if (isVoiceControlEnabled) startVoiceListening()
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
            val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
            }
            speechRecognizer?.startListening(intent)
        }
    }

    private fun stopVoiceListening() {
        runOnUiThread {
            speechRecognizer?.stopListening()
        }
    }

    private fun closeSubmenu() {
        if (currentExpandedMenuId != -1) {
            viewBinding.subMenuScroll.visibility = android.view.View.GONE
            viewBinding.subMenuLayout.removeAllViews()
            currentExpandedMenuId = -1
        }
    }

    private fun showInlineSubmenu(menuViewId: Int, options: Array<String>, onSelect: (Int) -> Unit) {
        resetInactivityTimer()
        // 同一個按鈕再次點擊 → 收合次選單
        if (currentExpandedMenuId == menuViewId) {
            viewBinding.subMenuScroll.visibility = android.view.View.GONE
            viewBinding.subMenuLayout.removeAllViews()
            currentExpandedMenuId = -1
            return
        }
        currentExpandedMenuId = menuViewId
        viewBinding.subMenuLayout.removeAllViews()

        options.forEachIndexed { index, label ->
            val btn = TextView(this).apply {
                text = label
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundResource(R.drawable.bg_pill_button)
                gravity = android.view.Gravity.CENTER
                setPadding(40, 0, 40, 0)
                layoutParams = LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 76
                ).apply { setMargins(10, 6, 10, 6) }
                setOnClickListener {
                    onSelect(index)
                    // 選完後收合
                    viewBinding.subMenuScroll.visibility = android.view.View.GONE
                    viewBinding.subMenuLayout.removeAllViews()
                    currentExpandedMenuId = -1
                }
            }
            viewBinding.subMenuLayout.addView(btn)
        }
        viewBinding.subMenuScroll.visibility = android.view.View.VISIBLE
    }

    private fun showMenuSelectionDialog(title: String, options: Array<String>, onSelect: (Int) -> Unit) {
        resetInactivityTimer()
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(options) { dialog, which ->
                onSelect(which)
                dialog.dismiss()
            }
            .show()
    }

    private fun resetInactivityTimer() {
        lastActivityTime = System.currentTimeMillis()
        inactivityHandler.removeCallbacksAndMessages(null)
        inactivityHandler.postDelayed({
            if (timeLapseIntervalMs == 0L) {
                Log.d(TAG, "Inactivity timeout: finishing app")
                finish()
            } else {
                // 縮時攝影中，不關閉程式，但重啟計時以備之後使用
                resetInactivityTimer()
            }
        }, INACTIVITY_TIMEOUT_MS)
    }

    private fun updateTimeLapseLogic() {
        timeLapseHandler.removeCallbacksAndMessages(null)
        if (timeLapseIntervalMs > 0) {
            // 縮時攝影開啟：允許螢幕依照系統設定關閉以省電
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            startTimeLapseTask()
        } else {
            // 縮時攝影關閉：恢復相機預設的「保持螢幕開啟」
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun startTimeLapseTask() {
        timeLapseHandler.postDelayed(object : Runnable {
            override fun run() {
                if (timeLapseIntervalMs > 0) {
                    takePhoto()
                    timeLapseHandler.postDelayed(this, timeLapseIntervalMs)
                }
            }
        }, timeLapseIntervalMs)
    }



    private fun showLutManagerDialog() {
        val lutDir = File(filesDir, "luts")
        if (!lutDir.exists()) lutDir.mkdirs()
        
        val files = lutDir.listFiles { _, name -> name.endsWith(".cube") }?.toList() ?: emptyList()
        val names = files.map { it.name }.toMutableList()
        names.add(0, "None (Reset)")
        
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("LUT 管理")
        
        val listView = android.widget.ListView(this)
        val adapter = object : android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, names) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = super.getView(position, convertView, parent) as TextView
                if (position > 0 && names[position] == currentLutName) {
                    view.setTextColor(android.graphics.Color.YELLOW)
                    view.text = "✓ " + names[position]
                }
                return view
            }
        }
        listView.adapter = adapter
        
        listView.setOnItemClickListener { _, _, position, _ ->
            if (position == 0) {
                customLutBitmap = null
                currentLutName = null
                updateLutEffect()
            } else {
                loadCustomLut(android.net.Uri.fromFile(files[position - 1]))
                currentLutName = files[position - 1].name
            }
        }
        
        listView.setOnItemLongClickListener { _, _, position, _ ->
            if (position > 0) {
                val file = files[position - 1]
                android.app.AlertDialog.Builder(this)
                    .setTitle("管理: ${file.name}")
                    .setItems(arrayOf("重新命名", "刪除")) { _, which ->
                        when (which) {
                            0 -> { // 重新命名
                                val input = android.widget.EditText(this@MainActivity).apply {
                                    setText(file.name.removeSuffix(".cube"))
                                }
                                android.app.AlertDialog.Builder(this@MainActivity)
                                    .setTitle("重新命名")
                                    .setView(input)
                                    .setPositiveButton("確定") { _, _ ->
                                        val newName = input.text.toString().trim()
                                        if (newName.isNotEmpty()) {
                                            val newFile = File(file.parent, "$newName.cube")
                                            if (file.renameTo(newFile)) {
                                                if (currentLutName == file.name) currentLutName = newFile.name
                                                showLutManagerDialog()
                                            } else {
                                                Toast.makeText(this@MainActivity, "更名失敗", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                    .setNegativeButton("取消", null)
                                    .show()
                            }
                            1 -> { // 刪除
                                android.app.AlertDialog.Builder(this@MainActivity)
                                    .setTitle("刪除 LUT")
                                    .setMessage("確定要刪除 ${file.name} 嗎？")
                                    .setPositiveButton("刪除") { _, _ ->
                                        if (currentLutName == file.name) {
                                            customLutBitmap = null
                                            currentLutName = null
                                            updateLutEffect()
                                        }
                                        file.delete()
                                        showLutManagerDialog()
                                    }
                                    .setNegativeButton("取消", null)
                                    .show()
                            }
                        }
                    }
                    .show()
            }
            true
        }
        
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(listView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            val btnImport = android.widget.Button(this@MainActivity).apply {
                text = "匯入新檔案 (.cube)"
                setOnClickListener { 
                    lutPickerLauncher.launch("*/*")
                }
            }
            addView(btnImport)
        }
        
        builder.setView(layout)
        builder.setNegativeButton("關閉", null)
        builder.show()
    }

    private fun loadCustomLut(uri: android.net.Uri) {
        try {
            // 如果是外部路徑，先複製到內部儲存空間
            val fileName = uri.lastPathSegment?.split("/")?.last() ?: "custom_${System.currentTimeMillis()}.cube"
            val lutDir = File(filesDir, "luts")
            if (!lutDir.exists()) lutDir.mkdirs()
            val localFile = File(lutDir, fileName)
            
            if (!uri.toString().contains(filesDir.absolutePath)) {
                contentResolver.openInputStream(uri)?.use { input ->
                    localFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            
            val reader = localFile.bufferedReader()
            var size = 0
            val data = mutableListOf<Float>()
            reader.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("LUT_3D_SIZE")) size = trimmed.split(" ").last().toInt()
                else if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && !trimmed[0].isLetter()) {
                    val parts = trimmed.split(Regex("\\s+"))
                    if (parts.size >= 3) {
                        data.add(parts[0].toFloat()); data.add(parts[1].toFloat()); data.add(parts[2].toFloat())
                    }
                }
            }
            if (size > 0 && data.size >= size * size * size * 3) {
                customLutSize = size
                val lutBitmap = Bitmap.createBitmap(size, size * size, Bitmap.Config.ARGB_8888)
                for (b in 0 until size) {
                    for (g in 0 until size) {
                        for (r in 0 until size) {
                            val idx = (b * size * size + g * size + r) * 3
                            val color = Color.rgb((data[idx]*255).toInt(), (data[idx+1]*255).toInt(), (data[idx+2]*255).toInt())
                            lutBitmap.setPixel(r, g + b * size, color)
                        }
                    }
                }
                customLutBitmap = lutBitmap
                currentLutName = localFile.name
                runOnUiThread { updateLutEffect(); Toast.makeText(this, "LUT 套用成功", Toast.LENGTH_SHORT).show() }
            }
        } catch (e: Exception) { Log.e(TAG, "LUT Error", e) }
    }

    private val sensorListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(event: android.hardware.SensorEvent) {
            if (!isLevelEnabled) return
            if (event.sensor.type == android.hardware.Sensor.TYPE_ACCELEROMETER) {
                System.arraycopy(event.values, 0, gravityValues, 0, 3)
            } else if (event.sensor.type == android.hardware.Sensor.TYPE_MAGNETIC_FIELD) {
                System.arraycopy(event.values, 0, magneticValues, 0, 3)
            }

            val r = FloatArray(9)
            val i = FloatArray(9)
            if (android.hardware.SensorManager.getRotationMatrix(r, i, gravityValues, magneticValues)) {
                val orientation = FloatArray(3)
                android.hardware.SensorManager.getOrientation(r, orientation)
                val pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
                val roll = Math.toDegrees(orientation[2].toDouble()).toFloat()
                
                runOnUiThread {
                    viewBinding.levelLine.rotation = -roll
                    viewBinding.levelLine.translationY = (pitch * 5).coerceIn(-100f, 100f)
                    // 當接近水平時變色
                    val color = if (Math.abs(roll) < 1.0 && Math.abs(pitch) < 1.0) 
                        android.graphics.Color.GREEN else android.graphics.Color.WHITE
                    viewBinding.levelLine.setBackgroundColor(color)
                    viewBinding.levelCenterDot.setBackgroundColor(color)
                }
            }
        }
        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
    }

    private fun showSuperHdrSettingsDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 40)
        }

        val minInput = android.widget.EditText(this).apply {
            hint = getString(R.string.shdr_hint_min)
            setText(superHdrMinIso.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val maxInput = android.widget.EditText(this).apply {
            hint = getString(R.string.shdr_hint_max)
            setText(superHdrMaxIso.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        layout.addView(TextView(this).apply { text = getString(R.string.shdr_min_iso) })
        layout.addView(minInput)
        layout.addView(TextView(this).apply { text = "\n" + getString(R.string.shdr_max_iso) })
        layout.addView(maxInput)

        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.shdr_settings_title))
            .setView(layout)
            .setPositiveButton(getString(R.string.shdr_save)) { _, _ ->
                superHdrMinIso = minInput.text.toString().toIntOrNull() ?: superHdrMinIso
                superHdrMaxIso = maxInput.text.toString().toIntOrNull() ?: superHdrMaxIso
                Toast.makeText(this, getString(R.string.shdr_save_success, superHdrMinIso, superHdrMaxIso), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.shdr_cancel), null)
            .show()
    }

    private fun fetchAllTelescopeMedia(): List<MediaItem> {
        val mediaList = mutableListOf<MediaItem>()
        
        // 1. Fetch images
        val imgProjection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN
        )
        val imgSelection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE 'Telescope_%'"
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            imgProjection,
            imgSelection,
            null,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol)
                val date = cursor.getLong(dateCol)
                val uri = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                mediaList.add(MediaItem(uri, date, false, name))
            }
        }
        
        // 2. Fetch videos
        val vidProjection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_TAKEN
        )
        val vidSelection = "${MediaStore.Video.Media.DISPLAY_NAME} LIKE 'Telescope_%'"
        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            vidProjection,
            vidSelection,
            null,
            "${MediaStore.Video.Media.DATE_TAKEN} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol)
                val date = cursor.getLong(dateCol)
                val uri = android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                mediaList.add(MediaItem(uri, date, true, name))
            }
        }
        
        return mediaList.sortedByDescending { it.dateTaken }
    }

    private fun openCustomPreviewOverlay() {
        Thread {
            customMediaList = fetchAllTelescopeMedia()
            customMediaIndex = if (lastMediaUri != null) {
                val idx = customMediaList.indexOfFirst { it.uri == lastMediaUri }
                if (idx != -1) idx else 0
            } else {
                0
            }
            
            runOnUiThread {
                if (customMediaList.isEmpty()) {
                    Toast.makeText(this, "目前沒有望遠鏡相片或影片", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                
                viewBinding.customPreviewOverlay.visibility = android.view.View.VISIBLE
                loadMediaInPreview(customMediaIndex)
            }
        }.start()
    }

    private fun loadMediaInPreview(index: Int) {
        if (index < 0 || index >= customMediaList.size) return
        val item = customMediaList[index]
        
        viewBinding.tvPreviewTitle.text = "${item.displayName} (${index + 1}/${customMediaList.size})"
        
        if (viewBinding.previewVideoView.isPlaying) {
            viewBinding.previewVideoView.stopPlayback()
        }
        
        if (item.isVideo) {
            viewBinding.previewImageView.visibility = android.view.View.GONE
            viewBinding.previewVideoView.visibility = android.view.View.VISIBLE
            viewBinding.btnVideoPlayOverlay.visibility = android.view.View.VISIBLE
            
            viewBinding.previewVideoView.setVideoURI(item.uri)
            
            viewBinding.previewVideoView.setOnCompletionListener {
                viewBinding.btnVideoPlayOverlay.visibility = android.view.View.VISIBLE
            }
            
            viewBinding.btnVideoPlayOverlay.setOnClickListener {
                viewBinding.btnVideoPlayOverlay.visibility = android.view.View.GONE
                viewBinding.previewVideoView.start()
            }
            
            viewBinding.previewVideoView.setOnClickListener {
                if (viewBinding.previewVideoView.isPlaying) {
                    viewBinding.previewVideoView.pause()
                    viewBinding.btnVideoPlayOverlay.visibility = android.view.View.VISIBLE
                } else {
                    viewBinding.btnVideoPlayOverlay.visibility = android.view.View.GONE
                    viewBinding.previewVideoView.start()
                }
            }
        } else {
            viewBinding.previewImageView.visibility = android.view.View.VISIBLE
            viewBinding.previewVideoView.visibility = android.view.View.GONE
            viewBinding.btnVideoPlayOverlay.visibility = android.view.View.GONE
            
            viewBinding.previewImageView.setImageURI(item.uri)
        }
    }

    private data class MediaItem(
        val uri: android.net.Uri,
        val dateTaken: Long,
        val isVideo: Boolean,
        val displayName: String
    )

    private data class ZoomConfig(
        val label: String,
        val cameraId: String,
        val isTelephoto: Boolean = false,
        val zoomRatio: Float = 1.0f
    )

    companion object {
        private const val TAG = "TelescopeApp"
        private const val FILENAME_FORMAT = "'Telescope_'yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                android.Manifest.permission.CAMERA,
                android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(
                android.Manifest.permission.CAMERA,
                android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }
    }
}
