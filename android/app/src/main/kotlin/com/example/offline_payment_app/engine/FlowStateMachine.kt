package com.example.offline_payment_app.engine

import android.util.Log

object FlowStateMachine {
    private const val TAG = "FlowStateMachine"

    enum class State {
        IDLE,
        WAIT_MAIN_MENU,
        WAIT_SEND_OPTION,
        WAIT_NUMBER,
        WAIT_AMOUNT,
        WAIT_PIN,
        DONE
    }
    
    var state = State.IDLE
    var targetAccount = ""
    var targetAmount = ""
    var isBalanceCheck = false

    fun startNewTransaction(target: String, amount: String) {
        targetAccount = target
        targetAmount = amount
        isBalanceCheck = (target == "BALANCE")
        state = State.WAIT_MAIN_MENU
        Log.d(TAG, "New transaction: target=$target, amount=$amount, balanceCheck=$isBalanceCheck")
    }

    fun processUssdText(text: String, service: UssdAccessibilityService) {
        val lower = text.lowercase()
        Log.d(TAG, "Processing [state=$state]: $lower")

        when (state) {
            State.WAIT_MAIN_MENU -> {
                // *99# main menu — look for key options
                when {
                    isBalanceCheck && (lower.contains("balance") || lower.contains("enquiry")) -> {
                        // Typically option 3 or 4 for balance
                        val option = findOptionNumber(lower, listOf("balance", "enquiry"))
                        Log.d(TAG, "Selecting balance option: $option")
                        service.sendInput(option)
                        state = State.WAIT_PIN
                    }
                    !isBalanceCheck && (lower.contains("send") || lower.contains("money") || 
                        lower.contains("transfer") || lower.contains("fund")) -> {
                        // Typically option 1 for Send Money / Fund Transfer
                        val option = findOptionNumber(lower, listOf("send", "money", "transfer", "fund"))
                        Log.d(TAG, "Selecting send money option: $option")
                        service.sendInput(option)
                        state = State.WAIT_SEND_OPTION
                    }
                    lower.contains("1") && lower.contains("2") -> {
                        // Generic menu detected, default to option 1
                        service.sendInput("1")
                        state = if (isBalanceCheck) State.WAIT_PIN else State.WAIT_SEND_OPTION
                    }
                }
            }

            State.WAIT_SEND_OPTION -> {
                // Some banks have sub-menu: "Send via mobile" / "Send via UPI"
                when {
                    lower.contains("mobile") || lower.contains("phone") || lower.contains("mmid") -> {
                        val option = findOptionNumber(lower, listOf("mobile", "phone"))
                        service.sendInput(option)
                        state = State.WAIT_NUMBER
                    }
                    lower.contains("upi") || lower.contains("virtual") -> {
                        val option = findOptionNumber(lower, listOf("upi", "virtual"))
                        service.sendInput(option)
                        state = State.WAIT_NUMBER
                    }
                    lower.contains("enter") && (lower.contains("number") || lower.contains("id")) -> {
                        // Directly asking for number — no sub-menu
                        service.sendInput(targetAccount)
                        state = State.WAIT_AMOUNT
                    }
                    else -> {
                        // Default: select option 1
                        service.sendInput("1")
                        state = State.WAIT_NUMBER
                    }
                }
            }

            State.WAIT_NUMBER -> {
                when {
                    lower.contains("mobile") || lower.contains("number") || 
                    lower.contains("upi") || lower.contains("enter") || 
                    lower.contains("beneficiary") || lower.contains("payee") -> {
                        Log.d(TAG, "Entering target account: $targetAccount")
                        service.sendInput(targetAccount)
                        state = State.WAIT_AMOUNT
                    }
                }
            }

            State.WAIT_AMOUNT -> {
                when {
                    lower.contains("amount") || lower.contains("rupee") || 
                    lower.contains("rs") || lower.contains("enter") -> {
                        Log.d(TAG, "Entering amount: $targetAmount")
                        service.sendInput(targetAmount)
                        state = State.WAIT_PIN
                    }
                }
            }

            State.WAIT_PIN -> {
                // We NEVER automate PIN entry. User must enter manually.
                Log.d(TAG, "PIN step reached — waiting for manual user input")
                state = State.DONE
            }

            State.DONE -> {
                // Check for success / failure messages
                when {
                    lower.contains("success") || lower.contains("completed") || 
                    lower.contains("approved") -> {
                        Log.d(TAG, "Transaction SUCCESS detected!")
                        state = State.IDLE
                    }
                    lower.contains("fail") || lower.contains("error") || 
                    lower.contains("declined") || lower.contains("insufficient") -> {
                        Log.d(TAG, "Transaction FAILED detected!")
                        state = State.IDLE
                    }
                }
            }

            else -> {}
        }
    }

    /**
     * Attempt to find the menu option number for a keyword.
     * Looks for patterns like "1. Send Money" or "1) Balance Enquiry"
     * Returns the number or "1" as default.
     */
    private fun findOptionNumber(text: String, keywords: List<String>): String {
        for (keyword in keywords) {
            // Match: "1. send" or "1) send" or "1 send" or "1.send"
            val regex = Regex("""(\d+)\s*[.):\-]?\s*[^0-9]*$keyword""", RegexOption.IGNORE_CASE)
            val match = regex.find(text)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return "1" // default
    }
}
