package com.driver.profitcalculator

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import java.nio.ByteBuffer

/**
 * ScreenCaptureService V2 - Optimized for sub-500ms latency
 * Uses FareAnalyzer for zone-based extraction
 */
class ScreenCaptureServiceV2 : Service() {

    companion object {
        const val TAG = "SilentEyeV2"
        const val FPS = 2
        const val FRAME_DELAY_MS = 1000L / FPS
        const val NOTIFICATION_ID = 1338
        const val CHANNEL_ID = "silent_eye_v2"
        
        // Capture dimensions - optimized for speed
        const val CAPTURE_WIDTH = 720
        const val CAPTURE_HEIGHT = 1280
        const val CAPTURE_DPI = 320
        
        // Performance tuning
        const val MAX_PROCESSING_TIME_MS = 400 // Must complete within 400ms
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var overlayView: View? = null
    private var overlayWindowManager: WindowManager? = null
    
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val fareAnalyzer = FareAnalyzer()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var captureJob: Job? = null
    
    // Reusable bitmap to reduce GC pressure
    private var reusableBitmap: Bitmap? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initOverlay()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>("data")
        
        if (resultCode == Activity.RESULT_OK && data != null) {
            startForeground(NOTIFICATION_ID, createNotification())
            initMediaProjection(resultCode, data)
            startCaptureLoop()
        } else {
            Log.e(TAG, "Invalid result code or data")
            stopSelf()
        }
        
        return START_STICKY
    }

    private fun initMediaProjection(resultCode: Int, data: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        
        imageReader = ImageReader.newInstance(
            CAPTURE_WIDTH, CAPTURE_HEIGHT, 
            PixelFormat.RGBA_8888, 2
        )
        
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "SilentCaptureV2",
            CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_DPI,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
        
        Log.d(TAG, "MediaProjection ready - ${CAPTURE_WIDTH}x${CAPTURE_HEIGHT} @ ${FPS}FPS")
    }

    private fun startCaptureLoop() {
        captureJob = serviceScope.launch {
            while (isActive) {
                val startTime = System.currentTimeMillis()
                
                try {
                    processFrame()
                } catch (e: Exception) {
                    Log.e(TAG, "Frame processing error: ${e.message}")
                }
                
                val processingTime = System.currentTimeMillis() - startTime
                val delay = (FRAME_DELAY_MS - processingTime).coerceAtLeast(0)
                
                if (processingTime > MAX_PROCESSING_TIME_MS) {
                    Log.w(TAG, "Slow frame: ${processingTime}ms")
                }
                
                delay(delay)
            }
        }
    }

    private suspend fun processFrame() = withContext(Dispatchers.IO) {
        val image = imageReader?.acquireLatestImage() ?: return@withContext
        
        try {
            val bitmap = image.toBitmapOptimized()
            if (bitmap != null) {
                val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
                
                val analysis = fareAnalyzer.analyze(
                    result.textBlocks, 
                    CAPTURE_WIDTH, 
                    CAPTURE_HEIGHT
                )
                
                withContext(Dispatchers.Main) {
                    updateOverlay(analysis)
                }
            }
        } finally {
            image.close()
        }
    }

    /**
     * Optimized bitmap conversion - reuses buffer when possible
     */
    private fun Image.toBitmapOptimized(): Bitmap? {
        val planes = this.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * CAPTURE_WIDTH
        
        val width = CAPTURE_WIDTH + rowPadding / pixelStride
        val height = CAPTURE_HEIGHT
        
        // Reuse bitmap if possible
        if (reusableBitmap == null || reusableBitmap?.width != width || reusableBitmap?.height != height) {
            reusableBitmap?.recycle()
            reusableBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        
        reusableBitmap?.copyPixelsFromBuffer(buffer.rewind() as ByteBuffer)
        return reusableBitmap
    }

    /**
     * Update ghost overlay with analysis results
     */
    private fun updateOverlay(analysis: FareAnalyzer.AnalysisResult) {
        if (!analysis.hasAnyResult()) {
            hideOverlay()
            return
        }
        
        val displayText = analysis.formatDisplay()
        
        if (overlayView == null) {
            createOverlayView()
        }
        
        val textView = overlayView?.findViewById<TextView>(R.id.profitText)
        textView?.text = displayText
        
        // Dynamic background based on profitability
        val hasProfitable = analysis.rapido?.isProfitable == true || 
                           analysis.uber?.isProfitable == true
        val hasBlocked = analysis.uber?.isBlocked == true
        
        overlayView?.background = when {
            hasBlocked -> getDrawable(R.drawable.bg_blocked)
            hasProfitable -> getDrawable(R.drawable.bg_profit_green)
            else -> getDrawable(R.drawable.bg_loss_red)
        }
        
        overlayView?.visibility = View.VISIBLE
        
        // Auto-hide after 5 seconds if no new data
        overlayView?.removeCallbacks(hideRunnable)
        overlayView?.postDelayed(hideRunnable, 5000)
    }

    private val hideRunnable = Runnable { hideOverlay() }

    private fun hideOverlay() {
        overlayView?.visibility = View.GONE
    }

    /**
     * Ghost Hologram - FLAG_NOT_TOUCHABLE creates click-through overlay
     */
    private fun initOverlay() {
        overlayWindowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    private fun createOverlayView() {
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        overlayView = inflater.inflate(R.layout.overlay_profit_banner, null)
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
            else 
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            // CRITICAL FLAGS for ghost overlay:
            // FLAG_NOT_TOUCHABLE - taps pass through to underlying app
            // FLAG_NOT_FOCUSABLE - no input focus
            // FLAG_LAYOUT_IN_SCREEN - position relative to screen
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        
        // Position at top-center, below status bar
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.y = 180
        
        overlayWindowManager?.addView(overlayView, params)
        Log.d(TAG, "Ghost overlay created - FLAG_NOT_TOUCHABLE active")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Silent Eye V2",
                NotificationManager.IMPORTANCE_MIN // Lowest importance - no sound/vibration
            ).apply {
                description = "Background fare analysis"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Silent Eye Active")
            .setContentText("Analyzing fares...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        
        captureJob?.cancel()
        serviceScope.cancel()
        
        overlayView?.removeCallbacks(hideRunnable)
        overlayView?.let {
            overlayWindowManager?.removeView(it)
        }
        
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        reusableBitmap?.recycle()
        
        Log.d(TAG, "Service destroyed - resources cleaned")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
