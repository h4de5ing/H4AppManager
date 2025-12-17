package com.github.appmanager.ui.fragment

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.appmanager.adapter.AppAdapter
import com.github.appmanager.databinding.FragmentAppBinding
import com.github.appmanager.ui.base.BaseFragment
import com.github.appmanager.ui.viewmodel.AppViewModel
import com.github.appmanager.utils.ViewUtils

class SystemAppFragment : BaseFragment<FragmentAppBinding>() {
    private val viewModel: AppViewModel by viewModels({ requireActivity() })
    private val adapter = AppAdapter()
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recyclerview.setHasFixedSize(true)
        binding.recyclerview.layoutManager = LinearLayoutManager(activity)
        binding.recyclerview.adapter = adapter
        viewModel.appList.observe(this, {
            adapter.setNewInstance(it.filter { item -> item.isSystem }.toMutableList())
            adapter.notifyDataSetChanged()
        })
        adapter.setOnItemClickListener { _, _, position ->
            ViewUtils.appInfoDialog(activity, adapter.getItem(position))
        }
    }
}