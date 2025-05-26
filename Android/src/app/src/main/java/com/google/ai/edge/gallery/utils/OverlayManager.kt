package com.google.ai.edge.gallery.utils

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.google.ai.edge.gallery.R // Assuming R is correctly generated for the new layout
import java.util.ArrayList

class OverlayManager(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val overlayViews = ArrayList<View>()

    fun addOverlay(originalBounds: Rect, translatedText: String) {
        val inflater = LayoutInflater.from(context)
        val overlayView = inflater.inflate(R.layout.translation_balloon, null)
        val textView = overlayView.findViewById<TextView>(R.id.translated_text_view)
        textView.text = translatedText

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, // Allows positioning outside screen edges if needed, though typically not for this.
            PixelFormat.TRANSLUCENT
        )

        // Position below the original text block for now
        params.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        params.x = originalBounds.left
        params.y = originalBounds.bottom // Position balloon below the original text

        windowManager.addView(overlayView, params)
        overlayViews.add(overlayView)
    }

    fun removeAllOverlays() {
        for (view in overlayViews) {
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                // Ignore if view already removed or other issues
            }
        }
        overlayViews.clear()
    }
}
