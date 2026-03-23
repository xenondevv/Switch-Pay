package com.example.offline_payment_app.engine

import android.util.Log

/**
 * State machine for *99# USSD flow.
 *
 * Flow: IDLE → WAIT_BANK_MENU → WAIT_SEND_METHOD → WAIT_NUMBER → WAIT_AMOUNT → WAIT_REMARK → WAIT_PIN → DONE
 *
 * Always selects option 1 (Mobile No.) because it accepts both
 * phone numbers AND UPI IDs.
 */
object FlowStateMachine {
    private const val TAG = "FlowStateMachine"

    enum class State {
        IDLE,
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
        state = State.WAIT_BANK_MENU
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
        if (textKey == lastProcessedText && (now - lastProcessedTime) < 2000) {
            Log.d(TAG, "Skipping duplicate text")
            return
        }
        lastProcessedText = textKey
        lastProcessedTime = now

        Log.d(TAG, "Processing [state=$state]: ${lower.take(120)}")

        when (state) {
            State.WAIT_BANK_MENU -> {
                if (lower.contains("send") || lower.contains("money") ||
                    lower.contains("transfer") || lower.contains("fund") ||
                    lower.contains("select") || lower.contains("option")) {

                    Log.d(TAG, "Bank menu → Selecting '1' (Send Money)")
                    sendStatus("bank_menu", "📋 Bank menu → Selecting 'Send Money'", false)
                    service.sendInput("1")
                    state = State.WAIT_SEND_METHOD
                }
            }

            State.WAIT_SEND_METHOD -> {
                if (lower.contains("mobile") || lower.contains("upi") ||
                    lower.contains("send money") || lower.contains("beneficiary") ||
                    lower.contains("send") || lower.contains("money to")) {

                    Log.d(TAG, "Send method → Selecting '1' (Mobile No.)")
                    sendStatus("select_method", "📋 Selecting 'Mobile No.' (option 1)", false)
                    service.sendInput("1")
                    state = State.WAIT_NUMBER
                } else if (lower.contains("enter") && (lower.contains("number") || lower.contains("mobile") || lower.contains("id"))) {
                    // Already at number entry (skipped method selection)
                    Log.d(TAG, "Direct number entry prompt")
                    sendStatus("entering_number", "📝 Entering: $targetAccount", false)
                    service.sendInput(targetAccount)
                    state = State.WAIT_AMOUNT
                } else {
                    // Fallback: send 1 for any unrecognized menu
                    Log.d(TAG, "Unrecognized send method menu, defaulting to '1'")
                    service.sendInput("1")
                    state = State.WAIT_NUMBER
                }
            }

            State.WAIT_NUMBER -> {
                // Enter target (phone number or UPI ID) when any input prompt appears
                if (lower.contains("mobile") || lower.contains("number") ||
                    lower.contains("upi") || lower.contains("enter") ||
                    lower.contains("beneficiary") || lower.contains("payee") ||
                    lower.contains("id") || lower.contains("no.") ||
                    lower.contains("input") || lower.length > 5) {

                    Log.d(TAG, "Entering target: $targetAccount")
                    sendStatus("entering_number", "📝 Entering: $targetAccount", false)
                    service.sendInput(targetAccount)
                    state = State.WAIT_AMOUNT
                }
            }

            State.WAIT_AMOUNT -> {
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
                when {
                    lower.contains("remark") || lower.contains("skip") ||
                    lower.contains("comment") || lower.contains("note") -> {
                        sendStatus("skipping_remark", "💬 Skipping remark...", false)
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
                        service.sendInput("1")
                        state = State.WAIT_PIN
                    }
                }
            }

            State.WAIT_PIN -> {
                handlePinStep()
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
                    lower.contains("pin") -> {
                        // Still at PIN step
                        state = State.WAIT_PIN
                        handlePinStep()
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
