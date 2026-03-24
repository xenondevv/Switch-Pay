package com.example.offline_payment_app.engine

import android.util.Log

/**
 * State machine for *99# USSD flow (tested with SBI).
 *
 * Flow: IDLE → WAIT_WELCOME → WAIT_BANK_MENU → WAIT_SEND_METHOD → WAIT_NUMBER → WAIT_AMOUNT → WAIT_REMARK → WAIT_PIN → DONE
 *
 * Real sequence (SBI):
 *   0. Welcome: "Welcome to *99#" → click OK (info-only dialog, no input)
 *   1. Bank menu: "Select Option: State Bank Of India" → send "1" (Send Money)
 *   2. Send method: "Send Money to: 1. Mobile No. ..." → send "1" (Mobile No.)
 *   3. Enter UPI ID: "Enter Mobile No. Or 00.Bac" → send UPI ID (option 1 accepts UPI IDs)
 *   4. Enter amount: "Paying NAME, Enter Amount in Rs." → send amount
 *   5. Remark: "Enter a remark (Enter 1 to skip)" → send "1"
 *   6. PIN: collect via in-app keypad → send via USSD
 *   7. Done: success/failure
 */
object FlowStateMachine {
    private const val TAG = "FlowStateMachine"

    enum class State {
        IDLE,
        WAIT_WELCOME,
        WAIT_BANK_MENU,
        WAIT_SEND_METHOD,
        WAIT_NUMBER,
        WAIT_AMOUNT,
        WAIT_REMARK,
        WAIT_PIN,
        DONE
    }

    var state = State.IDLE
    var targetAccount = ""
    var targetAmount = ""
    var pendingPin: String? = null
    var waitingForPin = false   // blocks event processing while waiting for PIN
    private var lastProcessedText = ""
    private var lastProcessedTime = 0L

    var statusCallback: ((status: String, message: String, isFinal: Boolean) -> Unit)? = null

    fun startNewTransaction(target: String, amount: String) {
        targetAccount = target
        targetAmount = amount
        pendingPin = null
        waitingForPin = false
        lastProcessedText = ""
        lastProcessedTime = 0L
        state = State.WAIT_WELCOME
        Log.d(TAG, "New transaction: target=$target, amount=$amount")
        sendStatus("dialing", "📡 Dialing *99# USSD...", false)
    }

    fun setPinFromFlutter(pin: String) {
        pendingPin = pin
        waitingForPin = false  // allow processing again
        Log.d(TAG, "PIN received (${pin.length} digits), entering into USSD")

        if (state == State.WAIT_PIN) {
            val svc = UssdAccessibilityService.instance
            if (svc != null) {
                sendStatus("pin_entering", "🔒 Entering UPI PIN...", false)
                svc.sendInput(pin)
                pendingPin = null  // Clear so handlePinStep() can't re-send
                state = State.DONE
            } else {
                Log.e(TAG, "AccessibilityService not connected")
                sendStatus("error", "❌ Accessibility service not connected", true)
            }
        }
    }

    fun processUssdText(text: String, service: UssdAccessibilityService) {
        // BLOCK processing while waiting for PIN input from user
        if (waitingForPin) {
            Log.d(TAG, "Blocked: waiting for PIN input from user")
            return
        }

        val lower = text.lowercase()

        // Deduplicate
        val now = System.currentTimeMillis()
        val textKey = lower.trim().take(80)
        if (textKey == lastProcessedText && (now - lastProcessedTime) < 1500) {
            Log.d(TAG, "Skipping duplicate text")
            return
        }
        lastProcessedText = textKey
        lastProcessedTime = now

        Log.d(TAG, "Processing [state=$state]: ${lower.take(120)}")

        when (state) {
            State.WAIT_WELCOME -> {
                // First dialog: "Welcome to *99#" — info-only, just has OK button
                // Also handles any initial info screen before the bank menu
                val shortText = text.take(80).trim()
                Log.d(TAG, "Welcome dialog detected: $shortText")
                sendStatus("welcome", "📋 $shortText", false)

                // Check if this is actually the bank menu (some carriers skip welcome)
                if (lower.contains("select option") ||
                    (lower.contains("1.") && (lower.contains("send money") || lower.contains("send")))) {
                    // This IS the bank menu, skip welcome step
                    Log.d(TAG, "No welcome screen — directly at bank menu")
                    state = State.WAIT_BANK_MENU
                    processUssdText(text, service)  // re-process as bank menu
                } else {
                    // Info-only dialog — just click OK to dismiss and move on
                    service.clickOkButton()
                    state = State.WAIT_BANK_MENU
                }
            }

            State.WAIT_BANK_MENU -> {
                // SBI prompt: "Select Option: State Bank Of India → 1. Send Money ..."
                // Require specific bank menu indicators, not loose keywords
                if (lower.contains("select option") ||
                    (lower.contains("1.") && (lower.contains("send money") || lower.contains("send"))) ||
                    (lower.contains("send money") && lower.contains("check balance"))) {

                    val shortText = text.take(80).trim()
                    Log.d(TAG, "Bank menu → Selecting '1' (Send Money)")
                    sendStatus("bank_menu", "📋 Bank menu → Selecting 'Send Money'", false)
                    service.sendInput("1")
                    state = State.WAIT_SEND_METHOD
                } else if (lower.contains("welcome") || lower.contains("hello") ||
                           lower.length < 30) {
                    // Another info/welcome dialog — dismiss and keep waiting
                    val shortText = text.take(80).trim()
                    Log.d(TAG, "Info dialog in WAIT_BANK_MENU: $shortText")
                    sendStatus("info", "📋 $shortText", false)
                    service.clickOkButton()
                } else {
                    Log.d(TAG, "WAIT_BANK_MENU: Unrecognized text, waiting: ${lower.take(80)}")
                }
            }

            State.WAIT_SEND_METHOD -> {
                // SBI prompt: "Send Money to: 1. Mobile No. 3. UPI ID ..."
                // IMPORTANT: Skip stale bank menu text (contains "select option" but not "send money to")
                if (lower.contains("select option") && !lower.contains("send money to")) {
                    Log.d(TAG, "WAIT_SEND_METHOD: Ignoring stale bank menu text")
                    return
                }

                if (lower.contains("send money to") || lower.contains("money to") ||
                    (lower.contains("mobile no") && lower.contains("upi id"))) {

                    Log.d(TAG, "Send method → Selecting '1' (Mobile No. — accepts UPI IDs)")
                    sendStatus("select_method", "📋 Selecting 'Mobile No.' (accepts UPI IDs)", false)
                    service.sendInput("1")
                    state = State.WAIT_NUMBER
                } else if (lower.contains("enter") && (lower.contains("mobile") || lower.contains("no."))) {
                    // Already at number entry (skipped method selection)
                    Log.d(TAG, "Direct UPI ID entry prompt")
                    sendStatus("entering_number", "📝 Entering UPI ID: $targetAccount", false)
                    service.sendInput(targetAccount)
                    state = State.WAIT_AMOUNT
                } else {
                    // Don't act on unrecognized text — wait for the correct menu
                    Log.d(TAG, "WAIT_SEND_METHOD: Ignoring unrecognized text, waiting for menu")
                }
            }

            State.WAIT_NUMBER -> {
                // SBI prompt: "Enter Mobile No. Or 00.Bac"
                // IMPORTANT: Skip "Send Money to:" menu text — that belongs to WAIT_SEND_METHOD
                if (lower.contains("send money to") || lower.contains("money to") ||
                    (lower.contains("mobile no") && lower.contains("upi id") && lower.contains("beneficiary"))) {
                    Log.d(TAG, "WAIT_NUMBER: Ignoring send method menu text")
                    return
                }

                if (lower.contains("enter mobile") || lower.contains("00.bac") ||
                    (lower.contains("enter") && (lower.contains("mobile") || lower.contains("no."))) ||
                    (lower.contains("mobile") && !lower.contains("upi id"))) {

                    Log.d(TAG, "Entering UPI ID: $targetAccount")
                    sendStatus("entering_number", "📝 Entering UPI ID: $targetAccount", false)
                    service.sendInput(targetAccount)
                    state = State.WAIT_AMOUNT
                }
            }

            State.WAIT_AMOUNT -> {
                // SBI prompt: "Paying DEVANSH DEV SINGH , Enter Amount in Rs. or 00.Bac"
                if (lower.contains("amount") || lower.contains("rupee") ||
                    lower.contains("rs") || lower.contains("paying") ||
                    (lower.contains("enter") && !lower.contains("mobile") && !lower.contains("number"))) {

                    if (lower.contains("mobile") && !lower.contains("amount")) return

                    val nameMatch = Regex("paying\\s+(.+?)\\s*,", RegexOption.IGNORE_CASE).find(text)
                    val recipientName = nameMatch?.groupValues?.get(1) ?: ""

                    val msg = if (recipientName.isNotEmpty())
                        "💰 Paying $recipientName — ₹$targetAmount"
                    else
                        "💰 Entering amount: ₹$targetAmount"
                    sendStatus("entering_amount", msg, false)
                    service.sendInput(targetAmount)
                    state = State.WAIT_REMARK
                }
            }

            State.WAIT_REMARK -> {
                // SBI prompt: "Enter a remark (Enter 1 to skip)"
                when {
                    lower.contains("remark") || lower.contains("skip") ||
                    lower.contains("comment") || lower.contains("note") -> {
                        sendStatus("skipping_remark", "💬 Skipping remark (sending 1)...", false)
                        service.sendInput("1")
                        state = State.WAIT_PIN
                    }
                    lower.contains("pin") || lower.contains("mpin") -> {
                        state = State.WAIT_PIN
                        handlePinStep()
                    }
                    lower.contains("success") || lower.contains("completed") || lower.contains("approved") -> {
                        state = State.DONE
                        processUssdText(text, service)
                    }
                    else -> {
                        // Default: skip remark
                        sendStatus("skipping_remark", "💬 Skipping remark...", false)
                        service.sendInput("1")
                        state = State.WAIT_PIN
                    }
                }
            }

            State.WAIT_PIN -> {
                // IMPORTANT: Only show PIN prompt when we see the actual PIN dialog
                // This prevents the race condition where PIN gets typed into the remark box
                if (lower.contains("pin") || lower.contains("mpin") ||
                    lower.contains("proceed") || lower.contains("you are paying") ||
                    lower.contains("enter upi") || lower.contains("authenticate")) {
                    Log.d(TAG, "PIN dialog detected: ${lower.take(80)}")
                    sendStatus("pin_prompt", "🔒 Entering UPI PIN...", false)
                    handlePinStep()
                } else {
                    Log.d(TAG, "WAIT_PIN: Waiting for PIN dialog, ignoring: ${lower.take(60)}")
                }
            }

            State.DONE -> {
                when {
                    lower.contains("success") || lower.contains("completed") ||
                    lower.contains("approved") || lower.contains("sent") ||
                    lower.contains("credited") || lower.contains("debited") -> {
                        sendStatus("success", "✅ Payment successful!", true)
                        service.dismissDialog()
                        state = State.IDLE
                    }
                    lower.contains("fail") || lower.contains("error") ||
                    lower.contains("declined") || lower.contains("insufficient") ||
                    lower.contains("incorrect") || lower.contains("wrong") ||
                    lower.contains("invalid") -> {
                        sendStatus("error", "❌ Payment failed: $text", true)
                        service.dismissDialog()
                        state = State.IDLE
                    }
                    // Ignore stale text from previous USSD steps
                    lower.contains("pin") || lower.contains("remark") ||
                    lower.contains("skip") || lower.contains("amount") ||
                    lower.contains("mobile") || lower.contains("enter") ||
                    lower.contains("paying") || lower.contains("select") ||
                    lower.contains("money") -> {
                        Log.d(TAG, "DONE: Ignoring stale text from previous step")
                    }
                    else -> {
                        if (lower.contains("rs") || lower.contains("₹") || lower.contains("transaction")) {
                            sendStatus("success", "✅ $text", true)
                        } else {
                            sendStatus("response", "📝 $text", true)
                        }
                        service.dismissDialog()
                        state = State.IDLE
                    }
                }
            }

            else -> {}
        }
    }

    private fun handlePinStep() {
        if (pendingPin != null) {
            val svc = UssdAccessibilityService.instance
            if (svc != null) {
                sendStatus("pin_entering", "🔒 Entering UPI PIN...", false)
                svc.sendInput(pendingPin!!)
                pendingPin = null
                state = State.DONE
            }
        } else if (!waitingForPin) {
            // First time reaching PIN step - ask for PIN and BLOCK further processing
            waitingForPin = true
            sendStatus("enter_pin", "🔒 Enter your UPI PIN", false)
        }
        // If waitingForPin is already true, do nothing (blocked)
    }

    fun reset() {
        state = State.IDLE
        targetAccount = ""
        targetAmount = ""
        pendingPin = null
        waitingForPin = false
        lastProcessedText = ""
        lastProcessedTime = 0L
    }

    private fun sendStatus(status: String, message: String, isFinal: Boolean) {
        Log.d(TAG, "STATUS [$status]: $message")
        statusCallback?.invoke(status, message, isFinal)
    }
}
