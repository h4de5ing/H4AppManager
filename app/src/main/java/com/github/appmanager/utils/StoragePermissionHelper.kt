package com.github.appmanager.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object StoragePermissionHelper {

    private const val REQUEST_CODE_STORAGE_PERMISSION = 1001

    /**
     * Check if we have the required storage permissions
     */
    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // For Android 11 and above, we use the scoped storage approach
            true
        } else {
            // For Android 10 and below, check WRITE_EXTERNAL_STORAGE permission
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Request storage permissions if needed
     */
    fun requestStoragePermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // For Android 11 and above, we don't need legacy storage permissions
            // We use the scoped storage approach
            return
        } else {
            // For Android 10 and below, request WRITE_EXTERNAL_STORAGE permission
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_CODE_STORAGE_PERMISSION
            )
        }
    }

    /**
     * Handle permission request results
     */
    fun onRequestPermissionsResult(
        requestCode: Int,
        grantResults: IntArray
    ): Boolean {
        return when (requestCode) {
            REQUEST_CODE_STORAGE_PERMISSION -> {
                grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            }

            else -> false
        }
    }
}