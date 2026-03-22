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

        // Static instance so MainActivity can call tapDialpadKey()
        var instance: UssdAccessibilityService? = null
            private set

        /**
         * Tap a single key on the in-call dialpad.
         * Returns true if the button was found and clicked.
         */
        fun tapDialpadKey(digit: Char): Boolean {
            val svc = instance
            if (svc == null) {
                Log.e(TAG, "Service not connected! Cannot tap key '$digit'")
                return false
            }
            return svc.tapKeyInDialer(digit)
        }

        /**
         * Open the dialpad/keypad on the in-call screen.
         * Returns true if found and tapped.
         */
        fun openDialpad(): Boolean {
            val svc = instance
            if (svc == null) {
                Log.e(TAG, "Service not connected! Cannot open dialpad")
                return false
            }
            return svc.findAndOpenDialpad()
        }

        /**
         * Dump the entire UI tree for debugging (helps find button labels).
         */
        fun dumpWindowTree() {
            val svc = instance
            if (svc == null) {
                Log.e(TAG, "Service not connected! Cannot dump tree")
                return
            }
            svc.dumpAllWindows()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "AccessibilityService CONNECTED — instance ready")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "AccessibilityService DESTROYED")
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

    // ========================
    // DIALPAD KEY TAPPING (for IVR DTMF)
    // ========================

    /**
     * Finds and taps a digit key on the phone's in-call dialpad.
     * Searches all accessible windows for a clickable element matching the digit.
     */
    private fun tapKeyInDialer(digit: Char): Boolean {
        Log.d(TAG, ">>> Trying to tap key: '$digit'")

        // Try all windows (dialer might not be the "active" window)
        try {
            val allWindows = windows
            for (window in allWindows) {
                val root = window.root ?: continue
                Log.d(TAG, "Searching window: ${window.title} (type=${window.type})")

                if (findAndClickDigit(root, digit)) {
                    Log.d(TAG, "✅ Successfully tapped '$digit'")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error accessing windows: ${e.message}")
        }

        // Fallback: try rootInActiveWindow
        val root = rootInActiveWindow
        if (root != null) {
            Log.d(TAG, "Trying rootInActiveWindow as fallback...")
            if (findAndClickDigit(root, digit)) {
                Log.d(TAG, "✅ Successfully tapped '$digit' via rootInActiveWindow")
                return true
            }
        }

        Log.e(TAG, "❌ Could not find key '$digit' in any window!")
        return false
    }

    /**
     * Find and click a digit button in the UI tree.
     * Tries multiple matching strategies for different dialer UIs.
     */
    private fun findAndClickDigit(root: AccessibilityNodeInfo, digit: Char): Boolean {
        val digitStr = digit.toString()

        // Strategy 1: findAccessibilityNodeInfosByText (fastest)
        val candidates = root.findAccessibilityNodeInfosByText(digitStr)
        for (node in candidates) {
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val cls = node.className?.toString() ?: ""

            // For '#' key, look for specific labels
            if (digit == '#') {
                if (node.isClickable && (text == "#" || text == "pound" ||
                    desc.contains("pound", true) || desc.contains("hash", true) || desc == "#")) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "Clicked # key: text='$text' desc='$desc'")
                    return true
                }
            }
            // For '*' key
            else if (digit == '*') {
                if (node.isClickable && (text == "*" || text == "star" ||
                    desc.contains("star", true) || desc.contains("asterisk", true) || desc == "*")) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "Clicked * key: text='$text' desc='$desc'")
                    return true
                }
            }
            // For digit keys (0-9): match exact digit in text or description
            else {
                // Must be clickable AND match the digit precisely
                if (node.isClickable && (text == digitStr || text.startsWith(digitStr) ||
                    desc == digitStr || desc.startsWith(digitStr))) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "Clicked digit '$digit': text='$text' desc='$desc' class='$cls'")
                    return true
                }
            }
        }

        // Strategy 2: Deep search — look for any clickable node with matching content
        return deepFindAndClickDigit(root, digit)
    }

    /**
     * Deep recursive search for a digit button.
     */
    private fun deepFindAndClickDigit(node: AccessibilityNodeInfo, digit: Char): Boolean {
        val digitStr = digit.toString()
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""

        // Check if this node matches
        val textMatch = when (digit) {
            '#' -> text == "#" || desc.contains("pound", true) || desc.contains("hash", true) || desc == "#"
            '*' -> text == "*" || desc.contains("star", true) || desc == "*"
            else -> text == digitStr || desc == digitStr
        }

        if (node.isClickable && textMatch) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "Deep-found & clicked '$digit': text='$text' desc='$desc'")
            return true
        }

        // Recurse into children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (deepFindAndClickDigit(child, digit)) return true
        }
        return false
    }

    /**
     * Find and tap the "Keypad" / "Dialpad" button to reveal digit buttons.
     */
    private fun findAndOpenDialpad(): Boolean {
        Log.d(TAG, ">>> Trying to open dialpad...")

        val keypadLabels = listOf("Keypad", "Dialpad", "keypad", "dialpad", "Key pad", "Dial pad")

        try {
            val allWindows = windows
            for (window in allWindows) {
                val root = window.root ?: continue
                for (label in keypadLabels) {
                    val nodes = root.findAccessibilityNodeInfosByText(label)
                    for (node in nodes) {
                        if (node.isClickable) {
                            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            Log.d(TAG, "✅ Opened dialpad via: '${node.text ?: node.contentDescription}'")
                            return true
                        }
                        // Try clicking the parent if the node itself isn't clickable
                        val parent = node.parent
                        if (parent != null && parent.isClickable) {
                            parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            Log.d(TAG, "✅ Opened dialpad via parent of: '${node.text ?: node.contentDescription}'")
                            return true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening dialpad: ${e.message}")
        }

        Log.e(TAG, "❌ Could not find dialpad button")
        return false
    }

    /**
     * Dump the UI tree of all windows for debugging.
     * Helps identify the correct button labels/descriptions on different phones.
     */
    private fun dumpAllWindows() {
        Log.d(TAG, "=== DUMPING ALL WINDOWS ===")
        try {
            val allWindows = windows
            for (window in allWindows) {
                Log.d(TAG, "Window: title='${window.title}' type=${window.type} layer=${window.layer}")
                val root = window.root ?: continue
                dumpNode(root, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error dumping windows: ${e.message}")
        }
        Log.d(TAG, "=== END DUMP ===")
    }

    private fun dumpNode(node: AccessibilityNodeInfo, depth: Int) {
        val indent = "  ".repeat(depth)
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val cls = node.className?.toString()?.substringAfterLast('.') ?: ""
        val clickable = if (node.isClickable) "CLICK" else ""
        if (text.isNotEmpty() || desc.isNotEmpty() || node.isClickable) {
            Log.d(TAG, "${indent}[$cls] text='$text' desc='$desc' $clickable")
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dumpNode(child, depth + 1)
        }
    }

    // ========================
    // USSD (original functionality)
    // ========================

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
                val args = Bundle()
                args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value
                )
                editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                Log.d(TAG, "Text set: $value")

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
                val root = rootInActiveWindow ?: return@postDelayed
                clickButton(root, value)
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
