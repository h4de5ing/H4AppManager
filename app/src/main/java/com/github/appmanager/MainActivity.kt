package com.github.appmanager

import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.github.appmanager.model.AppModel
import com.github.appmanager.ui.base.BaseSearchActivity
import com.github.appmanager.ui.fragment.SystemAppFragment
import com.github.appmanager.ui.fragment.UserAppFragment
import com.github.appmanager.ui.viewmodel.AppViewModel
import com.github.appmanager.utils.AppUtil2
import com.github.appmanager.utils.ViewUtils
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.content_main.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class MainActivity : BaseSearchActivity() {
    private var titles = mutableListOf("用户应用", "系统应用")
    private val pages = listOf(UserAppFragment(), SystemAppFragment())
    private val appViewModel: AppViewModel by viewModels()
    private val appList = mutableListOf<AppModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)
        supportActionBar!!.setDisplayHomeAsUpEnabled(!isTaskRoot)
        titles.forEach { main_tabLayout.addTab(main_tabLayout.newTab().setText(it)) }
        val adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = pages.size

            override fun createFragment(position: Int): Fragment = pages[position]
        }
        main_viewPager2.adapter = adapter
        main_viewPager2.offscreenPageLimit = 2
        TabLayoutMediator(main_tabLayout, main_viewPager2) { tab, position ->
            tab.text = titles[position]
        }.attach()
        onClickItem.observe(this, {
            ViewUtils.appInfoDialog(
                this,
                appList.firstOrNull { item -> item.appPack == it })
        })
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_apk -> ViewUtils.openAPk(this)
            R.id.action_open -> ViewUtils.openDialog(this)
            R.id.action_about -> ViewUtils.aboutDialog(this)
        }
        return super.onOptionsItemSelected(item)
    }
}