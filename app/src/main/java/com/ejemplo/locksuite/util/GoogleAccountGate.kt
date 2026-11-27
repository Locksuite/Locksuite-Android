package com.ejemplo.locksuite.util

import android.Manifest
import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.UserManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Gate de cuenta Google antes de usar Google Play como respaldo (adaptado de Lockfox).
 * Nunca afirma NO_ACCOUNT si no se tiene permiso GET_ACCOUNTS (devuelve UNKNOWN).
 */
object GoogleAccountGate {

    enum class State {
        HAS_ACCOUNT,
        NO_ACCOUNT,
        BLOCKED,
        UNKNOWN
    }

    fun state(context: Context): State {
        val ctx = context.applicationContext ?: context
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.GET_ACCOUNTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return State.UNKNOWN
        }
        val accounts = try {
            AccountManager.get(ctx).getAccountsByType("com.google")
        } catch (_: Throwable) {
            return State.UNKNOWN
        }
        if (accounts.isNotEmpty()) return State.HAS_ACCOUNT

        val modifyBlocked = try {
            val um = ctx.getSystemService(Context.USER_SERVICE) as? UserManager
            um?.hasUserRestriction(UserManager.DISALLOW_MODIFY_ACCOUNTS) == true
        } catch (_: Throwable) {
            false
        }
        return if (modifyBlocked) State.BLOCKED else State.NO_ACCOUNT
    }

    fun startSignIn(activity: Activity): Boolean {
        val primary = Intent(Settings.ACTION_ADD_ACCOUNT).apply {
            putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google"))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            activity.startActivity(primary)
            true
        } catch (_: Throwable) {
            try {
                activity.startActivity(Intent(Settings.ACTION_SYNC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (_: Throwable) {
                false
            }
        }
    }
}
