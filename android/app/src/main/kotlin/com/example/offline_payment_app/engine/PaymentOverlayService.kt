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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Full-screen overlay covering USSD popups.
 * Shows: progress steps → PIN keypad → success/failure screen.
 */
class PaymentOverlayService : Service() {

    companion object {
        private var instance: PaymentOverlayService? = null
        private val handler = Handler(Looper.getMainLooper())

        fun addStep(status: String, message: String, isFinal: Boolean) {
            handler.post { instance?.addStepInternal(status, message, isFinal) }
        }

        fun close() {
            handler.post { instance?.stopSelf() }
        }

        fun showPinInput() {
            handler.post { instance?.showPinInputInternal() }
        }

        fun showSuccess(message: String) {
            handler.post { instance?.showSuccessInternal(message) }
        }

        fun showFailure(message: String) {
            handler.post { instance?.showFailureInternal(message) }
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var stepsContainer: LinearLayout? = null
    private var headerText: TextView? = null
    private var scrollView: ScrollView? = null
    private var contentFrame: FrameLayout? = null
    private var stepsSection: LinearLayout? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundNotification()
        createOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        try { windowManager?.removeView(overlayView) } catch (_: Exception) {}
    }

    private fun startForegroundNotification() {
        val channelId = "payment_overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Payment Progress",
                NotificationManager.IMPORTANCE_LOW).apply {
                description = "USSD payment progress"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, channelId)
        else @Suppress("DEPRECATION") Notification.Builder(this)).apply {
            setContentTitle("Processing payment...")
            setContentText("USSD *99# in progress")
            setSmallIcon(android.R.drawable.ic_menu_send)
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

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            PixelFormat.OPAQUE
        )
        params.gravity = Gravity.TOP or Gravity.START

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#FF0D1117"))
        }

        // Steps section (default view)
        stepsSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(60), dp(24), dp(24))
        }

        headerText = TextView(this).apply {
            text = "📡 Processing Payment"
            setTextColor(Color.parseColor("#66BB6A"))
            textSize = 22f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        }
        stepsSection!!.addView(headerText)

        val subtitle = TextView(this).apply {
            text = "USSD *99# running • Do not close"
            setTextColor(Color.parseColor("#757575"))
            textSize = 12f
            setPadding(0, 0, 0, dp(16))
        }
        stepsSection!!.addView(subtitle)

        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        stepsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollView!!.addView(stepsContainer)
        stepsSection!!.addView(scrollView)

        // Cancel button
        val cancelBtn = TextView(this).apply {
            text = "✕  Cancel Payment"
            setTextColor(Color.parseColor("#EF5350"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1C1C2E"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor("#EF5350"))
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(8)
            layoutParams = lp
            setOnClickListener {
                FlowStateMachine.reset()
                stopSelf()
            }
        }
        stepsSection!!.addView(cancelBtn)

        root.addView(stepsSection)

        // Content frame for PIN / success screens
        contentFrame = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#FF0D1117"))
        }
        root.addView(contentFrame)

        overlayView = root

        try {
            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            Log.e("OverlayService", "Overlay failed: ${e.message}")
            stopSelf()
        }
    }

    // ========================
    // STEPS
    // ========================

    private fun addStepInternal(status: String, message: String, isFinal: Boolean) {
        val dotColor = when {
            status.contains("error") || status.contains("fail") -> "#EF5350"
            status.contains("success") -> "#66BB6A"
            status.contains("pin") -> "#FFA726"
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
                status.contains("error") || status.contains("fail") -> "❌ Payment Failed"
                else -> "✅ Done"
            }
        }
    }

    // ========================
    // PIN INPUT
    // ========================

    private fun showPinInputInternal() {
        // Make overlay touchable for PIN input
        updateOverlayFlags(touchable = true)

        stepsSection?.visibility = View.GONE
        contentFrame?.visibility = View.VISIBLE
        contentFrame?.removeAllViews()

        val pinLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(80), dp(32), dp(32))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Lock icon
        val lockIcon = TextView(this).apply {
            text = "🔒"
            textSize = 48f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16))
        }
        pinLayout.addView(lockIcon)

        // Title
        val title = TextView(this).apply {
            text = "Enter UPI PIN"
            setTextColor(Color.parseColor("#FFA726"))
            textSize = 22f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        pinLayout.addView(title)

        val hint = TextView(this).apply {
            text = "Enter your 4-6 digit UPI PIN to complete payment"
            setTextColor(Color.parseColor("#757575"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(24))
        }
        pinLayout.addView(hint)

        // PIN display
        val pinDisplay = TextView(this).apply {
            text = ""
            setTextColor(Color.WHITE)
            textSize = 32f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A1F2B"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(2), Color.parseColor("#FFA726"))
            }
            setPadding(dp(20), dp(16), dp(20), dp(16))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(24)
            layoutParams = lp
        }
        pinLayout.addView(pinDisplay)

        var currentPin = ""

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
                lp.bottomMargin = dp(8)
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
                    textSize = 22f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor(when (key) {
                            "✓" -> "#1B3A1B"
                            "⌫" -> "#3A1B1B"
                            else -> "#1A1F2B"
                        }))
                        cornerRadius = dp(14).toFloat()
                        setStroke(dp(1), Color.parseColor("#30363D"))
                    }
                    val lp = LinearLayout.LayoutParams(0, dp(60), 1f)
                    lp.setMargins(dp(5), 0, dp(5), 0)
                    layoutParams = lp

                    setOnClickListener {
                        when (key) {
                            "⌫" -> {
                                if (currentPin.isNotEmpty()) {
                                    currentPin = currentPin.dropLast(1)
                                    pinDisplay.text = "●  ".repeat(currentPin.length).trim()
                                }
                            }
                            "✓" -> {
                                if (currentPin.length >= 4) {
                                    // Submit PIN!
                                    FlowStateMachine.setPinFromFlutter(currentPin)
                                    // Show processing state
                                    contentFrame?.visibility = View.GONE
                                    stepsSection?.visibility = View.VISIBLE
                                    headerText?.text = "🔒 Verifying PIN..."
                                    headerText?.setTextColor(Color.parseColor("#FFA726"))
                                    updateOverlayFlags(touchable = false)
                                }
                            }
                            else -> {
                                if (currentPin.length < 6) {
                                    currentPin += key
                                    pinDisplay.text = "●  ".repeat(currentPin.length).trim()
                                }
                            }
                        }
                    }
                }
                rowLayout.addView(btn)
            }
            pinLayout.addView(rowLayout)
        }

        contentFrame?.addView(pinLayout)
    }

    // ========================
    // SUCCESS SCREEN (GPay style)
    // ========================

    private fun showSuccessInternal(message: String) {
        updateOverlayFlags(touchable = true)

        stepsSection?.visibility = View.GONE
        contentFrame?.visibility = View.VISIBLE
        contentFrame?.removeAllViews()

        val successLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(60), dp(32), dp(32))
            setBackgroundColor(Color.parseColor("#FF0D1117"))
        }

        // Green circle with checkmark
        val checkCircle = TextView(this).apply {
            text = "✓"
            setTextColor(Color.WHITE)
            textSize = 52f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            val size = dp(120)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#4CAF50"))
            }
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER
                bottomMargin = dp(24)
            }
        }
        successLayout.addView(checkCircle)

        // Amount
        val amountText = TextView(this).apply {
            text = "₹${FlowStateMachine.targetAmount}"
            setTextColor(Color.WHITE)
            textSize = 40f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        successLayout.addView(amountText)

        // Status
        val statusText = TextView(this).apply {
            text = "Payment Successful"
            setTextColor(Color.parseColor("#66BB6A"))
            textSize = 20f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        successLayout.addView(statusText)

        // To
        val toText = TextView(this).apply {
            text = "To: ${FlowStateMachine.targetAccount}"
            setTextColor(Color.parseColor("#9E9E9E"))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(4))
        }
        successLayout.addView(toText)

        // Method
        val methodText = TextView(this).apply {
            text = "via USSD *99#"
            setTextColor(Color.parseColor("#757575"))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(32))
        }
        successLayout.addView(methodText)

        // Message
        if (message.isNotEmpty() && message != "✅ Payment successful!") {
            val msgText = TextView(this).apply {
                text = message
                setTextColor(Color.parseColor("#9E9E9E"))
                textSize = 12f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1A1F2B"))
                    cornerRadius = dp(12).toFloat()
                }
                setPadding(dp(16), dp(12), dp(16), dp(12))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(24)
                layoutParams = lp
            }
            successLayout.addView(msgText)
        }

        // Done button
        val doneBtn = TextView(this).apply {
            text = "Done"
            setTextColor(Color.BLACK)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#4CAF50"))
                cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(20), dp(16), dp(20), dp(16))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56))
            lp.topMargin = dp(8)
            layoutParams = lp
            setOnClickListener { stopSelf() }
        }
        successLayout.addView(doneBtn)

        contentFrame?.addView(successLayout)
    }

    // ========================
    // FAILURE SCREEN
    // ========================

    private fun showFailureInternal(message: String) {
        updateOverlayFlags(touchable = true)

        stepsSection?.visibility = View.GONE
        contentFrame?.visibility = View.VISIBLE
        contentFrame?.removeAllViews()

        val failLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(80), dp(32), dp(32))
        }

        val crossCircle = TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 52f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            val size = dp(120)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#EF5350"))
            }
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER
                bottomMargin = dp(24)
            }
        }
        failLayout.addView(crossCircle)

        val statusText = TextView(this).apply {
            text = "Payment Failed"
            setTextColor(Color.parseColor("#EF5350"))
            textSize = 20f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        }
        failLayout.addView(statusText)

        val msgText = TextView(this).apply {
            text = message
            setTextColor(Color.parseColor("#9E9E9E"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(32))
        }
        failLayout.addView(msgText)

        val closeBtn = TextView(this).apply {
            text = "Close"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EF5350"))
                cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(20), dp(16), dp(20), dp(16))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56))
            layoutParams = lp
            setOnClickListener { stopSelf() }
        }
        failLayout.addView(closeBtn)

        contentFrame?.addView(failLayout)
    }

    // ========================
    // OVERLAY FLAGS
    // ========================

    private fun updateOverlayFlags(touchable: Boolean) {
        try {
            val params = overlayView?.layoutParams as? WindowManager.LayoutParams ?: return
            params.flags = if (touchable) {
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            } else {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            }
            windowManager?.updateViewLayout(overlayView, params)
        } catch (e: Exception) {
            Log.e("OverlayService", "Failed to update flags: ${e.message}")
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
    }
}
