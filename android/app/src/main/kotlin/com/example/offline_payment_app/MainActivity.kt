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
    private var audioManager: AudioManager? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
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
    // IVR — Speakerphone + ToneGenerator DTMF
    //
    // Strategy:
    //   VoLTE bypasses Android audio framework for DTMF, so URI commas
    //   and STREAM_VOICE_CALL tones don't reach the IVR.
    //
    //   Solution: Turn on SPEAKERPHONE, play DTMF tones LOUD through
    //   the speaker. The phone's microphone picks up the tones from
    //   the speaker and sends them through the VoLTE uplink audio.
    //   This is "acoustic coupling" — works on ANY phone, ANY network.
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
                            step("ivr_connected", "📞 Connected! Turning on speaker for DTMF...", false)

                            // Turn on speakerphone so DTMF tones go through the mic
                            handler.postDelayed({
                                enableSpeakerForDtmf()
                            }, 1000)

                            handler.postDelayed({
                                step("ivr_wait", "⏳ Waiting for welcome (18s)...", false)
                            }, 2000)

                            // +18s: Press 1 → Transfer Money
                            handler.postDelayed({
                                step("ivr_menu", "📞 Pressing 1 → Transfer Money", false)
                                playDtmfLoud('1')
                            }, 18000)

                            // +20s: Enter phone number + #
                            handler.postDelayed({
                                step("ivr_number", "📞 Entering: $target #", false)
                                playSequenceLoud("$target#") {}
                            }, 20000)

                            // +25s: Enter amount + #  (give 5s for number entry)
                            handler.postDelayed({
                                step("ivr_amount", "📞 Entering: ₹$amount #", false)
                                playSequenceLoud("$amount#") {}
                            }, 25000)

                            // +30s: Press 1 → Confirm
                            handler.postDelayed({
                                step("ivr_confirm", "📞 Pressing 1 → Confirm", false)
                                playDtmfLoud('1')
                            }, 30000)
                        }
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
                        if (callConnected && outgoingIvrActive) {
                            callConnected = false
                            outgoingIvrActive = false
                            tm.listen(this, PhoneStateListener.LISTEN_NONE)
                            Log.d("OfflinePayment", ">>> CALL 1 ENDED")
                            // Disable speaker after call 1
                            disableSpeaker()
                            step("ivr_call1_done", "✅ Call 1 done. Waiting for callback...", false)
                            waitingForCallback = true
                            startCallbackListener()
                        }
                    }
                }
            }
        }, PhoneStateListener.LISTEN_CALL_STATE)

        // Place a PLAIN call — DTMF sent via speakerphone acoustic coupling
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
    // SPEAKERPHONE DTMF (acoustic coupling)
    // ========================
    private fun enableSpeakerForDtmf() {
        try {
            audioManager?.let { am ->
                am.mode = AudioManager.MODE_IN_CALL
                am.isSpeakerphoneOn = true
                // Set volume to max for reliable DTMF pickup
                val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, 0)
                Log.d("OfflinePayment", "🔊 Speaker ON, volume MAX")
            }
        } catch (e: Exception) {
            Log.e("OfflinePayment", "Speaker setup failed: ${e.message}")
        }
    }

    private fun disableSpeaker() {
        try {
            audioManager?.isSpeakerphoneOn = false
            Log.d("OfflinePayment", "🔇 Speaker OFF")
        } catch (_: Exception) {}
    }

    /**
     * Play a DTMF tone LOUD through the speaker.
     * The mic picks it up → sends to IVR via uplink audio.
     * Uses STREAM_MUSIC (goes to speaker) + 500ms duration for reliable detection.
     */
    private fun playDtmfLoud(d: Char) {
        try {
            // Create tone generator on STREAM_MUSIC (speaker audio, NOT call audio)
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, ToneGenerator.MAX_VOLUME)
            val tone = when(d) {
                '0' -> ToneGenerator.TONE_DTMF_0; '1' -> ToneGenerator.TONE_DTMF_1
                '2' -> ToneGenerator.TONE_DTMF_2; '3' -> ToneGenerator.TONE_DTMF_3
                '4' -> ToneGenerator.TONE_DTMF_4; '5' -> ToneGenerator.TONE_DTMF_5
                '6' -> ToneGenerator.TONE_DTMF_6; '7' -> ToneGenerator.TONE_DTMF_7
                '8' -> ToneGenerator.TONE_DTMF_8; '9' -> ToneGenerator.TONE_DTMF_9
                '#' -> ToneGenerator.TONE_DTMF_P; '*' -> ToneGenerator.TONE_DTMF_S
                else -> { Log.e("OfflinePayment", "Unknown DTMF char: $d"); return }
            }
            tg.startTone(tone, 500)  // 500ms for reliable acoustic detection
            Log.d("OfflinePayment", "🔊 DTMF LOUD: '$d' (500ms)")
            // Release after tone completes
            handler.postDelayed({ tg.release() }, 600)
        } catch (e: Exception) {
            Log.e("OfflinePayment", "DTMF failed: ${e.message}")
        }
    }

    /**
     * Play a sequence of DTMF tones loudly.
     * 700ms between each tone (500ms tone + 200ms silence).
     */
    private fun playSequenceLoud(digits: String, done: () -> Unit) {
        digits.forEachIndexed { i, d ->
            handler.postDelayed({
                playDtmfLoud(d)
                if (i == digits.length - 1) handler.postDelayed(done, 700)
            }, i * 700L)  // 700ms per digit
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
                            // Enable speaker for callback DTMF too
                            handler.postDelayed({ enableSpeakerForDtmf() }, 1000)

                            handler.postDelayed({
                                step("ivr_cb_confirm", "📞 Pressing 1 → Verify", false)
                                playDtmfLoud('1')
                            }, 5000)
                            handler.postDelayed({
                                step("ivr_pin", "🔒 Enter UPI PIN below", false)
                                PaymentOverlayService.showPinInput { pin ->
                                    step("ivr_pin_entry", "🔒 Sending PIN...", false)
                                    playSequenceLoud(pin) {
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
                            disableSpeaker()
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

    override fun onDestroy() {
        super.onDestroy()
        toneGenerator?.release()
        toneGenerator = null
        disableSpeaker()
    }
}
