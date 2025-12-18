package com.github.appmanager

import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.github.appmanager.databinding.ActivityMainBinding
import com.github.appmanager.model.AppModel
import com.github.appmanager.ui.base.BaseSearchActivity
import com.github.appmanager.ui.fragment.SystemAppFragment
import com.github.appmanager.ui.fragment.UserAppFragment
import com.github.appmanager.ui.viewmodel.AppViewModel
import com.github.appmanager.utils.AppUtil2
import com.github.appmanager.utils.StoragePermissionHelper
import com.github.appmanager.utils.ViewUtils
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class MainActivity : BaseSearchActivity() {
    private var titles = mutableListOf("用户应用", "系统应用")
    private val pages = listOf(UserAppFragment(), SystemAppFragment())
    private val appViewModel: AppViewModel by viewModels()
    private val appList = mutableListOf<AppModel>()
    private lateinit var binding: ActivityMainBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(!isTaskRoot)

        titles.forEach {
            binding.contentMain.mainTabLayout.addTab(
                binding.contentMain.mainTabLayout.newTab().setText(it)
            )
        }
        val adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = pages.size

            override fun createFragment(position: Int): Fragment = pages[position]
        }

        binding.contentMain.mainViewPager2.adapter = adapter
        binding.contentMain.mainViewPager2.offscreenPageLimit = 2
        TabLayoutMediator(
            binding.contentMain.mainTabLayout, binding.contentMain.mainViewPager2
        ) { tab, position ->
            tab.text = titles[position]
        }.attach()
        onClickItem.observe(this, {
            ViewUtils.appInfoDialog(
                this, appList.firstOrNull { item -> item.appPack == it })
        })
        checkAndRequestPermissions()
        loadData()
    }

    private fun loadData() {
        GlobalScope.launch {
            appList.addAll(AppUtil2.getInstallApp(this@MainActivity))
            Log.i("gh0st", "加载结束:${appList.size}")
            appViewModel.appList.postValue(appList)
            appList.forEach {
                allDataList.add(Triple(it.appName, "$it", it.appPack))
            }
        }
    }

    private fun checkAndRequestPermissions() {
        if (!StoragePermissionHelper.hasStoragePermission(this)) {
            StoragePermissionHelper.requestStoragePermission(this)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_apk -> ViewUtils.openAPk(this)
            R.id.action_open -> ViewUtils.openDialog(this)
            R.id.action_about -> ViewUtils.aboutDialog(this)
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        StoragePermissionHelper.onRequestPermissionsResult(requestCode, grantResults)
    }
}