package com.ruijiepro.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.ruijiepro.utils.PreferencesManager

class MainViewModelFactory(private val prefs: PreferencesManager) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(prefs) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
