package com.example.offline_payment_app.engine

import android.telecom.Call
import android.telecom.InCallService
import android.util.Log

class PaymentInCallService : InCallService() {
    companion object {
        var activeCall: Call? = null
        var isServiceBound: Boolean = false
        var callback: ((Call) -> Unit)? = null
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        Log.d("PaymentInCall", "Call added: $call")
        activeCall = call
        callback?.invoke(call)
        
        call.registerCallback(object : Call.Callback() {
            override fun onStateChanged(c: Call, state: Int) {
                Log.d("PaymentInCall", "Call state changed to: $state")
                if (state == Call.STATE_DISCONNECTED) {
                    activeCall = null
                }
            }
        })
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Log.d("PaymentInCall", "Call removed: $call")
        if (activeCall == call) {
            activeCall = null
        }
    }

    override fun onBind(intent: android.content.Intent?): android.os.IBinder? {
        Log.d("PaymentInCall", "Service Bound")
        isServiceBound = true
        return super.onBind(intent)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Log.d("PaymentInCall", "Service Unbound")
        isServiceBound = false
        activeCall = null
        return super.onUnbind(intent)
    }
}
