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
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (FlowStateMachine.state == FlowStateMachine.State.IDLE) return

        // Collect ALL text from the event
        val eventText = event.text.joinToString(" ").trim()

        // Also try to read from the active window nodes (more reliable)
        val windowText = extractAllTextFromWindow()

        val combinedText = "$eventText $windowText".trim()

        if (combinedText.isNotEmpty()) {
            Log.d(TAG, "Detected text: $combinedText")
            Log.d(TAG, "Current state: ${FlowStateMachine.state}")
            FlowStateMachine.processUssdText(combinedText, this)
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    /**
     * Extract all readable text from the current active window.
     * This helps capture USSD dialog content that event.text might miss.
     */
    private fun extractAllTextFromWindow(): String {
        val rootNode = rootInActiveWindow ?: return ""
        val texts = mutableListOf<String>()
        collectAllText(rootNode, texts)
        return texts.joinToString(" ")
    }

    private fun collectAllText(node: AccessibilityNodeInfo, texts: MutableList<String>) {
        val nodeText = node.text?.toString()
        if (!nodeText.isNullOrBlank()) {
            texts.add(nodeText)
        }
        val contentDesc = node.contentDescription?.toString()
        if (!contentDesc.isNullOrBlank()) {
            texts.add(contentDesc)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllText(child, texts)
        }
    }

    /**
     * Types a value into the USSD dialog's EditText and clicks Send/Reply.
     * Uses a small delay to ensure the UI is ready.
     */
    fun sendInput(value: String) {
        Log.d(TAG, "Sending input: $value")

        Handler(Looper.getMainLooper()).postDelayed({
            val rootNode = rootInActiveWindow
            if (rootNode == null) {
                Log.e(TAG, "rootInActiveWindow is null, cannot send input")
                return@postDelayed
            }

            val editText = findEditTextNode(rootNode)
            if (editText != null) {
                // Set the text
                val args = Bundle()
                args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value
                )
                editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                Log.d(TAG, "Text set: $value")

                // Wait a moment then click Send/Reply/OK button
                Handler(Looper.getMainLooper()).postDelayed({
                    val root = rootInActiveWindow ?: return@postDelayed
                    val clicked = clickButton(root, "Send") ||
                                  clickButton(root, "Reply") ||
                                  clickButton(root, "Ok") ||
                                  clickButton(root, "OK") ||
                                  clickButton(root, "Submit")
                    Log.d(TAG, "Button click result: $clicked")
                }, 300)
            } else {
                Log.e(TAG, "No EditText found in the window")
                // Try to click a numbered option directly (some USSD shows as list)
                val root = rootInActiveWindow ?: return@postDelayed
                clickButton(root, value) // Try clicking the value as button text
            }
        }, 200)
    }

    private fun findEditTextNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.className?.toString() == "android.widget.EditText") {
            return node
        }
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

        // Match buttons by text content
        if ((nodeClass.contains("Button") || node.isClickable) &&
            nodeText.contains(text, ignoreCase = true)) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "Clicked button: $nodeText")
            return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (clickButton(child, text)) return true
        }
        return false
    }
}
