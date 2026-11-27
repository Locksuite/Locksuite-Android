package com.ejemplo.locksuite.ui.aurora

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.ejemplo.locksuite.ui.playstore.PlayStoreActivity

/**
 * Compatibilidad con accesos directos viejos: Tienda Aurora se unificó con la Tienda principal (B.134).
 */
class AuroraStoreActivity : Activity() {

    companion object {
        fun intent(context: Context): Intent = PlayStoreActivity.intent(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(PlayStoreActivity.intent(this))
        finish()
    }
}
