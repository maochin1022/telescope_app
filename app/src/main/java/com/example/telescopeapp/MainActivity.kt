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

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val lensTextViews = mutableListOf<TextView>()
    private var currentCameraId: String? = null
    private var manualFocusDistance: Float = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        if (allPermissionsGranted()) {
            setupDynamicLenses()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        viewBinding.imageCaptureButton.setOnClickListener { takePhoto() }
        viewBinding.videoCaptureButton.setOnClickListener { 
            Toast.makeText(this, "Video capture not fully implemented in raw Camera2 yet.", Toast.LENGTH_SHORT).show() 
        }

        viewBinding.focusSlider.addOnChangeListener { _, value, _ ->
            manualFocusDistance = value
            updatePreview()
        }
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

        closeCamera()
        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCameraPreviewSession()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Cannot open camera", e)
        }
    }

    private fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
    }

    private fun createCameraPreviewSession() {
        try {
            val texture = viewBinding.viewFinder.surfaceTexture ?: return
            texture.setDefaultBufferSize(1920, 1080)
            val surface = Surface(texture)

            imageReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 2).apply {
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
                
                // --- 強制開啟防手震 ---
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)

                // 手動對焦
                if (manualFocusDistance > 0) {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocusDistance)
                } else {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }
            }
            captureSession?.setRepeatingRequest(previewRequestBuilder!!.build(), null, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to update preview", e)
        }
    }

    private fun takePhoto() {
        if (cameraDevice == null) return
        try {
            val captureBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(imageReader!!.surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                // 照片方向也轉 180 度 (對應預覽的旋轉)
                set(CaptureRequest.JPEG_ORIENTATION, 180)
            }
            captureSession?.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "Photo saved", Toast.LENGTH_SHORT).show() }
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Capture failed", e)
        }
    }

    private fun saveImage(bytes: ByteArray) {
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TelescopeApp")
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            contentResolver.openOutputStream(it)?.use { output ->
                output.write(bytes)
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

            val colorActive = android.graphics.Color.parseColor("#FF0000")
            val colorInactive = android.graphics.Color.parseColor("#FFFFFF")

            for ((index, camera) in backCameras.withIndex()) {
                val tv = TextView(this).apply {
                    text = String.format(Locale.US, "%s\n%.1fmm", camera.third, camera.second)
                    textSize = 12f
                    val isActive = (currentCameraId == camera.first) || (currentCameraId == null && index == 0)
                    setTextColor(if (isActive) colorActive else colorInactive)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.MATCH_PARENT
                    ).apply { setMargins(24, 0, 24, 0) }

                    setOnClickListener {
                        currentCameraId = camera.first
                        lensTextViews.forEach { it.setTextColor(colorInactive) }
                        this.setTextColor(colorActive)
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
        }
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
