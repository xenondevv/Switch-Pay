package com.example.offline_payment_app.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Foreground Service overlay on top of InCallUI.
 * Shows live progress + UPI PIN input keypad.
 */
class PaymentOverlayService : Service() {

    companion object {
        private var instance: PaymentOverlayService? = null
        private val handler = Handler(Looper.getMainLooper())
        private var pinCallback: ((String) -> Unit)? = null

        fun addStep(status: String, message: String, isFinal: Boolean) {
            handler.post { instance?.addStepInternal(status, message, isFinal) }
        }

        fun close() {
            handler.post { instance?.stopSelf() }
        }

        fun showPinInput(callback: (String) -> Unit) {
            pinCallback = callback
            handler.post { instance?.showPinInputInternal() }
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var stepsContainer: LinearLayout? = null
    private var headerText: TextView? = null
    private var scrollView: ScrollView? = null
    private var pinSection: LinearLayout? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d("OverlayService", "Creating overlay")
        startForegroundNotification()
        createOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        pinCallback = null
        try { windowManager?.removeView(overlayView) } catch (_: Exception) {}
        Log.d("OverlayService", "Overlay destroyed")
    }

    private fun startForegroundNotification() {
        val channelId = "payment_overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Payment Progress",
                NotificationManager.IMPORTANCE_LOW).apply {
                description = "IVR payment progress"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, channelId)
        else @Suppress("DEPRECATION") Notification.Builder(this)).apply {
            setContentTitle("Processing payment...")
            setContentText("IVR in progress")
            setSmallIcon(android.R.drawable.ic_menu_call)
            setOngoing(true)
        }.build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1001, notification)
        }
    }

    private fun createOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        // Use FLAG_NOT_TOUCH_MODAL so we can interact with PIN input
        // but calls still work underneath
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(60), dp(24), dp(24))
            setBackgroundColor(Color.parseColor("#F50D1117"))
        }

        // Header
        headerText = TextView(this).apply {
            text = "📞 IVR Payment"
            setTextColor(Color.parseColor("#66BB6A"))
            textSize = 22f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(headerText)

        val subtitle = TextView(this).apply {
            text = "Call running in background • Details auto-entered"
            setTextColor(Color.parseColor("#757575"))
            textSize = 12f
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(subtitle)

        // Steps
        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        stepsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollView!!.addView(stepsContainer)
        root.addView(scrollView)

        // PIN section (hidden initially)
        pinSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(pinSection)

        // Close button
        val closeBtn = TextView(this).apply {
            text = "✕  Close"
            setTextColor(Color.parseColor("#EF5350"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(14), dp(14), dp(14))
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#1C1C2E"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor("#EF5350"))
            }
            background = bg
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(12)
            layoutParams = lp
            setOnClickListener { stopSelf() }
        }
        root.addView(closeBtn)

        overlayView = root

        try {
            windowManager?.addView(overlayView, params)
            Log.d("OverlayService", "Overlay added")
        } catch (e: Exception) {
            Log.e("OverlayService", "Overlay failed: ${e.message}")
            stopSelf()
        }
    }

    private fun addStepInternal(status: String, message: String, isFinal: Boolean) {
        Log.d("OverlayService", "Step: $status")

        val dotColor = when {
            status.contains("error") || status.contains("fail") -> "#EF5350"
            status.contains("success") -> "#66BB6A"
            status.contains("pin") || status.contains("callback") -> "#FFA726"
            else -> "#42A5F5"
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
            gravity = Gravity.TOP
        }

        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(dotColor))
            }
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                topMargin = dp(5); rightMargin = dp(10)
            }
        }
        row.addView(dot)

        val card = TextView(this).apply {
            text = message
            setTextColor(if (isFinal) Color.WHITE else Color.parseColor("#BDBDBD"))
            textSize = 12f
            if (isFinal) typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A1F2B"))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor(if (isFinal) dotColor else "#30363D"))
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(card)
        stepsContainer?.addView(row)
        scrollView?.post { scrollView?.fullScroll(ScrollView.FOCUS_DOWN) }

        if (isFinal) {
            headerText?.text = when {
                status.contains("success") -> "✅ Payment Complete"
                status.contains("error") -> "❌ Failed"
                status.contains("callback") -> "⏳ Callback"
                status.contains("pin") -> "🔒 Enter PIN"
                else -> "✅ Done"
            }
            headerText?.setTextColor(Color.parseColor(when {
                status.contains("error") -> "#EF5350"
                status.contains("callback") || status.contains("pin") -> "#FFA726"
                else -> "#66BB6A"
            }))
        }
    }

    private fun showPinInputInternal() {
        pinSection?.visibility = View.VISIBLE
        pinSection?.removeAllViews()

        // Label
        val label = TextView(this).apply {
            text = "🔒 Enter UPI PIN"
            setTextColor(Color.parseColor("#FFA726"))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        }
        pinSection?.addView(label)

        // PIN display
        val pinDisplay = TextView(this).apply {
            text = ""
            setTextColor(Color.WHITE)
            textSize = 28f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A1F2B"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(2), Color.parseColor("#FFA726"))
            }
            setPadding(dp(16), dp(12), dp(16), dp(12))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(8)
            layoutParams = lp
        }
        pinSection?.addView(pinDisplay)

        var currentPin = ""

        // Number pad (3x4 grid)
        val rows = arrayOf(
            arrayOf("1", "2", "3"),
            arrayOf("4", "5", "6"),
            arrayOf("7", "8", "9"),
            arrayOf("⌫", "0", "✓")
        )

        for (row in rows) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(4)
                layoutParams = lp
            }

            for (key in row) {
                val btn = TextView(this).apply {
                    text = key
                    setTextColor(when (key) {
                        "✓" -> Color.parseColor("#66BB6A")
                        "⌫" -> Color.parseColor("#EF5350")
                        else -> Color.WHITE
                    })
                    textSize = 20f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor(when (key) {
                            "✓" -> "#1B3A1B"
                            "⌫" -> "#3A1B1B"
                            else -> "#1A1F2B"
                        }))
                        cornerRadius = dp(10).toFloat()
                        setStroke(dp(1), Color.parseColor("#30363D"))
                    }
                    val lp = LinearLayout.LayoutParams(0, dp(52), 1f)
                    lp.setMargins(dp(3), 0, dp(3), 0)
                    layoutParams = lp

                    setOnClickListener {
                        when (key) {
                            "⌫" -> {
                                if (currentPin.isNotEmpty()) {
                                    currentPin = currentPin.dropLast(1)
                                    pinDisplay.text = "●".repeat(currentPin.length)
                                }
                            }
                            "✓" -> {
                                if (currentPin.length >= 4) {
                                    pinSection?.visibility = View.GONE
                                    pinCallback?.invoke(currentPin)
                                    pinCallback = null
                                }
                            }
                            else -> {
                                if (currentPin.length < 6) {
                                    currentPin += key
                                    pinDisplay.text = "●".repeat(currentPin.length)
                                }
                            }
                        }
                    }
                }
                rowLayout.addView(btn)
            }
            pinSection?.addView(rowLayout)
        }

        // Update overlay flags to allow touch input
        try {
            val params = overlayView?.layoutParams as? WindowManager.LayoutParams
            if (params != null) {
                // Remove FLAG_NOT_FOCUSABLE to allow keyboard/touch input
                params.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                windowManager?.updateViewLayout(overlayView, params)
            }
        } catch (e: Exception) {
            Log.e("OverlayService", "Failed to update overlay flags: ${e.message}")
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
    }
}
