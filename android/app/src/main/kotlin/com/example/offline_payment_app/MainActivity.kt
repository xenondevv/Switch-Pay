package com.example.offline_payment_app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.offline_payment_app.engine.FlowStateMachine
import com.example.offline_payment_app.engine.PaymentOverlayService
import com.example.offline_payment_app.engine.UssdAccessibilityService
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val CHANNEL = "offline_payment_channel"
    private val PERMISSION_REQUEST_CODE = 200
    private val OVERLAY_PERMISSION_CODE = 201
    private var pendingTarget = ""
    private var pendingAmount = ""
    private var methodChannelInstance: MethodChannel? = null
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "OfflinePayment"
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        methodChannelInstance = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        methodChannelInstance!!.setMethodCallHandler { call, result ->
            when (call.method) {
                "executePayment" -> {
                    val target = call.argument<String>("target") ?: ""
                    val amount = call.argument<String>("amount") ?: ""
                    pendingTarget = target
                    pendingAmount = amount

                    val perms = mutableListOf<String>()
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                        != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.CALL_PHONE)
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                        != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.READ_PHONE_STATE)

                    if (perms.isEmpty()) {
                        startUssdPayment(target, amount)
                        result.success(true)
                    } else {
                        ActivityCompat.requestPermissions(this, perms.toTypedArray(), PERMISSION_REQUEST_CODE)
                        result.success("permission_requested")
                    }
                }

                "sendUpiPin" -> {
                    val pin = call.argument<String>("pin") ?: ""
                    if (pin.isNotEmpty()) {
                        FlowStateMachine.setPinFromFlutter(pin)
                    }
                    result.success(true)
                }

                "checkAccessibility" -> {
                    result.success(UssdAccessibilityService.instance != null)
                }

                "openAccessibilitySettings" -> {
                    try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {}
                    result.success(true)
                }

                else -> result.notImplemented()
            }
        }
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(rc, perms, results)
        if (rc == PERMISSION_REQUEST_CODE && results.all { it == PackageManager.PERMISSION_GRANTED }) {
            startUssdPayment(pendingTarget, pendingAmount)
        }
    }

    override fun onActivityResult(rc: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(rc, resultCode, data)
        if (rc == OVERLAY_PERMISSION_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                startUssdPayment(pendingTarget, pendingAmount)
            }
        }
    }

    // ========================
    // USSD PAYMENT FLOW
    // ========================

    private fun startUssdPayment(target: String, amount: String) {
        if (UssdAccessibilityService.instance == null) {
            sendStep("error", "⚠️ Enable Accessibility Service in Settings first", true)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            sendStep("overlay_permission", "⚙️ Requesting overlay permission...", false)
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                OVERLAY_PERMISSION_CODE
            )
            return
        }

        // Set up FlowStateMachine callbacks
        FlowStateMachine.statusCallback = { status, message, isFinal ->
            sendStep(status, message, isFinal)

            when {
                // PIN needed → show PIN keypad in overlay (don't close overlay!)
                status == "enter_pin" -> {
                    PaymentOverlayService.showPinInput()
                }
                // Success → show GPay-style success screen in overlay
                status.contains("success") && isFinal -> {
                    handler.postDelayed({
                        PaymentOverlayService.showSuccess(message)
                    }, 500)
                }
                // Failure → show failure screen in overlay
                (status.contains("error") || status.contains("fail")) && isFinal -> {
                    handler.postDelayed({
                        PaymentOverlayService.showFailure(message)
                    }, 500)
                }
            }
        }
        FlowStateMachine.startNewTransaction(target, amount)

        // Start overlay
        val intent = Intent(this, PaymentOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        // Dial *99#
        handler.postDelayed({
            try {
                val ussdCode = "*99${Uri.encode("#")}"
                val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$ussdCode"))
                startActivity(callIntent)
                Log.d(TAG, "Dialed *99#")
            } catch (e: Exception) {
                sendStep("error", "❌ Failed to dial *99#: ${e.message}", true)
                PaymentOverlayService.close()
                FlowStateMachine.reset()
            }
        }, 500)
    }

    // ========================
    // FLUTTER COMMUNICATION
    // ========================

    private fun sendStep(status: String, msg: String, isFinal: Boolean) {
        Log.d(TAG, "STEP [$status]: $msg (final=$isFinal)")
        handler.post {
            methodChannelInstance?.invokeMethod("paymentStatus", mapOf(
                "status" to status, "message" to msg, "isFinal" to isFinal
            ))
        }
        // Also update overlay steps (not for PIN/success — those have their own views)
        if (status != "enter_pin" && !(isFinal && (status.contains("success") || status.contains("error")))) {
            PaymentOverlayService.addStep(status, msg, isFinal)
        }
    }
}
