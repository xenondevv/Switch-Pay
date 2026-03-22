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
import android.os.Looper
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.offline_payment_app.engine.PaymentOverlayService
import com.example.offline_payment_app.engine.UssdAccessibilityService
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
                startIvrWithDtmf(target, amount)
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
    // IVR — AccessibilityService Dialpad Tapping
    //
    // Strategy:
    //   - Place a PLAIN call (no DTMF in URI — commas don't work on VoLTE)
    //   - PhoneStateListener detects OFFHOOK → wait for welcome → open keypad
    //   - AccessibilityService physically taps the dialer's keypad buttons
    //   - Each key tap is verified (logged ✅ or ❌)
    //
    // Timing:
    //   +20s  → open keypad, press 1
    //   +3s   → enter number + #
    //   +3s   → enter amount + #
    //   +5s   → press 1 (confirm)
    // ========================

    @Suppress("DEPRECATION")
    private fun startIvrWithDtmf(target: String, amount: String) {
        startOverlay()
        outgoingIvrActive = true

        // Set up call state listener
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

                            // Dump the UI tree for debugging (remove after it works)
                            handler.postDelayed({
                                Log.d("OfflinePayment", "Dumping window tree for debugging...")
                                UssdAccessibilityService.dumpWindowTree()
                            }, 5000)

                            // +2s: show waiting message
                            handler.postDelayed({
                                step("ivr_wait", "⏳ Waiting for welcome message (20s)...", false)
                            }, 2000)

                            // +18s: open dialpad first
                            handler.postDelayed({
                                val opened = UssdAccessibilityService.openDialpad()
                                Log.d("OfflinePayment", "Dialpad open: $opened")
                                if (!opened) {
                                    step("ivr_keypad", "⌨️ Dialpad may already be open...", false)
                                }
                            }, 18000)

                            // +20s: press 1 (Transfer Money)
                            handler.postDelayed({
                                step("ivr_menu", "📞 Pressing 1 → Transfer Money", false)
                                val ok = UssdAccessibilityService.tapDialpadKey('1')
                                if (!ok) step("ivr_menu_fail", "❌ Could not tap '1' — check Accessibility Service", false)
                            }, 20000)

                            // +23s: enter target number + #
                            handler.postDelayed({
                                step("ivr_number", "📞 Entering: $target #", false)
                                tapSequenceViaAccessibility("$target#")
                            }, 23000)

                            // +26s: enter amount + #
                            handler.postDelayed({
                                step("ivr_amount", "📞 Entering: ₹$amount #", false)
                                tapSequenceViaAccessibility("$amount#")
                            }, 28000)

                            // +31s: press 1 (confirm)
                            handler.postDelayed({
                                step("ivr_confirm", "📞 Pressing 1 → Confirm", false)
                                UssdAccessibilityService.tapDialpadKey('1')
                            }, 33000)
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

        // Place a PLAIN call — NO DTMF in URI
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

    /**
     * Tap a sequence of digits on the dialer keypad via AccessibilityService.
     * Each digit is tapped with a 400ms interval.
     */
    private fun tapSequenceViaAccessibility(digits: String) {
        digits.forEachIndexed { i, d ->
            handler.postDelayed({
                val ok = UssdAccessibilityService.tapDialpadKey(d)
                Log.d("OfflinePayment", "Tap '$d': ${if (ok) "✅" else "❌"}")
            }, i * 400L)
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
                                playDtmf('1')
                            }, 5000)
                            handler.postDelayed({
                                step("ivr_pin", "🔒 Enter UPI PIN below", false)
                                PaymentOverlayService.showPinInput { pin ->
                                    step("ivr_pin_entry", "🔒 Sending PIN...", false)
                                    playSequence(pin) {
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
    // DTMF for callback (ToneGenerator — plays through call audio on some devices)
    // ========================
    private fun playDtmf(d: Char) {
        try {
            if (toneGenerator == null) toneGenerator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, ToneGenerator.MAX_VOLUME)
            val t = when(d) { '0'->ToneGenerator.TONE_DTMF_0; '1'->ToneGenerator.TONE_DTMF_1
                '2'->ToneGenerator.TONE_DTMF_2; '3'->ToneGenerator.TONE_DTMF_3
                '4'->ToneGenerator.TONE_DTMF_4; '5'->ToneGenerator.TONE_DTMF_5
                '6'->ToneGenerator.TONE_DTMF_6; '7'->ToneGenerator.TONE_DTMF_7
                '8'->ToneGenerator.TONE_DTMF_8; '9'->ToneGenerator.TONE_DTMF_9
                '#'->ToneGenerator.TONE_DTMF_P; '*'->ToneGenerator.TONE_DTMF_S; else->return }
            toneGenerator?.startTone(t, 300)
        } catch (_: Exception) {}
    }

    private fun playSequence(digits: String, done: () -> Unit) {
        digits.forEachIndexed { i, d ->
            handler.postDelayed({ playDtmf(d); if (i == digits.length - 1) handler.postDelayed(done, 500) }, i * 400L)
        }
    }

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
