package com.ejemplo.locksuite.ui.update

import android.app.Activity
import android.os.Bundle
import com.ejemplo.locksuite.ui.playstore.PlayStoreActivity

/**
 * Redirección a la Tienda única (B.134): instalar y actualizar ahora viven en [PlayStoreActivity].
 */
class AppUpdateActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(PlayStoreActivity.intent(this))
        finish()
    }
}
