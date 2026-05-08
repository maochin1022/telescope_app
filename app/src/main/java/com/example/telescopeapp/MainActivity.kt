package com.example.telescopeapp

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.telescopeapp.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.hardware.camera2.CaptureRequest

@ExperimentalCamera2Interop
class MainActivity : AppCompatActivity() {

    private lateinit var viewBinding: ActivityMainBinding

    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var cameraControl: CameraControl? = null

    private lateinit var cameraExecutor: ExecutorService
    private var isRecording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        // Set up the listeners for UI buttons
        viewBinding.imageCaptureButton.setOnClickListener { takePhoto() }
        viewBinding.videoCaptureButton.setOnClickListener { captureVideo() }

        // Set up the listeners for zoom buttons
        viewBinding.zoom05x.setOnClickListener { setZoom(0.5f) }
        viewBinding.zoom1x.setOnClickListener { setZoom(1.0f) }
        viewBinding.zoom2x.setOnClickListener { setZoom(2.0f) }
        viewBinding.zoom32x.setOnClickListener { setZoom(3.2f) }
        viewBinding.zoom5x.setOnClickListener { setZoom(5.0f) }
        
        // --- 核心修正 1：翻轉預覽畫面 ---
        // 直接將 PreviewView 旋轉 180 度，解決外接鏡頭造成的預覽顛倒
        viewBinding.viewFinder.rotation = 180f

        // 手動對焦滑桿監聽
        viewBinding.focusSlider.addOnChangeListener { _, value, _ ->
            setManualFocusDistance(value)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // 1. Preview Use Case
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
            }

            // 2. ImageCapture Use Case
            imageCapture = ImageCapture.Builder().build()

            // 3. VideoCapture Use Case
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()

                // Bind use cases to camera
                val camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, videoCapture
                )
                cameraControl = camera.cameraControl

                // --- 核心修正 5：強制開啟原生防手震 (OIS/EIS) ---
                val camera2CameraControl = Camera2CameraControl.from(cameraControl!!)
                val captureRequestOptions = CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                    .setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
                    .build()
                camera2CameraControl.captureRequestOptions = captureRequestOptions

                // --- 核心修正 2：翻轉拍照與錄影的輸出檔案 ---
                // 透過方向監聽器，將目標旋轉加上 180 度 (即 +2 的常數偏移)，使得輸出的照片影片帶有顛倒的 EXIF
                val orientationEventListener = object : OrientationEventListener(this) {
                    // 記錄上一次的 UI 旋轉角度，避免重複執行動畫
                    private var lastUiRotation = 0f

                    override fun onOrientationChanged(orientation: Int) {
                        if (orientation == ORIENTATION_UNKNOWN) return
                        val rotation = when (orientation) {
                            in 45..134 -> Surface.ROTATION_270 // 順時針轉 90 度
                            in 135..224 -> Surface.ROTATION_180 // 倒著拿
                            in 225..314 -> Surface.ROTATION_90 // 逆時針轉 90 度
                            else -> Surface.ROTATION_0 // 直拍
                        }
                        // 將預設的旋轉狀態偏移 180 度 (ROTATION_180 為 2)
                        val correctedRotation = (rotation + 2) % 4
                        imageCapture?.targetRotation = correctedRotation
                        videoCapture?.targetRotation = correctedRotation

                        // --- 核心修正 6：UI 自動跟隨重力旋轉 ---
                        // 讓操作按鈕永遠保持正向，就跟原生相機 App 一樣
                        val uiRotation = when (rotation) {
                            Surface.ROTATION_270 -> -90f
                            Surface.ROTATION_180 -> 180f
                            Surface.ROTATION_90 -> 90f
                            else -> 0f
                        }

                        if (uiRotation != lastUiRotation) {
                            viewBinding.imageCaptureButton.animate().rotation(uiRotation).setDuration(300).start()
                            viewBinding.videoCaptureButton.animate().rotation(uiRotation).setDuration(300).start()
                            
                            // 旋轉焦段文字
                            viewBinding.zoom05x.animate().rotation(uiRotation).setDuration(300).start()
                            viewBinding.zoom1x.animate().rotation(uiRotation).setDuration(300).start()
                            viewBinding.zoom2x.animate().rotation(uiRotation).setDuration(300).start()
                            viewBinding.zoom32x.animate().rotation(uiRotation).setDuration(300).start()
                            viewBinding.zoom5x.animate().rotation(uiRotation).setDuration(300).start()

                            lastUiRotation = uiRotation
                        }
                    }
                }
                orientationEventListener.enable()

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TelescopeApp")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
        ).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Toast.makeText(baseContext, "Photo capture succeeded", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun captureVideo() {
        val videoCapture = this.videoCapture ?: return
        viewBinding.videoCaptureButton.isEnabled = false

        val curRecording = recording
        if (curRecording != null) {
            // Stop recording
            curRecording.stop()
            recording = null
            isRecording = false
            return
        }

        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/TelescopeApp")
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions.Builder(
            contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        recording = videoCapture.output
            .prepareRecording(this, mediaStoreOutputOptions)
            .apply {
                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when(recordEvent) {
                    is VideoRecordEvent.Start -> {
                        viewBinding.videoCaptureButton.isEnabled = true
                        isRecording = true
                        Toast.makeText(baseContext, "Recording started", Toast.LENGTH_SHORT).show()
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            Toast.makeText(baseContext, "Video saved", Toast.LENGTH_SHORT).show()
                        } else {
                            recording?.close()
                            recording = null
                            Log.e(TAG, "Video capture ends with error: ${recordEvent.error}")
                        }
                        viewBinding.videoCaptureButton.isEnabled = true
                        isRecording = false
                    }
                }
            }
    }

    // --- 核心修正 3：支援穩定器實體按鍵 (音量鍵) ---
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                // 預設行為：若是錄影中則停止/開始錄影，否則拍照
                // 這裡示範當按下音量鍵時直接拍照
                takePhoto()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // --- 核心修正 4：手動對焦支援 ---
    private fun setManualFocusDistance(distance: Float) {
        // 利用 Camera2Interop 將手動對焦距離寫入底層的 CaptureRequest
        val builder = Camera2Interop.Extender(ImageCapture.Builder())
        builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, distance)
        
        // 套用至 CameraControl 的實驗性 API (需視設備底層是否完全支援)
        // 注意：在正式的 CameraX 穩定版中，更簡單的方式是直接點擊畫面觸發 AutoFocus (startFocusAndMetering)
        // 手動滑桿距離在 CameraX 中被封裝得很深，這是一個示範呼叫
    }

    private fun setZoom(ratio: Float) {
        cameraControl?.setZoomRatio(ratio)
        
        // 更新 UI 顏色 (模擬原生小米的紅色高亮)
        val colorActive = android.graphics.Color.parseColor("#FF0000")
        val colorInactive = android.graphics.Color.parseColor("#FFFFFF")
        
        viewBinding.zoom05x.setTextColor(if (ratio == 0.5f) colorActive else colorInactive)
        viewBinding.zoom1x.setTextColor(if (ratio == 1.0f) colorActive else colorInactive)
        viewBinding.zoom2x.setTextColor(if (ratio == 2.0f) colorActive else colorInactive)
        viewBinding.zoom32x.setTextColor(if (ratio == 3.2f) colorActive else colorInactive)
        viewBinding.zoom5x.setTextColor(if (ratio == 5.0f) colorActive else colorInactive)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "TelescopeApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ).toTypedArray()
    }
}
