package com.github.appmanager

import android.os.Bundle
import android.util.Log
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.github.appmanager.ui.fragment.SystemAppFragment
import com.github.appmanager.ui.fragment.UserAppFragment
import com.github.appmanager.ui.viewmodel.AppViewModel
import com.github.appmanager.utils.AppUtil2
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.content_main.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private var titles = mutableListOf("用户应用", "系统应用")
    private val pages = listOf(UserAppFragment(), SystemAppFragment())
    private val appViewModel: AppViewModel by viewModels()
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
        TabLayoutMediator(
            main_tabLayout,
            main_viewPager2,
            TabLayoutMediator.TabConfigurationStrategy { tab, position ->
                tab.text = titles[position]
            }).attach()
        loadData()
    }

    private fun loadData() {
        GlobalScope.launch {
            val appList = AppUtil2.getInstallApp(this@MainActivity)
            Log.i("gh0st", "加载结束:${appList.size}")
            appViewModel.appList.postValue(appList)
        }
    }
}