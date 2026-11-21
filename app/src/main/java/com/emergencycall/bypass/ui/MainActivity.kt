package com.emergencycall.bypass.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import com.emergencycall.bypass.ui.theme.EmergencyCallTheme
import com.emergencycall.bypass.util.PermissionHelper

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestRequiredPermissions()

        setContent {
            EmergencyCallTheme {
                MainScreen(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissions()
    }

    private fun requestRequiredPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (!PermissionHelper.hasPhonePermissions(this)) {
            permissionsToRequest.add(Manifest.permission.READ_PHONE_STATE)
            permissionsToRequest.add(Manifest.permission.READ_CALL_LOG)
        }

        if (!PermissionHelper.hasSmsPermissions(this)) {
            permissionsToRequest.add(Manifest.permission.RECEIVE_SMS)
            permissionsToRequest.add(Manifest.permission.READ_SMS)
        }

        if (!PermissionHelper.hasSendSmsPermission(this)) {
            permissionsToRequest.add(Manifest.permission.SEND_SMS)
        }

        if (!PermissionHelper.hasLocationPermissions(this)) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (!PermissionHelper.hasCallPhonePermission(this)) {
            permissionsToRequest.add(Manifest.permission.CALL_PHONE)
        }

        if (!PermissionHelper.hasReadContactsPermission(this)) {
            permissionsToRequest.add(Manifest.permission.READ_CONTACTS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !PermissionHelper.hasNotificationPermission(this)
        ) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                1001
            )
        }
    }
}
