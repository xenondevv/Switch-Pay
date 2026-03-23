package com.example.offline_payment_app.engine

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class UssdAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "UssdAccessibility"
        var instance: UssdAccessibilityService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastEventText = ""
    private var lastEventTime = 0L
    private var isProcessing = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "AccessibilityService CONNECTED")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "AccessibilityService DESTROYED")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (FlowStateMachine.state == FlowStateMachine.State.IDLE) return
        if (isProcessing) return

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        // Collect text from event
        val eventText = event.text.joinToString(" ").trim()

        // Also try reading from the window
        val windowText = extractAllTextFromWindow()
        val combinedText = "$eventText $windowText".trim()

        if (combinedText.length < 5) return

        // Deduplicate events (same text within 1.5 seconds)
        val now = System.currentTimeMillis()
        val textKey = combinedText.take(80)
        if (textKey == lastEventText && (now - lastEventTime) < 1500) return
        lastEventText = textKey
        lastEventTime = now

        Log.d(TAG, "Event [state=${FlowStateMachine.state}]: ${combinedText.take(100)}")

        // Delay to let dialogs fully render before interacting
        isProcessing = true
        handler.postDelayed({
            try {
                FlowStateMachine.processUssdText(combinedText, this)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing USSD: ${e.message}")
            }
            isProcessing = false
        }, 800)
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    // ========================
    // USSD Dialog Interaction
    // ========================

    /**
     * Types a value into the USSD dialog's EditText and clicks Send/Reply.
     * Uses longer delays for reliability.
     */
    fun sendInput(value: String) {
        Log.d(TAG, ">>> sendInput: '$value'")

        handler.postDelayed({
            val rootNode = rootInActiveWindow
            if (rootNode == null) {
                Log.e(TAG, "rootInActiveWindow is null — retrying in 500ms")
                handler.postDelayed({ retrySendInput(value) }, 500)
                return@postDelayed
            }

            val editText = findEditTextNode(rootNode)
            if (editText != null) {
                // Clear existing text and set new value
                val args = Bundle()
                args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value
                )
                val setResult = editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                Log.d(TAG, "Text set '$value': $setResult")

                // Wait for text to be set, then click Send
                handler.postDelayed({
                    clickSendButton()
                }, 700)
            } else {
                Log.e(TAG, "No EditText found — retrying in 500ms")
                handler.postDelayed({ retrySendInput(value) }, 500)
            }
        }, 400)
    }

    private fun retrySendInput(value: String) {
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            Log.e(TAG, "Retry: rootInActiveWindow still null")
            return
        }
        val editText = findEditTextNode(rootNode)
        if (editText != null) {
            val args = Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value
            )
            editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.d(TAG, "Retry: Text set '$value'")
            handler.postDelayed({ clickSendButton() }, 700)
        } else {
            Log.e(TAG, "Retry: No EditText found")
        }
    }

    private fun clickSendButton() {
        val root = rootInActiveWindow ?: return
        val clicked = clickButton(root, "Send") ||
                      clickButton(root, "Reply") ||
                      clickButton(root, "Ok") ||
                      clickButton(root, "OK") ||
                      clickButton(root, "Submit") ||
                      clickButton(root, "SEND") ||
                      clickButton(root, "send")
        Log.d(TAG, "Send button clicked: $clicked")
        if (!clicked) {
            // Try clicking by position — some devices have non-standard USSD dialogs
            Log.w(TAG, "Could not find Send button by text, trying alternatives")
            clickAnyButton(root)
        }
    }

    /**
     * Dismiss the USSD dialog by clicking Cancel/OK/Close.
     */
    fun dismissDialog() {
        handler.postDelayed({
            val root = rootInActiveWindow ?: return@postDelayed
            val dismissed = clickButton(root, "Cancel") ||
                            clickButton(root, "OK") ||
                            clickButton(root, "Ok") ||
                            clickButton(root, "Close") ||
                            clickButton(root, "Dismiss")
            Log.d(TAG, "Dismiss dialog: $dismissed")
        }, 500)
    }

    // ========================
    // Text Extraction
    // ========================

    private fun extractAllTextFromWindow(): String {
        val rootNode = rootInActiveWindow ?: return ""
        val texts = mutableListOf<String>()
        collectAllText(rootNode, texts)
        return texts.joinToString(" ")
    }

    private fun collectAllText(node: AccessibilityNodeInfo, texts: MutableList<String>) {
        val nodeText = node.text?.toString()
        if (!nodeText.isNullOrBlank()) texts.add(nodeText)
        val contentDesc = node.contentDescription?.toString()
        if (!contentDesc.isNullOrBlank()) texts.add(contentDesc)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllText(child, texts)
        }
    }

    // ========================
    // Node Finders
    // ========================

    private fun findEditTextNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val className = node.className?.toString() ?: ""
        if (className == "android.widget.EditText" || className.contains("EditText")) {
            return node
        }
        // Also check if it's editable even if class name differs
        if (node.isEditable) return node

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditTextNode(child)
            if (result != null) return result
        }
        return null
    }

    private fun clickButton(node: AccessibilityNodeInfo, text: String): Boolean {
        val nodeText = node.text?.toString() ?: ""
        val nodeClass = node.className?.toString() ?: ""

        if ((nodeClass.contains("Button") || node.isClickable) &&
            nodeText.equals(text, ignoreCase = true)) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "Clicked: '$nodeText'")
            return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (clickButton(child, text)) return true
        }
        return false
    }

    /**
     * Fallback: click the first clickable button that looks like an action button
     */
    private fun clickAnyButton(node: AccessibilityNodeInfo): Boolean {
        val nodeClass = node.className?.toString() ?: ""
        val nodeText = node.text?.toString() ?: ""

        if (nodeClass.contains("Button") && node.isClickable &&
            !nodeText.equals("Cancel", ignoreCase = true)) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "Fallback clicked: '$nodeText'")
            return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (clickAnyButton(child)) return true
        }
        return false
    }
}
