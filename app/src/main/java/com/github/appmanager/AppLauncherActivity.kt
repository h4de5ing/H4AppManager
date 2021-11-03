package com.github.appmanager

import android.os.Bundle
import android.util.Log
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.code19.library.AppUtils
import com.github.appmanager.adapter.AppAdapter
import com.github.appmanager.model.AppModel
import com.github.appmanager.ui.viewmodel.AppViewModel
import com.github.appmanager.utils.AppUtil2
import kotlinx.android.synthetic.main.activity_app_luncher.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class AppLauncherActivity : AppCompatActivity() {
    private val appList = mutableListOf<AppModel>()
    private val adapter = AppAdapter()
    private val viewModel: AppViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_luncher)
        app.setHasFixedSize(true)
        app.layoutManager = LinearLayoutManager(this)
        app.adapter = adapter
        viewModel.appList.observe(this, {
            adapter.setNewInstance(it.toMutableList())
            adapter.notifyDataSetChanged()
        })
        adapter.setOnItemClickListener { _, _, position ->
            AppUtils.runApp(
                this,
                adapter.getItem(position).appPack
            )
        }
        loadData()
    }

    private fun loadData() {
        GlobalScope.launch {
            appList.addAll(AppUtil2.getInstallApp(this@AppLauncherActivity))
            Log.i("gh0st", "加载结束:${appList.size}")
            viewModel.appList.postValue(appList)
        }
    }
}