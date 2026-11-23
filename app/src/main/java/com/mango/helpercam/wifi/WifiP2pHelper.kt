package com.mango.helpercam.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pManager

@SuppressLint("MissingPermission")
class WifiP2pHelper(
    private val context: Context,
    private val onConnectionInfoAvailable: (String, Boolean) -> Unit
) {
    private val manager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel: WifiP2pManager.Channel? = manager?.initialize(context, context.mainLooper, null)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION == intent.action) {
                manager?.requestConnectionInfo(channel) { info ->
                    if (info.groupFormed) {
                        val isOwner = info.isGroupOwner
                        val ip = if (isOwner) "SERVER" else (info.groupOwnerAddress?.hostAddress ?: "")
                        onConnectionInfoAvailable(ip, isOwner)
                    }
                }
            }
        }
    }

    fun startGroup() {
        manager?.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {}
        })
    }

    fun register() {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        context.registerReceiver(receiver, filter)
    }

    fun unregister() {
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
    }
}