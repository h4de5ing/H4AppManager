package com.github.appmanager.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.TypedValue
import android.widget.Toast
import com.github.appmanager.R
import kotlin.math.roundToInt

internal fun Context.dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

internal fun Context.sp(value: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

internal fun Context.isNightMode(): Boolean =
    resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

internal fun Context.copyToClipboard(label: String, text: String) {
    val clipboard = getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    // Android 13 起系统会自行弹出复制确认，避免重复提示。
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }
}
