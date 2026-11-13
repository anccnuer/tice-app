package com.example.tice.util

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    /** 取第一个非回环 IPv4 地址，用于展示电脑访问地址。 */
    fun lanIpAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .filter { address ->
                !address.isLoopbackAddress &&
                    address is Inet4Address &&
                    address.hostAddress?.startsWith("127.") != true
            }
            .mapNotNull { it.hostAddress }
            .firstOrNull()
    }.getOrNull()
}
