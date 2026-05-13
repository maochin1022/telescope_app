package com.example.telescopeapp

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
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
import java.text.SimpleDateFormat
import java.util.*

enum class CameraMode { AUTO, PRO, HDR }
enum class ManualParameter { ISO, SHUTTER, APERTURE, WB, EV, FOCUS }

class MainActivity : AppCompatActivity() {

    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var cameraManager: CameraManager

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var imageReader: ImageReader? = null
    private var mediaRecorder: android.media.MediaRecorder? = null
    private var isRecording = false
    private var videoUri: android.net.Uri? = null
    private var mediaActionSound: android.media.MediaActionSound? = null
    private var lastMediaUri: android.net.Uri? = null
    private var bestPreviewSize: Size? = null
    private var bestJpegSize: Size? = null
    private var supportedOisModes: IntArray? = null
    private var supportedAfModes: IntArray? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val lensTextViews = mutableListOf<TextView>()
    private var currentCameraId: String? = null
    
    // Camera Control State
    private var currentCameraMode = CameraMode.AUTO
    private var currentManualParam: ManualParameter? = null

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
    private var isHistogramEnabled = true

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

        if (allPermissionsGranted()) {
            setupDynamicLenses()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        viewBinding.imageCaptureButton.setOnClickListener { takePhoto() }
        viewBinding.videoCaptureButton.setOnClickListener {
            if (isRecording) stopRecordingVideo() else startRecordingVideo()
        }

        viewBinding.parameterSlider.addOnChangeListener { _, value, _ ->
            if (currentManualParam != null) {
                viewBinding.parameterValueText.text = formatParameterValue(currentManualParam!!, value)
            }
            onParameterSliderChanged(value)
        }

        viewBinding.btnParamMinus.setOnClickListener {
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
            input.hint = "輸入數值 (例如 ISO 400 或 快門 0.01s)"
            android.app.AlertDialog.Builder(this)
                .setTitle("手動輸入")
                .setView(input)
                .setPositiveButton("確定") { _, _ ->
                    try {
                        val v = input.text.toString().toFloat()
                        val sliderVal = when (currentManualParam) {
                            ManualParameter.SHUTTER -> {
                                val sec = if (v > 10) 1.0 / v else v
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

        viewBinding.histogramToggleButton.setOnClickListener {
            isHistogramEnabled = !isHistogramEnabled
            viewBinding.histogramToggleButton.setBackgroundResource(if (isHistogramEnabled) R.drawable.bg_pill_button_active else R.drawable.bg_pill_button)
            viewBinding.histogramToggleButton.setTextColor(if (isHistogramEnabled) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            
            if (isHistogramEnabled && currentCameraMode == CameraMode.PRO) {
                viewBinding.histogramView.visibility = android.view.View.VISIBLE
                startHistogramAnalysis()
            } else {
                viewBinding.histogramView.visibility = android.view.View.GONE
                stopHistogramAnalysis()
            }
        }

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

        viewBinding.thumbnailView.setOnClickListener {
            lastMediaUri?.let { uri ->
                val mimeType = contentResolver.getType(uri) ?: "*/*"
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mimeType)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaActionSound?.release()
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (viewBinding.viewFinder.isAvailable) {
            openCamera(currentCameraId ?: return)
        } else {
            viewBinding.viewFinder.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
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
        // --- 核心修正：翻轉預覽畫面 180 度 ---
        matrix.postRotate(180f, viewWidth / 2f, viewHeight / 2f)
        viewBinding.viewFinder.setTransform(matrix)
    }

    private fun openCamera(cameraId: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return

        try {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            supportedOisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
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
                val previewSizes = map.getOutputSizes(SurfaceTexture::class.java)
                val standard1080p = Size(1920, 1080)
                
                if (jpegSizes != null && previewSizes != null) {
                    val has1080pPreview = previewSizes.contains(standard1080p)
                    val has1080pJpeg = jpegSizes.contains(standard1080p)

                    if (has1080pPreview && has1080pJpeg) {
                        bestPreviewSize = standard1080p
                        bestJpegSize = standard1080p
                    } else {
                        val sortedJpegSizes = jpegSizes.sortedByDescending { it.width * it.height }
                        val safePreviewSizes = previewSizes.filter { it.width * it.height <= 1920 * 1080 }.sortedByDescending { it.width * it.height }
                        
                        var found = false
                        for (jpegSize in sortedJpegSizes) {
                            val jpegRatio = jpegSize.width.toDouble() / jpegSize.height
                            val matchingPreview = safePreviewSizes.firstOrNull { previewSize ->
                                val previewRatio = previewSize.width.toDouble() / previewSize.height
                                Math.abs(jpegRatio - previewRatio) < 0.05
                            }
                            if (matchingPreview != null) {
                                bestJpegSize = jpegSize
                                bestPreviewSize = matchingPreview
                                found = true
                                break
                            }
                        }
                        if (!found) {
                            bestJpegSize = sortedJpegSizes.firstOrNull() ?: standard1080p
                            bestPreviewSize = safePreviewSizes.firstOrNull() ?: previewSizes.firstOrNull() ?: standard1080p
                        }
                    }
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
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
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
            // --- 核心修正：錄影方向也翻轉 180 度 ---
            setOrientationHint(180)
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
                lastMediaUri = it
                try {
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
            val surface = Surface(texture)

            imageReader = ImageReader.newInstance(jpegW, jpegH, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader ->
                    backgroundHandler?.post {
                        val image = reader.acquireLatestImage()
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.capacity())
                        buffer.get(bytes)
                        saveImage(bytes)
                        image.close()
                    }
                }, backgroundHandler)
            }

            previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
            }

            cameraDevice!!.createCaptureSession(
                listOf(surface, imageReader!!.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = session
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
            
            // 防手震設定
            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            if (supportedOisModes?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) == true) {
                set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
            } else {
                set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
            }
        }
    }

    private fun updatePreview() {
        if (cameraDevice == null || previewRequestBuilder == null) return
        try {
            applyCameraSettings(previewRequestBuilder!!)
            captureSession?.setRepeatingRequest(previewRequestBuilder!!.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update preview", e)
        }
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

    private fun takePhoto() {
        val device = cameraDevice ?: return
        val reader = imageReader ?: return
        val session = captureSession ?: return

        triggerShutterEffect()
        mediaActionSound?.play(android.media.MediaActionSound.SHUTTER_CLICK)
        try {
            val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                applyCameraSettings(this)
                // 照片方向也轉 180 度 (對應預覽的旋轉)
                set(CaptureRequest.JPEG_ORIENTATION, 180)
            }
            session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                    super.onCaptureStarted(session, request, timestamp, frameNumber)
                }
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "Photo saved", Toast.LENGTH_SHORT).show() }
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Capture failed", e)
        }
    }

    private fun saveImage(bytes: ByteArray) {
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            contentResolver.openOutputStream(it)?.use { output ->
                output.write(bytes)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(it, contentValues, null, null)
            }
            lastMediaUri = it
            runOnUiThread {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val thumbnail = contentResolver.loadThumbnail(it, android.util.Size(128, 128), null)
                        viewBinding.thumbnailView.setImageBitmap(thumbnail)
                    } else {
                        viewBinding.thumbnailView.setImageURI(it)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load image thumbnail", e)
                }
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
            val backCameras = mutableListOf<Triple<String, Float, String>>()
            val potentialIds = (0..15).map { it.toString() }
            
            for (id in potentialIds) {
                try {
                    val chars = cameraManager.getCameraCharacteristics(id)
                    val facing = chars.get(CameraCharacteristics.LENS_FACING)
                    if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        val primaryFocalLength = focalLengths?.firstOrNull() ?: 0f
                        backCameras.add(Triple(id, primaryFocalLength, "Log$id"))
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }

            backCameras.sortBy { it.second }

            runOnUiThread {
                viewBinding.zoomLayout.removeAllViews()
            lensTextViews.clear()

            val colorActive = android.graphics.Color.parseColor("#000000") // 黑字
            val colorInactive = android.graphics.Color.parseColor("#FFFFFF") // 白字

            // 依照用戶要求的標籤順序
            val zoomLabels = listOf("0.5x", "1x", "2x", "3.2x", "5x")

            for ((index, camera) in backCameras.withIndex()) {
                val labelText = zoomLabels.getOrNull(index) ?: String.format(Locale.US, "%.1fmm", camera.second)
                
                val tv = TextView(this).apply {
                    text = labelText
                    textSize = 14f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    
                    val isActive = (currentCameraId == camera.first) || (currentCameraId == null && index == 0)
                    setTextColor(if (isActive) colorActive else colorInactive)
                    setBackgroundResource(if (isActive) R.drawable.bg_pill_button_active else R.drawable.bg_pill_button)
                    
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(140, 90).apply { 
                        setMargins(16, 0, 16, 0) 
                    }

                    setOnClickListener {
                        currentCameraId = camera.first
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

            if (currentCameraId == null && backCameras.isNotEmpty()) {
                currentCameraId = backCameras[0].first
            }
            if (viewBinding.viewFinder.isAvailable && currentCameraId != null) {
                openCamera(currentCameraId!!)
            }
            } // end runOnUiThread
        }.start() // end Thread
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
        val colorActive = android.graphics.Color.parseColor("#000000")
        val colorInactive = android.graphics.Color.parseColor("#FFFFFF")
        
        fun updateModeUI() {
            viewBinding.modeAuto.setTextColor(if (currentCameraMode == CameraMode.AUTO) colorActive else colorInactive)
            viewBinding.modeAuto.setBackgroundResource(if (currentCameraMode == CameraMode.AUTO) R.drawable.bg_pill_button_active else 0)
            
            viewBinding.modeManual.setTextColor(if (currentCameraMode == CameraMode.PRO) colorActive else colorInactive)
            viewBinding.modeManual.setBackgroundResource(if (currentCameraMode == CameraMode.PRO) R.drawable.bg_pill_button_active else 0)
            
            viewBinding.modeHdr.setTextColor(if (currentCameraMode == CameraMode.HDR) colorActive else colorInactive)
            viewBinding.modeHdr.setBackgroundResource(if (currentCameraMode == CameraMode.HDR) R.drawable.bg_pill_button_active else 0)
            
            if (currentCameraMode == CameraMode.PRO) {
                viewBinding.parameterScrollView.visibility = android.view.View.VISIBLE
                viewBinding.histogramToggleButton.visibility = android.view.View.VISIBLE
                // We show parameterControlPanel only when a parameter is selected.
                // It is initially hidden until user clicks ISO/S/F etc.
                if (currentManualParam != null) {
                    viewBinding.parameterControlPanel.visibility = android.view.View.VISIBLE
                }
                if (isHistogramEnabled) {
                    viewBinding.histogramView.visibility = android.view.View.VISIBLE
                    startHistogramAnalysis()
                }
            } else {
                viewBinding.parameterControlPanel.visibility = android.view.View.GONE
                viewBinding.parameterScrollView.visibility = android.view.View.GONE
                viewBinding.histogramView.visibility = android.view.View.GONE
                viewBinding.histogramToggleButton.visibility = android.view.View.GONE
                currentManualParam = null
                stopHistogramAnalysis()
            }
            updatePreview()
        }
        
        viewBinding.modeAuto.setOnClickListener { currentCameraMode = CameraMode.AUTO; updateModeUI() }
        viewBinding.modeManual.setOnClickListener { currentCameraMode = CameraMode.PRO; updateModeUI() }
        viewBinding.modeHdr.setOnClickListener { currentCameraMode = CameraMode.HDR; updateModeUI() }
        
        updateModeUI()
    }

    private fun setupManualParameters() {
        val colorActive = android.graphics.Color.parseColor("#FFD700") // Gold for active param
        val colorInactive = android.graphics.Color.parseColor("#FFFFFF")
        
        val params = listOf(
            Pair(ManualParameter.ISO, "ISO"),
            Pair(ManualParameter.SHUTTER, "S"),
            Pair(ManualParameter.APERTURE, "F"),
            Pair(ManualParameter.WB, "WB"),
            Pair(ManualParameter.EV, "EV"),
            Pair(ManualParameter.FOCUS, "AF/MF")
        )
        
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
                
                setOnClickListener {
                    currentManualParam = p.first
                    paramTextViews.forEach { it.setTextColor(colorInactive) }
                    setTextColor(colorActive)
                    updateSliderForParameter()
                }
            }
            viewBinding.parameterLayout.addView(tv)
            paramTextViews.add(tv)
        }
    }

    private fun formatParameterValue(param: ManualParameter, value: Float): String {
        return when (param) {
            ManualParameter.ISO -> "ISO ${value.toInt()}"
            ManualParameter.SHUTTER -> {
                val minNs = exposureRange?.lower ?: 1000000L
                val maxNs = exposureRange?.upper ?: 1000000000L
                val ratio = value / 100.0
                val expNs = Math.exp(Math.log(minNs.toDouble()) + ratio * (Math.log(maxNs.toDouble()) - Math.log(minNs.toDouble())))
                val sec = expNs / 1_000_000_000.0
                if (sec >= 1.0) {
                    String.format(Locale.US, "%.1fs", sec)
                } else {
                    "1/${Math.round(1.0 / sec)}s"
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
                    1 -> "WB DAYLIGHT"
                    2 -> "WB CLOUDY"
                    3 -> "WB FLUORESCENT"
                    4 -> "WB INCANDESCENT"
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
        }
    }

    private fun populatePresets(param: ManualParameter) {
        viewBinding.presetLayout.removeAllViews()
        val presets = mutableListOf<Pair<String, Float>>()
        
        when (param) {
            ManualParameter.ISO -> {
                listOf(100f, 200f, 400f, 800f, 1600f, 3200f).forEach {
                    presets.add(Pair(it.toInt().toString(), it))
                }
            }
            ManualParameter.SHUTTER -> {
                val minNs = exposureRange?.lower ?: 1000000L
                val maxNs = exposureRange?.upper ?: 1000000000L
                listOf(1.0/1000, 1.0/500, 1.0/250, 1.0/125, 1.0/60, 1.0/30, 1.0/15, 1.0/4, 1.0).forEach { sec ->
                    val expNs = (sec * 1_000_000_000).toLong().coerceIn(minNs, maxNs)
                    val ratio = (Math.log(expNs.toDouble()) - Math.log(minNs.toDouble())) / (Math.log(maxNs.toDouble()) - Math.log(minNs.toDouble()))
                    presets.add(Pair(if (sec >= 1.0) "1s" else "1/${Math.round(1.0/sec)}s", (ratio * 100).toFloat()))
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
            else -> {}
        }
        
        for (p in presets) {
            val tv = TextView(this).apply {
                text = p.first
                setTextColor(android.graphics.Color.WHITE)
                textSize = 12f
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_pill_button)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 70).apply { setMargins(8, 0, 8, 0) }
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
        if (currentCameraMode != CameraMode.PRO || currentManualParam == null) {
            viewBinding.parameterControlPanel.visibility = android.view.View.GONE
            return
        }
        viewBinding.parameterControlPanel.visibility = android.view.View.VISIBLE
        viewBinding.parameterSlider.clearOnChangeListeners()
        
        try {
            when (currentManualParam) {
                ManualParameter.ISO -> {
                    viewBinding.parameterSlider.valueFrom = (isoRange?.lower?.toFloat() ?: 100f)
                    viewBinding.parameterSlider.valueTo = (isoRange?.upper?.toFloat() ?: 3200f)
                    viewBinding.parameterSlider.stepSize = 0f
                    viewBinding.parameterSlider.value = currentIso.toFloat().coerceIn(viewBinding.parameterSlider.valueFrom, viewBinding.parameterSlider.valueTo)
                }
                ManualParameter.SHUTTER -> {
                    viewBinding.parameterSlider.valueFrom = 0f
                    viewBinding.parameterSlider.valueTo = 100f
                    viewBinding.parameterSlider.stepSize = 0f
                    val minNs = exposureRange?.lower ?: 1000000L
                    val maxNs = exposureRange?.upper ?: 1000000000L
                    val ratio = (Math.log(currentExposureNs.toDouble()) - Math.log(minNs.toDouble())) / (Math.log(maxNs.toDouble()) - Math.log(minNs.toDouble()))
                    viewBinding.parameterSlider.value = (ratio * 100f).toFloat().coerceIn(0f, 100f)
                }
                ManualParameter.APERTURE -> {
                    if (apertureList != null && apertureList!!.size > 1) {
                        viewBinding.parameterSlider.valueFrom = 0f
                        viewBinding.parameterSlider.valueTo = (apertureList!!.size - 1).toFloat()
                        viewBinding.parameterSlider.stepSize = 1f
                        val idx = apertureList!!.indexOf(currentAperture ?: apertureList!![0])
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
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating slider", e)
        }
        
        populatePresets(currentManualParam!!)
        viewBinding.parameterValueText.text = formatParameterValue(currentManualParam!!, viewBinding.parameterSlider.value)
        
        viewBinding.parameterSlider.addOnChangeListener { _, value, _ ->
            viewBinding.parameterValueText.text = formatParameterValue(currentManualParam!!, value)
            onParameterSliderChanged(value)
        }
    }

    private fun onParameterSliderChanged(value: Float) {
        when (currentManualParam) {
            ManualParameter.ISO -> currentIso = value.toInt()
            ManualParameter.SHUTTER -> {
                val minNs = exposureRange?.lower ?: 1000000L
                val maxNs = exposureRange?.upper ?: 1000000000L
                val ratio = value / 100.0
                val expNs = Math.exp(Math.log(minNs.toDouble()) + ratio * (Math.log(maxNs.toDouble()) - Math.log(minNs.toDouble())))
                currentExposureNs = expNs.toLong()
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
                // Wait, EV compensation requires AE_MODE_ON to work effectively.
                // In PRO mode, we are turning AE_MODE_OFF if ISO/Shutter are set, so EV may be ignored.
                // But we still store it.
            }
            ManualParameter.FOCUS -> manualFocusDistance = value
            else -> {}
        }
        updatePreview()
    }

    private fun startHistogramAnalysis() {
        if (histogramRunnable != null) return
        histogramRunnable = object : java.lang.Runnable {
            override fun run() {
                if (currentCameraMode != CameraMode.PRO) {
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

    companion object {
        private const val TAG = "TelescopeApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }
}
