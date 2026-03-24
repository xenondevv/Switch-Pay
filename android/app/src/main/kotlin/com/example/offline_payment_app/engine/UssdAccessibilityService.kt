package com.example.offline_payment_app.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

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

        // Deduplicate events (same text within 800ms — fast to reduce dialog flash)
        val now = System.currentTimeMillis()
        val textKey = combinedText.take(80)
        if (textKey == lastEventText && (now - lastEventTime) < 800) return
        lastEventText = textKey
        lastEventTime = now

        Log.d(TAG, "Event [state=${FlowStateMachine.state}]: ${combinedText.take(100)}")

        // Delay to let dialogs render before interacting (reduced to minimize flash)
        isProcessing = true
        handler.postDelayed({
            try {
                FlowStateMachine.processUssdText(combinedText, this)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing USSD: ${e.message}")
            }
            isProcessing = false
        }, 200)
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    // ========================
    // USSD Dialog Interaction
    // ========================

    /**
     * Types a value into the USSD dialog's EditText and clicks Send/Reply.
     * Searches ALL windows (not just rootInActiveWindow) to find the USSD EditText
     * even when overlay is covering it.
     */
    fun sendInput(value: String) {
        Log.d(TAG, ">>> sendInput: '$value'")
        attemptSendInput(value, 0)
    }

    private fun attemptSendInput(value: String, attempt: Int) {
        if (attempt >= 5) {
            Log.e(TAG, "sendInput FAILED after 5 attempts for '$value'")
            return
        }

        val delay = if (attempt == 0) 300L else 500L
        handler.postDelayed({
            val editText = findEditTextInAllWindows()
            if (editText != null) {
                val args = Bundle()
                args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value
                )
                val setResult = editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                Log.d(TAG, "Text set '$value' (attempt $attempt): $setResult")

                // Wait for text to be set, then click Send
                handler.postDelayed({
                    clickSendButton()
                }, 500)
            } else {
                Log.w(TAG, "No EditText found (attempt $attempt) — retrying...")
                attemptSendInput(value, attempt + 1)
            }
        }, delay)
    }

    /**
     * Search ALL accessibility windows for an EditText node.
     * This is critical when our overlay covers the USSD dialog,
     * since rootInActiveWindow may return the overlay instead.
     */
    private fun findEditTextInAllWindows(): AccessibilityNodeInfo? {
        // First try rootInActiveWindow (fastest path)
        val activeRoot = rootInActiveWindow
        if (activeRoot != null) {
            val found = findEditTextNode(activeRoot)
            if (found != null) {
                Log.d(TAG, "Found EditText in active window")
                return found
            }
        }

        // Search all windows — the USSD dialog might not be the active one
        try {
            val windowList = windows
            if (windowList != null) {
                for (window in windowList) {
                    val root = window.root ?: continue
                    val found = findEditTextNode(root)
                    if (found != null) {
                        Log.d(TAG, "Found EditText in window: ${window.type}")
                        return found
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error searching windows: ${e.message}")
        }

        return null
    }

    private fun clickSendButton() {
        // Search all windows for the Send button too
        val roots = getAllWindowRoots()
        var clicked = false
        for (root in roots) {
            clicked = clickButton(root, "Send") ||
                      clickButton(root, "Reply") ||
                      clickButton(root, "Ok") ||
                      clickButton(root, "OK") ||
                      clickButton(root, "Submit") ||
                      clickButton(root, "SEND") ||
                      clickButton(root, "send")
            if (clicked) break
        }
        Log.d(TAG, "Send button clicked: $clicked")
        if (!clicked) {
            for (root in roots) {
                if (clickAnyButton(root)) break
            }
        }
    }

    /**
     * Get root nodes from ALL windows for multi-window search.
     */
    private fun getAllWindowRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        val activeRoot = rootInActiveWindow
        if (activeRoot != null) roots.add(activeRoot)

        try {
            val windowList = windows
            if (windowList != null) {
                for (window in windowList) {
                    val root = window.root ?: continue
                    if (root != activeRoot) roots.add(root)
                }
            }
        } catch (_: Exception) {}

        return roots
    }

    /**
     * Click the OK button on an info-only USSD dialog (no EditText, no input needed).
     * Used for welcome screens, confirmation messages, etc.
     */
    fun clickOkButton() {
        Log.d(TAG, ">>> clickOkButton (info-only dialog)")
        handler.postDelayed({
            val root = rootInActiveWindow
            if (root == null) {
                Log.e(TAG, "clickOkButton: rootInActiveWindow is null — retrying in 400ms")
                handler.postDelayed({ retryClickOkButton() }, 400)
                return@postDelayed
            }
            val clicked = clickButton(root, "OK") ||
                          clickButton(root, "Ok") ||
                          clickButton(root, "ok") ||
                          clickButton(root, "Send") ||
                          clickButton(root, "Reply") ||
                          clickButton(root, "Next")
            Log.d(TAG, "OK button clicked: $clicked")
            if (!clicked) {
                Log.w(TAG, "Could not find OK button, trying any button")
                clickAnyButton(root)
            }
        }, 300)
    }

    private fun retryClickOkButton() {
        val root = rootInActiveWindow
        if (root == null) {
            Log.e(TAG, "retryClickOkButton: rootInActiveWindow still null")
            return
        }
        val clicked = clickButton(root, "OK") ||
                      clickButton(root, "Ok") ||
                      clickButton(root, "ok") ||
                      clickButton(root, "Send") ||
                      clickAnyButton(root)
        Log.d(TAG, "Retry OK button clicked: $clicked")
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
        }, 400)
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
