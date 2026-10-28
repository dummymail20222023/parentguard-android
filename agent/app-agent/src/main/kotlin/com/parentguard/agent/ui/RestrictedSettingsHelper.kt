package com.parentguard.agent.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Helper for direct-installed (sideloaded) APKs on Android 13+ (API 33+).
 * Because Android 13+ blocks Special App Access (Usage Access & Notification Listener) for
 * sideloaded APKs until the user enables 'Allow restricted settings' in App Info, this helper
 * detects sideloaded installation and deep-links the child/parent directly to App Info.
 */
object RestrictedSettingsHelper {

    fun requiresRestrictedSettingsUnlock(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }

    fun createAppDetailsSettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
