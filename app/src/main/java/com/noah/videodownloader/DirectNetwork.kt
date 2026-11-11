package com.noah.videodownloader

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// 直连网络：让本 App 的流量绕过系统 VPN，走手机本身的网络。
// 原理是向系统要一条“非 VPN”的网络，找到后把整个进程绑到它上面；
// Python（Chaquopy）在同一进程里跑，yt-dlp 的请求也会走这条直连网络。
// 适用场景：VPN 开着时下国内视频——抖音等平台看到国外出口 IP 会要求登录，
// 直连后走国内 IP，一般就不用登录了。
// 注意：VPN 软件或系统开了“屏蔽未使用 VPN 的所有连接”时，系统不会提供
// 非 VPN 网络，绑定会失败，这时只能走 VPN（调用方会收到 false）。
// 绑定是进程级的，一直生效到主动解绑或进程退出；开关关闭时才解绑。
object DirectNetwork {

    private const val PREFS_NAME = "vd_prefs"
    private const val KEY_DIRECT_ENABLED = "direct_download"

    @Volatile
    private var boundNetwork: Network? = null

    @Volatile
    private var boundCallback: ConnectivityManager.NetworkCallback? = null

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DIRECT_ENABLED, true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DIRECT_ENABLED, enabled).apply()
        if (!enabled) {
            unbind(context)
        }
    }

    @Synchronized
    fun isBound(): Boolean = boundNetwork != null

    // 确保进程已绑到直连网络。已绑定直接返回 true；未绑定则等最多 timeoutMs，
    // 拿到网络就绑定并返回 true，超时或系统不提供则返回 false。
    // 只能在后台线程调用（会阻塞等待）。
    @Synchronized
    fun ensureBound(context: Context, timeoutMs: Long = 5000): Boolean {
        if (boundNetwork != null) return true
        val appContext = context.applicationContext
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val latch = CountDownLatch(1)
        var gotNetwork: Network? = null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                gotNetwork = network
                latch.countDown()
            }

            override fun onUnavailable() {
                latch.countDown()
            }
        }
        try {
            cm.requestNetwork(request, callback, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            return false
        }
        val ok = try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            false
        }
        val network = gotNetwork
        if (!ok || network == null) {
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                // 忽略
            }
            return false
        }
        val bound = try {
            cm.bindProcessToNetwork(network)
        } catch (e: Exception) {
            false
        }
        if (!bound) {
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                // 忽略
            }
            return false
        }
        boundNetwork = network
        boundCallback = callback
        return true
    }

    // 解绑并恢复走系统默认网络（也就是 VPN）。
    @Synchronized
    fun unbind(context: Context) {
        val cb = boundCallback
        boundNetwork = null
        boundCallback = null
        try {
            val cm = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.bindProcessToNetwork(null)
            if (cb != null) {
                cm.unregisterNetworkCallback(cb)
            }
        } catch (e: Exception) {
            // 忽略
        }
    }
}
