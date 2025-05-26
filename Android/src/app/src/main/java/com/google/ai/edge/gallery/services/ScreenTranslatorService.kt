package com.google.ai.edge.gallery.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
// import android.view.WindowInsets // Not strictly needed for raw screen size
import androidx.core.app.NotificationCompat
import com.google.ai.edge.gallery.MainActivity
import com.google.ai.edge.gallery.R
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
// Keep existing android.media.Image import (implicitly available)
import android.graphics.Bitmap
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.TaskType // For selectedGemmaModel TaskType
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.gallery.utils.OverlayManager
import android.graphics.Rect // Added for OverlayManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ScreenTranslatorService : Service() {

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    // For screen dimensions
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var screenDensity: Int = 0

    private var llmChatModelHelper: LlmChatModelHelper? = null
    private var selectedGemmaModel: Model? = null
    private var isGemmaModelInitialized = false
    private var overlayManager: OverlayManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())


    companion object {
        const val CHANNEL_ID = "ScreenTranslatorChannel"
        const val ONGOING_NOTIFICATION_ID = 1001 // Example ID
        private const val TAG = "ScreenTranslatorService" // For logging
        const val EXTRA_RESULT_CODE = "mp_result_code"
        const val EXTRA_DATA_INTENT = "mp_data_intent"
        var isProcessingEnabled = true // Default to true when service starts
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate called")
        createNotificationChannel()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        llmChatModelHelper = LlmChatModelHelper()
        overlayManager = OverlayManager(this)

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            // val windowInsets = metrics.windowInsets // Not needed for raw screen size
            // val insets = windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()) // Not needed for raw screen size
            screenWidth = metrics.bounds.width()
            screenHeight = metrics.bounds.height()
            screenDensity = resources.displayMetrics.densityDpi
        } else {
            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION") // Default display and getMetrics are deprecated but needed for older APIs
            windowManager.defaultDisplay.getMetrics(displayMetrics)
            screenWidth = displayMetrics.widthPixels
            screenHeight = displayMetrics.heightPixels
            screenDensity = displayMetrics.densityDpi
        }
        Log.d(TAG, "Screen dimensions: $screenWidth x $screenHeight @ $screenDensity dpi")
    }

    private fun prepareGemmaModel() {
        if (selectedGemmaModel == null) {
            // Create a placeholder Model object for Gemma as specified.
            selectedGemmaModel = Model(
                name = "Gemma-3n-E2B-it-int4", // Hardcoded as per suggestion
                modelId = "google/gemma-3n-E2B-it-litert-preview", // Example
                modelFile = "gemma-3n-E2B-it-int4.task", // Example
                description = "Placeholder Gemma model for ScreenTranslatorService",
                sizeInBytes = 0, // Placeholder
                version = "internal", // Placeholder
                llmSupportImage = true, // Important for ask-image style tasks
                taskTypes = mutableListOf(TaskType.LLM_ASK_IMAGE)
            )
            Log.d(TAG, "Created placeholder selectedGemmaModel: ${selectedGemmaModel?.name}")
        }

        if (selectedGemmaModel != null && !isGemmaModelInitialized) {
            Log.d(TAG, "Initializing Gemma model: ${selectedGemmaModel!!.name}")
            llmChatModelHelper?.initialize(this, selectedGemmaModel!!) { error ->
                if (error.isEmpty()) {
                    isGemmaModelInitialized = true
                    Log.d(TAG, "Gemma model initialized successfully.")
                } else {
                    isGemmaModelInitialized = false
                    Log.e(TAG, "Gemma model initialization failed: $error")
                    // Handle error, maybe stop service or retry
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called")

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            pendingIntentFlags
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Translator Active")
            .setContentText("Tap to configure or stop the service.")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Replace with a suitable icon
            .setContentIntent(pendingIntent)
            .setOngoing(true) // Makes the notification non-dismissable
            .build()

        startForeground(ONGOING_NOTIFICATION_ID, notification)
        Log.d(TAG, "startForeground called")

        if (intent?.hasExtra(EXTRA_RESULT_CODE) == true && intent.hasExtra(EXTRA_DATA_INTENT)) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val data = intent.getParcelableExtra<Intent>(EXTRA_DATA_INTENT) // Use getParcelableExtra with type for API 33+
            if (resultCode == Activity.RESULT_OK && data != null) {
                mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
                if (mediaProjection != null) {
                    Log.i(TAG, "MediaProjection obtained.")
                    prepareGemmaModel() // Call prepareGemmaModel here
                    setupImageReaderAndVirtualDisplay()
                    // Register a callback to stop the service if projection stops
                    mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            Log.w(TAG, "MediaProjection stopped. Stopping service.")
                            stopSelf() // Stop the service if projection is stopped (e.g., by system)
                        }
                    }, null) // Handler can be null
                } else {
                    Log.e(TAG, "Failed to obtain MediaProjection.")
                    stopSelf() // Stop if we can't get projection
                }
            } else {
                Log.e(TAG, "MediaProjection permission not granted or data intent missing.")
                stopSelf() // Stop if permission not granted
            }
        } else {
            // If service is started without projection data (e.g. first start to show notification, or restart)
            // This path should ideally not try to start projection, or be handled carefully.
            // For now, if it's not the intent from ActivityResult, it won't have the extras.
            Log.d(TAG, "Service started without MediaProjection data.")
        }

        // If the service is killed, it will be automatically restarted.
        return START_STICKY
    }
    
    private fun setupImageReaderAndVirtualDisplay() {
        Log.d(TAG, "Setting up ImageReader and VirtualDisplay.")
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            if (!isProcessingEnabled) {
                val imageToClose = reader?.acquireLatestImage()
                imageToClose?.close() // Still need to acquire and close to keep queue empty
                return@setOnImageAvailableListener
            }

            val image = reader?.acquireLatestImage() // android.media.Image from ImageReader
            if (image == null) {
                return@setOnImageAvailableListener
            }

            // Convert android.media.Image to Bitmap (RGBA_8888 direct copy)
            val planes = image.planes
            val buffer = planes[0].buffer.asReadOnlyBuffer()
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val originalBitmap = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888)
            originalBitmap.copyPixelsFromBuffer(buffer)
            
            val finalBitmapToProcess: Bitmap
            if (rowPadding > 0) {
                // Crop the bitmap if there was padding
                finalBitmapToProcess = Bitmap.createBitmap(originalBitmap, 0, 0, screenWidth, screenHeight)
                if (finalBitmapToProcess != originalBitmap) originalBitmap.recycle() // Recycle the larger bitmap if a new one is created
            } else {
                finalBitmapToProcess = originalBitmap
            }

            val inputImage = InputImage.fromMediaImage(image, 0) // Use original image for ML Kit
            image.close() // Close ImageReader's image

            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    Log.d(TAG, "ML Kit Text Recognition Success. Full text: ${visionText.text.substring(0, minOf(visionText.text.length, 100))}...")
                    
                    serviceScope.launch { // Explicitly move to background
                        mainHandler.post { overlayManager?.removeAllOverlays() } // Keep UI on main

                        // Convert android.media.Image to Bitmap (RGBA_8888 direct copy)
                        // This logic was already here, just ensuring it's inside the background scope
                        val planesBG = image.planes // Re-access image or pass it to this scope if needed
                        val bufferBG = planesBG[0].buffer.asReadOnlyBuffer()
                        val pixelStrideBG = planesBG[0].pixelStride
                        val rowStrideBG = planesBG[0].rowStride
                        val rowPaddingBG = rowStrideBG - pixelStrideBG * screenWidth

                        val originalBitmapBG = Bitmap.createBitmap(screenWidth + rowPaddingBG / pixelStrideBG, screenHeight, Bitmap.Config.ARGB_8888)
                        originalBitmapBG.copyPixelsFromBuffer(bufferBG)
                        
                        val finalBitmapToProcessBG: Bitmap
                        if (rowPaddingBG > 0) {
                            finalBitmapToProcessBG = Bitmap.createBitmap(originalBitmapBG, 0, 0, screenWidth, screenHeight)
                            if (finalBitmapToProcessBG != originalBitmapBG) originalBitmapBG.recycle()
                        } else {
                            finalBitmapToProcessBG = originalBitmapBG
                        }
                        // Note: 'image' (android.media.Image) was closed outside this scope, which is correct.

                        for (block in visionText.textBlocks) {
                            Log.d(TAG, "Block text: '${block.text}', BoundingBox: ${block.boundingBox}")
                            
                            if (!isGemmaModelInitialized || selectedGemmaModel == null) {
                                Log.w(TAG, "Gemma model not ready for translation for block: '${block.text}'. Skipping.")
                                continue
                            }

                            val boundingBox = block.boundingBox
                            if (boundingBox != null) {
                                val left = maxOf(0, boundingBox.left)
                                val top = maxOf(0, boundingBox.top)
                                val width = minOf(boundingBox.width(), finalBitmapToProcessBG.width - left)
                                val height = minOf(boundingBox.height(), finalBitmapToProcessBG.height - top)

                                if (width <= 0 || height <= 0) {
                                    Log.e(TAG, "Adjusted bounding box has zero or negative dimensions. Skipping crop for block: '${block.text}'. Original box: ${boundingBox}")
                                    continue
                                }
                                
                                val croppedBitmap = try {
                                   Bitmap.createBitmap(finalBitmapToProcessBG, left, top, width, height)
                                } catch (e: IllegalArgumentException) {
                                   Log.e(TAG, "Failed to crop bitmap for block '${block.text}': ${e.message}. Original box: ${boundingBox}, Adjusted: $left, $top, $width, $height. Bitmap size: ${finalBitmapToProcessBG.width}x${finalBitmapToProcessBG.height}")
                                   null
                                }

                                if (croppedBitmap != null) {
                                    Log.d(TAG, "Cropped bitmap for text: '${block.text}'. Size: ${croppedBitmap.width}x${croppedBitmap.height}")
                                    val prompt = "Translate the text in this image to Portuguese: "

                                    llmChatModelHelper?.runInference(
                                        model = selectedGemmaModel!!,
                                        input = prompt, 
                                        image = croppedBitmap,
                                        resultListener = { partialResult, done ->
                                            if (done) {
                                                Log.i(TAG, "Translation for '${block.text}': $partialResult")
                                                mainHandler.post {
                                                    block.boundingBox?.let { bounds ->
                                                        overlayManager?.addOverlay(bounds, partialResult)
                                                    }
                                                }
                                                // if (!croppedBitmap.isRecycled) croppedBitmap.recycle() // Be cautious
                                            }
                                        },
                                        cleanUpListener = {
                                            Log.d(TAG, "Gemma inference cleanup for a block.")
                                             if (!croppedBitmap.isRecycled) { // Be cautious
                                                // croppedBitmap.recycle()
                                            }
                                        }
                                    )
                                }
                            }
                        }
                        if (!finalBitmapToProcessBG.isRecycled) {
                            // finalBitmapToProcessBG.recycle() // Be cautious
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "ML Kit Text Recognition Failed: ", e)
                     // Accessing finalBitmapToProcessBG here might be an issue if defined only in success listener's scope.
                     // Ensure cleanup or access is handled correctly based on variable scope.
                }
        }, null)
        virtualDisplay = mediaProjection?.createVirtualDisplay("ScreenTranslator", screenWidth, screenHeight, screenDensity, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, null)
        Log.d(TAG, "VirtualDisplay created.")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Destroying ScreenTranslatorService.")
        serviceScope.cancel() // Cancel the coroutine scope
        mainHandler.post { overlayManager?.removeAllOverlays() }
        if (selectedGemmaModel != null && isGemmaModelInitialized) {
            llmChatModelHelper?.cleanUp(selectedGemmaModel!!)
            Log.d(TAG, "Gemma model cleaned up.")
        }
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
    }

    override fun onBind(intent: Intent?): IBinder? {
        // This service does not provide binding, so return null
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Screen Translator Service Channel"
            val descriptionText = "Channel for Screen Translator foreground service notifications"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            // Register the channel with the system
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created")
        }
    }
}
