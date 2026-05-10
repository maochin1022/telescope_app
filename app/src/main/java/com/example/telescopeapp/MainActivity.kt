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
    private var bestPreviewSize: android.util.Size? = null
    private var bestJpegSize: android.util.Size? = null
    private var supportedOisModes: IntArray? = null
    private var supportedAfModes: IntArray? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val lensTextViews = mutableListOf<TextView>()
    private var currentCameraId: String? = null
    private var manualFocusDistance: Float = 0f

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

        viewBinding.focusSlider.addOnChangeListener { _, value, _ ->
            manualFocusDistance = value
            updatePreview()
        }

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

            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (map != null) {
                val jpegSizes = map.getOutputSizes(ImageFormat.JPEG)
                val previewSizes = map.getOutputSizes(SurfaceTexture::class.java)
                
                if (jpegSizes != null && previewSizes != null) {
                    // 優先嘗試找標準 1080p (16:9)，這在大部分鏡頭上最穩定
                    val standard1080p = android.util.Size(1920, 1080)
                    val has1080pPreview = previewSizes.contains(standard1080p)
                    val has1080pJpeg = jpegSizes.contains(standard1080p)

                    if (has1080pPreview && has1080pJpeg) {
                        bestPreviewSize = standard1080p
                        bestJpegSize = standard1080p
                    } else {
                        // 如果沒有標準 1080p，則尋找比例相近且預覽不超過 1080p 的最大解析度
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
                    bestJpegSize = android.util.Size(1280, 720)
                    bestPreviewSize = android.util.Size(1280, 720)
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

    private fun updatePreview() {
        if (cameraDevice == null) return
        try {
            previewRequestBuilder?.apply {
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                
                // --- 防手震設定 ---
                // 關閉數位防手震 (EIS)，避免與外接鏡頭衝突
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                
                // 動態判斷是否支援光學防手震 (OIS)
                if (supportedOisModes?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) == true) {
                    set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
                } else {
                    set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                }

                // --- 對焦設定 ---
                if (manualFocusDistance > 0) {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocusDistance)
                } else {
                    if (supportedAfModes?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) == true) {
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    } else if (supportedAfModes?.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) == true) {
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                    } else {
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    }
                }
            }
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
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
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
            val ids = try { cameraManager.cameraIdList } catch (e: Exception) { emptyArray<String>() }
            
            for (id in ids) {
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

    companion object {
        private const val TAG = "TelescopeApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }
}
