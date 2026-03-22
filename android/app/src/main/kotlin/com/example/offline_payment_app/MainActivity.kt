package com.example.offline_payment_app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.offline_payment_app.engine.PaymentOverlayService
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity: FlutterActivity() {
    private val CHANNEL = "offline_payment_channel"
    private val PERMISSION_REQUEST_CODE = 200
    private val OVERLAY_PERMISSION_CODE = 201
    private var pendingTarget = ""
    private var pendingAmount = ""
    private var pendingMode = "USSD"
    private var methodChannelInstance: MethodChannel? = null
    private val handler = Handler(Looper.getMainLooper())

    private var ussdStep = 0
    private var isBalanceCheck = false
    private var isOverlayActive = false
    private var waitingForCallback = false
    private var outgoingIvrActive = false
    private var toneGenerator: ToneGenerator? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        methodChannelInstance = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        methodChannelInstance!!.setMethodCallHandler { call, result ->
            when (call.method) {
                "executePayment" -> {
                    val target = call.argument<String>("target") ?: ""
                    val amount = call.argument<String>("amount") ?: ""
                    val mode = call.argument<String>("mode") ?: "USSD"
                    pendingTarget = target
                    pendingAmount = amount
                    pendingMode = mode
                    isBalanceCheck = (target == "BALANCE")
                    ussdStep = 0

                    val perms = mutableListOf<String>()
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                        != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.CALL_PHONE)
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                        != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.READ_PHONE_STATE)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                        ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS)
                        != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.ANSWER_PHONE_CALLS)

                    if (perms.isEmpty()) { startPaymentFlow(mode, target, amount); result.success(true) }
                    else { ActivityCompat.requestPermissions(this, perms.toTypedArray(), PERMISSION_REQUEST_CODE); result.success("permission_requested") }
                }
                else -> result.notImplemented()
            }
        }
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(rc, perms, results)
        if (rc == PERMISSION_REQUEST_CODE && results.all { it == PackageManager.PERMISSION_GRANTED })
            startPaymentFlow(pendingMode, pendingTarget, pendingAmount)
    }

    override fun onActivityResult(rc: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(rc, resultCode, data)
        if (rc == OVERLAY_PERMISSION_CODE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this))
            startPaymentFlow(pendingMode, pendingTarget, pendingAmount)
    }

    private fun startPaymentFlow(mode: String, target: String, amount: String) {
        when (mode) {
            "IVR" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                    sendStep("overlay_permission", "⚙️ Requesting overlay permission...", false)
                    startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), OVERLAY_PERMISSION_CODE)
                    return
                }
                startIvrCall(target, amount)
            }
            else -> startInAppUssd(target, amount)
        }
    }

    // ========================
    // OVERLAY
    // ========================
    private fun startOverlay() {
        isOverlayActive = true
        val intent = Intent(this, PaymentOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }
    private fun closeOverlay() { isOverlayActive = false; PaymentOverlayService.close() }
    private fun step(status: String, msg: String, isFinal: Boolean) {
        sendStep(status, msg, isFinal)
        if (isOverlayActive) PaymentOverlayService.addStep(status, msg, isFinal)
    }

    // ========================
    // USSD
    // ========================
    private fun startInAppUssd(target: String, amount: String) {
        ussdStep = 0
        sendStep("connecting", "Trying USSD (*99#)...", false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) sendUssdCommand("*99#", target, amount)
        else { sendStep("ussd_failed", "Android 8+ needed. Switching to IVR...", false)
               handler.postDelayed({ startPaymentFlow("IVR", target, amount) }, 1500) }
    }

    private fun sendUssdCommand(cmd: String, target: String, amount: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return
        try {
            (getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).sendUssdRequest(cmd,
                object : TelephonyManager.UssdResponseCallback() {
                    override fun onReceiveUssdResponse(t: TelephonyManager, r: String, resp: CharSequence) { handleUssd(resp.toString(), target, amount) }
                    override fun onReceiveUssdResponseFailed(t: TelephonyManager, r: String, c: Int) {
                        sendStep("ussd_failed", "USSD failed. Switching to IVR...", false)
                        handler.postDelayed({ startPaymentFlow("IVR", target, amount) }, 1500)
                    }
                }, handler)
        } catch (e: Exception) {
            sendStep("ussd_failed", "USSD error. Switching to IVR...", false)
            handler.postDelayed({ startPaymentFlow("IVR", target, amount) }, 1500)
        }
    }

    private fun handleUssd(resp: String, target: String, amount: String) {
        when (ussdStep) {
            0 -> { val opt = if (isBalanceCheck) findOpt(resp, listOf("balance","enquiry")) ?: "3"
                            else findOpt(resp, listOf("send","money","transfer")) ?: "1"
                   ussdStep = 1; handler.postDelayed({ sendUssdCommand(opt, target, amount) }, 1000) }
            1 -> { if (isBalanceCheck) { sendStep("success", "✅ $resp", true); return }
                   ussdStep = 2; handler.postDelayed({ sendUssdCommand(target, target, amount) }, 1000) }
            2 -> { ussdStep = 3; handler.postDelayed({ sendUssdCommand(amount, target, amount) }, 1000) }
            3 -> { sendStep("waiting_pin", "🔒 Enter UPI PIN on USSD popup.", true); ussdStep = 0 }
            else -> { sendStep("response", resp, true); ussdStep = 0 }
        }
    }

    // ========================
    // IVR — Hidden API ITelephony.sendDtmf() via Reflection
    //
    // Uses Android's internal telephony service to send DTMF directly
    // through the modem/telephony stack. Works on ANY network (GSM/VoLTE)
    // without being the default dialer.
    //
    // Flow:
    //   Call → 18s welcome → press 1 → 2s → number# → 2s → amount#
    //   → 5s → press 1 confirm → call cuts → callback → press 1 → PIN
    // ========================

    @Suppress("DEPRECATION")
    private fun startIvrCall(target: String, amount: String) {
        startOverlay()
        outgoingIvrActive = true

        val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        var callConnected = false

        tm.listen(object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, number: String?) {
                when (state) {
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        if (!callConnected && outgoingIvrActive) {
                            callConnected = true
                            Log.d("OfflinePayment", ">>> CALL CONNECTED")
                            step("ivr_connected", "📞 Connected! Waiting for IVR menu...", false)

                            // Test DTMF method availability immediately
                            handler.postDelayed({
                                val method = getDtmfMethod()
                                Log.d("OfflinePayment", "DTMF method: ${method ?: "NONE AVAILABLE"}")
                                if (method == null) {
                                    step("ivr_warn", "⚠️ DTMF API not available on this device", false)
                                }
                            }, 2000)

                            handler.postDelayed({
                                step("ivr_wait", "⏳ Waiting for welcome (18s)...", false)
                            }, 3000)

                            // +18s: Press 1 → Transfer Money
                            handler.postDelayed({
                                step("ivr_menu", "📞 Pressing 1 → Transfer Money", false)
                                sendDtmfDirect('1')
                            }, 18000)

                            // +20s: Enter phone number + #
                            handler.postDelayed({
                                step("ivr_number", "📞 Entering: $target #", false)
                                sendSequenceDirect("$target#") {}
                            }, 20000)

                            // +25s: Enter amount + #
                            handler.postDelayed({
                                step("ivr_amount", "📞 Entering: ₹$amount #", false)
                                sendSequenceDirect("$amount#") {}
                            }, 25000)

                            // +30s: Press 1 → Confirm
                            handler.postDelayed({
                                step("ivr_confirm", "📞 Pressing 1 → Confirm", false)
                                sendDtmfDirect('1')
                            }, 30000)
                        }
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
                        if (callConnected && outgoingIvrActive) {
                            callConnected = false
                            outgoingIvrActive = false
                            tm.listen(this, PhoneStateListener.LISTEN_NONE)
                            Log.d("OfflinePayment", ">>> CALL 1 ENDED")
                            step("ivr_call1_done", "✅ Call 1 done. Waiting for callback...", false)
                            waitingForCallback = true
                            startCallbackListener()
                        }
                    }
                }
            }
        }, PhoneStateListener.LISTEN_CALL_STATE)

        // Place a PLAIN call
        try {
            val uri = Uri.parse("tel:+918045163666")
            val intent = Intent(Intent.ACTION_CALL, uri)
            startActivity(intent)
            step("ivr_dialing", "📞 Dialing IVR number...", false)
        } catch (e: Exception) {
            closeOverlay()
            sendStep("error", "Call failed: ${e.message}", true)
        }
    }

    // ========================
    // DIRECT DTMF via Hidden Telephony API (Reflection)
    //
    // Method 1: TelephonyManager → getITelephony() → sendDtmf(char)
    // Method 2: ServiceManager → getService("phone") → ITelephony.Stub → sendDtmf(char)
    // ========================

    private fun getDtmfMethod(): String? {
        // Method 1: TelephonyManager.getITelephony()
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val getIT = tm.javaClass.getDeclaredMethod("getITelephony")
            getIT.isAccessible = true
            val it = getIT.invoke(tm)
            if (it != null) {
                // Check if sendDtmf exists
                val m = it.javaClass.getMethod("sendDtmf", Char::class.javaPrimitiveType)
                Log.d("OfflinePayment", "✅ Method 1 available: ITelephony.sendDtmf via TelephonyManager")
                return "METHOD_1"
            }
        } catch (e: Exception) {
            Log.d("OfflinePayment", "Method 1 failed: ${e.javaClass.simpleName}: ${e.message}")
        }

        // Method 2: ServiceManager.getService("phone")
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "phone") as? IBinder
            if (binder != null) {
                val stub = Class.forName("com.android.internal.telephony.ITelephony\$Stub")
                val asInterface = stub.getMethod("asInterface", IBinder::class.java)
                val it = asInterface.invoke(null, binder)
                if (it != null) {
                    val m = it.javaClass.getMethod("sendDtmf", Char::class.javaPrimitiveType)
                    Log.d("OfflinePayment", "✅ Method 2 available: ITelephony.sendDtmf via ServiceManager")
                    return "METHOD_2"
                }
            }
        } catch (e: Exception) {
            Log.d("OfflinePayment", "Method 2 failed: ${e.javaClass.simpleName}: ${e.message}")
        }

        Log.e("OfflinePayment", "❌ No DTMF method available")
        return null
    }

    @Suppress("PrivateApi")
    private fun sendDtmfDirect(digit: Char): Boolean {
        Log.d("OfflinePayment", ">>> Sending DTMF '$digit' via telephony API...")

        // Method 1: TelephonyManager → getITelephony() → sendDtmf()
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val getIT = tm.javaClass.getDeclaredMethod("getITelephony")
            getIT.isAccessible = true
            val iTelephony = getIT.invoke(tm)
            if (iTelephony != null) {
                val sendDtmf = iTelephony.javaClass.getMethod("sendDtmf", Char::class.javaPrimitiveType)
                val result = sendDtmf.invoke(iTelephony, digit)
                Log.d("OfflinePayment", "✅ DTMF '$digit' sent via Method 1, result=$result")
                return true
            }
        } catch (e: Exception) {
            Log.d("OfflinePayment", "Method 1 sendDtmf failed: ${e.javaClass.simpleName}: ${e.message}")
        }

        // Method 2: ServiceManager → ITelephony.Stub → sendDtmf()
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "phone") as? IBinder
            if (binder != null) {
                val stub = Class.forName("com.android.internal.telephony.ITelephony\$Stub")
                val asInterface = stub.getMethod("asInterface", IBinder::class.java)
                val iTelephony = asInterface.invoke(null, binder)
                if (iTelephony != null) {
                    val sendDtmf = iTelephony.javaClass.getMethod("sendDtmf", Char::class.javaPrimitiveType)
                    val result = sendDtmf.invoke(iTelephony, digit)
                    Log.d("OfflinePayment", "✅ DTMF '$digit' sent via Method 2, result=$result")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.d("OfflinePayment", "Method 2 sendDtmf failed: ${e.javaClass.simpleName}: ${e.message}")
        }

        Log.e("OfflinePayment", "❌ ALL DTMF methods failed for '$digit'")
        return false
    }

    private fun sendSequenceDirect(digits: String, done: () -> Unit) {
        digits.forEachIndexed { i, d ->
            handler.postDelayed({
                val ok = sendDtmfDirect(d)
                Log.d("OfflinePayment", "Seq DTMF '$d': ${if (ok) "✅" else "❌"}")
                if (i == digits.length - 1) handler.postDelayed(done, 500)
            }, i * 500L)  // 500ms between digits
        }
    }

    // ========================
    // CALLBACK (Call 2)
    // ========================
    @Suppress("DEPRECATION")
    private fun startCallbackListener() {
        Log.d("OfflinePayment", "Listening for callback...")
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        var answered = false

        tm.listen(object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, number: String?) {
                if (!waitingForCallback) return
                when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> {
                        step("ivr_callback_ring", "📞 Callback! Auto-answering...", false)
                        handler.postDelayed({ autoAnswer() }, 2000)
                    }
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        if (!answered) {
                            answered = true
                            handler.postDelayed({
                                step("ivr_cb_confirm", "📞 Pressing 1 → Verify", false)
                                sendDtmfDirect('1')
                            }, 5000)
                            handler.postDelayed({
                                step("ivr_pin", "🔒 Enter UPI PIN below", false)
                                PaymentOverlayService.showPinInput { pin ->
                                    step("ivr_pin_entry", "🔒 Sending PIN...", false)
                                    sendSequenceDirect(pin) {
                                        step("ivr_pin_done", "✅ PIN sent. Waiting...", false)
                                    }
                                }
                            }, 8000)
                        }
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
                        if (answered) {
                            waitingForCallback = false
                            tm.listen(this, PhoneStateListener.LISTEN_NONE)
                            handler.postDelayed({
                                step("success", "✅ Payment complete!\nCheck SMS for confirmation.", true)
                                handler.postDelayed({ closeOverlay() }, 3000)
                            }, 2000)
                        }
                    }
                }
            }
        }, PhoneStateListener.LISTEN_CALL_STATE)
    }

    private fun autoAnswer() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val tm = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED)
                    tm.acceptRingingCall()
            }
        } catch (_: Exception) { step("warn", "⚠️ Answer manually", false) }
    }

    // ========================
    // UTILS
    // ========================
    private fun findOpt(text: String, kw: List<String>): String? {
        for (k in kw) { Regex("""(\d+)\s*[.):\-]?\s*[^0-9]*$k""", RegexOption.IGNORE_CASE).find(text.lowercase())?.let { return it.groupValues[1] } }
        return null
    }

    private fun sendStep(status: String, msg: String, isFinal: Boolean) {
        Log.d("OfflinePayment", "STEP [$status]: $msg")
        handler.post { methodChannelInstance?.invokeMethod("paymentStatus", mapOf("status" to status, "message" to msg, "isFinal" to isFinal)) }
    }

    override fun onDestroy() { super.onDestroy(); toneGenerator?.release(); toneGenerator = null }
}
