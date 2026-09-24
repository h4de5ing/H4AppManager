package com.github.appmanager

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

/** 本机在局域网中的访问地址。 */
object LanAddress {
    private const val TAG = "LanAddress"
    const val LOOPBACK = "127.0.0.1"

    fun localIp(context: Context): String {
        try {
            val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            val ipInt = wifiManager?.connectionInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                return InetAddress.getByAddress(
                    byteArrayOf(
                        ipInt.toByte(),
                        (ipInt shr 8).toByte(),
                        (ipInt shr 16).toByte(),
                        (ipInt shr 24).toByte()
                    )
                ).hostAddress ?: LOOPBACK
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get WiFi IP", e)
        }

        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (nic in interfaces) {
                for (address in Collections.list(nic.inetAddresses)) {
                    if (address.isLoopbackAddress) continue
                    val ip = address.hostAddress ?: continue
                    if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                        return ip
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get network IP", e)
        }

        return LOOPBACK
    }

    fun baseUrl(context: Context): String =
        "http://${localIp(context)}:${HttpFileServerService.serverPort}"
}
