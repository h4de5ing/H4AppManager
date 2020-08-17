package com.github.appmanager.ui.fragment

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.appmanager.R
import com.github.appmanager.adapter.AppAdapter
import com.github.appmanager.utils.AppUtil2
import com.github.appmanager.utils.ViewUtils
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class OtherFragment : Fragment() {


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_other, container, false)
        initView(view)
        return view
    }

    private val adapter = AppAdapter()
    private fun initView(view: View) {
        val recyclerview = view.findViewById<RecyclerView>(R.id.recyclerview)
        recyclerview.setHasFixedSize(true)
        recyclerview.layoutManager = LinearLayoutManager(activity)
        recyclerview.adapter = adapter
        loadData()
    }

    private fun loadData() {
        GlobalScope.launch {
            val appList = AppUtil2.getInstallApp(requireActivity())
            Log.i("gh0st", "加载结束:${appList.size}")
            requireActivity().runOnUiThread {
                adapter.setNewInstance(appList)
                adapter.notifyDataSetChanged()
            }
        }
    }
}