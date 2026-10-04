package com.tzvi.drivedownloadmonitor.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.tzvi.drivedownloadmonitor.util.PermissionManager
import com.tzvi.drivedownloadmonitor.util.PermissionSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _permissions = MutableStateFlow(PermissionManager.snapshot(application))
    val permissions: StateFlow<PermissionSnapshot> = _permissions.asStateFlow()

    fun refresh() {
        _permissions.value = PermissionManager.snapshot(getApplication())
    }
}
