package com.github.appmanager.ui.viewmodel

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.github.appmanager.model.AppModel

class AppViewModel : ViewModel() {
    val appList = MutableLiveData<List<AppModel>>()
}