package com.github.appmanager.ui.fragment

import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.appmanager.R
import com.github.appmanager.adapter.AppAdapter
import com.github.appmanager.databinding.FragmentAppBinding
import com.github.appmanager.ui.base.BaseFragment
import com.github.appmanager.ui.viewmodel.AppViewModel
import com.github.appmanager.utils.ViewUtils
import kotlinx.android.synthetic.main.fragment_app.*


class SystemAppFragment : BaseFragment<FragmentAppBinding>(R.layout.fragment_app) {
    private val viewModel: AppViewModel by viewModels({ requireActivity() })
    private val adapter = AppAdapter()
    override fun initView() {
        recyclerview.setHasFixedSize(true)
        recyclerview.layoutManager = LinearLayoutManager(activity)
        recyclerview.adapter = adapter
        viewModel.appList.observe(this, {
            adapter.setNewInstance(it.filter { item -> item.isSystem }.toMutableList())
            adapter.notifyDataSetChanged()
        })
        adapter.setOnItemClickListener { _, _, position ->
            ViewUtils.appInfoDialog(activity, adapter.getItem(position))
        }
    }
}