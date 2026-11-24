package com.ejemplo.locksuite.mdm

import android.content.Context
import android.util.Log

/**
 * Política por app de la **Tienda clásica de APKs** (`storeApps`): `blockWebView` y
 * `blockInternet`.
 *
 * Mantiene propiedad separada de las reglas manuales y de la Tienda Play (`PlayStoreCatalog`
 * / `PlayStoreManager`) para no apagar nunca un bloqueo puesto desde la ficha del equipo o
 * desde "Sin web" de Tienda Play cuando se desmarca una app en `storeApps`.
 */
object ClassicStorePolicyManager {

    private const val TAG = "ClassicStorePolicy"
    private const val PREFS_NAME = "classic_store_policy_prefs"
    private const val KEY_WV_DESIRED = "wv_desired"
    private const val KEY_WV_OWNED = "wv_owned"
    private const val KEY_NET_DESIRED = "net_desired"
    private const val KEY_NET_OWNED = "net_owned"

    data class BlockPlan(
        val toBlock: Set<String>,
        val toUnblock: Set<String>,
        val ownedAfter: Set<String>
    )

    /**
     * Calcula qué paquetes bloquear/desbloquear conservando los bloqueos previos manuales
     * y los protegidos por otra capa (como `noweb` en Tienda Play).
     */
    fun planBlockOwnership(
        desired: Set<String>,
        currentlyBlocked: Set<String>,
        owned: Set<String>,
        protectedKeepBlocked: Set<String> = emptySet()
    ): BlockPlan {
        val toBlock = LinkedHashSet<String>()
        val toUnblock = LinkedHashSet<String>()
        val ownedAfter = LinkedHashSet<String>()

        for (pkg in desired) {
            if (pkg.isBlank()) continue
            if (pkg in owned) {
                if (pkg !in currentlyBlocked) toBlock.add(pkg)
                ownedAfter.add(pkg)
            } else if (pkg !in currentlyBlocked) {
                toBlock.add(pkg)
                ownedAfter.add(pkg)
            }
            // Si ya estaba bloqueado antes de ser nuestro (manual / Play), NO lo adoptamos
            // en ownedAfter: así desmarcarla en storeApps no levanta ese otro bloqueo.
        }

        for (pkg in owned) {
            if (pkg !in desired && pkg !in protectedKeepBlocked && pkg in currentlyBlocked) {
                toUnblock.add(pkg)
            }
        }

        return BlockPlan(toBlock = toBlock, toUnblock = toUnblock, ownedAfter = ownedAfter)
    }

    fun replaceConfig(
        context: Context,
        webViewBlockedPkgs: Set<String>,
        internetBlockedPkgs: Set<String>
    ) {
        val ctx = context.applicationContext
        val filteredNet = PerAppInternetBlockPolicy.filterSupported(internetBlockedPkgs)
        prefs(ctx).edit()
            .putStringSet(KEY_WV_DESIRED, LinkedHashSet(webViewBlockedPkgs))
            .putStringSet(KEY_NET_DESIRED, LinkedHashSet(filteredNet))
            .commit()
        reconcile(ctx)
    }

    fun applyOnInstall(
        context: Context,
        packageName: String,
        blockWebView: Boolean,
        blockInternet: Boolean
    ) {
        val ctx = context.applicationContext
        val p = prefs(ctx)
        val wv = LinkedHashSet(p.getStringSet(KEY_WV_DESIRED, emptySet()) ?: emptySet())
        val net = LinkedHashSet(p.getStringSet(KEY_NET_DESIRED, emptySet()) ?: emptySet())
        if (blockWebView) wv.add(packageName) else wv.remove(packageName)
        if (blockInternet && PerAppInternetBlockPolicy.isSupported(packageName)) {
            net.add(packageName)
        } else {
            net.remove(packageName)
        }
        p.edit()
            .putStringSet(KEY_WV_DESIRED, wv)
            .putStringSet(KEY_NET_DESIRED, PerAppInternetBlockPolicy.filterSupported(net))
            .commit()
        reconcile(ctx)
    }

    fun clearAll(context: Context) {
        prefs(context.applicationContext).edit().clear().apply()
    }

    /**
     * Suelta la propiedad de WebView sobre [packages] para que reconciliaciones posteriores de
     * `storeApps` nunca desbloqueen apps bloqueadas por un perfil o manualmente.
     */
    fun releaseWebViewOwnership(context: Context, packages: Set<String>) {
        if (packages.isEmpty()) return
        val p = prefs(context.applicationContext)
        val wvOwned = p.getStringSet(KEY_WV_OWNED, emptySet()) ?: emptySet()
        if (wvOwned.any { it in packages }) {
            p.edit().putStringSet(KEY_WV_OWNED, wvOwned - packages).apply()
        }
    }

    fun reconcile(context: Context) {
        val ctx = context.applicationContext
        try {
            val policy = PolicyManager(ctx)
            if (policy.isLockSuiteSuspended()) return

            val p = prefs(ctx)
            val wvDesired = p.getStringSet(KEY_WV_DESIRED, emptySet()) ?: emptySet()
            val wvOwned = p.getStringSet(KEY_WV_OWNED, emptySet()) ?: emptySet()
            val playStoreNoWeb = try {
                PlayStoreCatalog.noWebPackages(PlayStoreManager.offered(ctx))
            } catch (_: Exception) {
                emptySet()
            }
            val wvPlan = planBlockOwnership(
                desired = wvDesired,
                currentlyBlocked = WebViewBlockManager.getBlockedPackages(ctx),
                owned = wvOwned,
                protectedKeepBlocked = playStoreNoWeb
            )
            for (pkg in wvPlan.toBlock) WebViewBlockManager.setBlocked(ctx, pkg, true)
            for (pkg in wvPlan.toUnblock) WebViewBlockManager.setBlocked(ctx, pkg, false)

            val rawNetDesired = p.getStringSet(KEY_NET_DESIRED, emptySet()) ?: emptySet()
            val netDesired = PerAppInternetBlockPolicy.filterSupported(rawNetDesired)
            val netOwned = p.getStringSet(KEY_NET_OWNED, emptySet()) ?: emptySet()
            val netPlan = planBlockOwnership(
                desired = netDesired,
                currentlyBlocked = policy.getPerAppInternetBlockedPackages(),
                owned = netOwned
            )
            for (pkg in netPlan.toBlock) policy.setPerAppInternetBlocked(pkg, true)
            for (pkg in netPlan.toUnblock) policy.setPerAppInternetBlocked(pkg, false)

            if (wvOwned != wvPlan.ownedAfter || netOwned != netPlan.ownedAfter) {
                p.edit()
                    .putStringSet(KEY_WV_OWNED, wvPlan.ownedAfter)
                    .putStringSet(KEY_NET_OWNED, netPlan.ownedAfter)
                    .apply()
            }
        } catch (e: Exception) {
            Log.w(TAG, "reconcile falló: ${e.message}", e)
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
