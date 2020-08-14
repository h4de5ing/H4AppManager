package com.github.appmanager.adapter

import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.viewholder.BaseViewHolder
import com.github.appmanager.R
import com.github.appmanager.model.AppModel

class AppAdapter(layoutRes: Int = R.layout.item_recy) :
    BaseQuickAdapter<AppModel, BaseViewHolder>(layoutRes) {
    override fun convert(holder: BaseViewHolder, item: AppModel) {
        holder.setText(R.id.recy_app_name, item.appName)
        holder.setImageDrawable(R.id.recy_app_icon, item.appIcon)
        holder.setText(R.id.recy_app_size, item.appSize)
    }
}