package com.ejemplo.locksuite.ui.playstore

import com.ejemplo.locksuite.mdm.AppRequestManager
import com.ejemplo.locksuite.mdm.PlayStoreCatalog

/**
 * La parte de la pantalla de la Tienda Play que NO es Android (29/10/2026, B.101): qué
 * etiquetas lleva cada fila, qué letra y color tiene el recuadro de una app sin ícono, y
 * cómo se juntan los pedidos que lee la red con los que se mandaron desde esta pantalla.
 *
 * Está separada de [PlayStoreActivity] por la misma razón que `PlayStoreCatalog` está
 * separado de `PlayStoreManager`: ninguna sesión de IA de este proyecto puede compilar con
 * Gradle ni probar en un equipo, y lo único que agarra errores es dejar la decisión en una
 * función pura y correrle aserciones de verdad (ver `PlayStoreUiLogicTest`).
 */
object PlayStoreRows {

    /**
     * Cuánto tiempo se conserva en pantalla un pedido recién mandado aunque la lectura de
     * Firebase todavía no lo traiga. Sin esto, tocar "Pedir" e ir y volver a la pantalla
     * enseguida mostraría "Pedir" de nuevo (la lectura puede ganarle a la escritura).
     */
    const val LOCAL_GRACE_MS = 60_000L

    /** Aclaraciones que se muestran debajo del nombre de una app. */
    enum class Tag { NO_WEB, DOMAINS, ON_REQUEST, PAUSED }

    /**
     * @param access      acceso de la app en el catálogo ([PlayStoreCatalog.ACCESS_FULL] etc.).
     * @param status      estado en el catálogo ([PlayStoreCatalog.STATUS_ENABLED] o `_REQUEST`).
     * @param installed   ¿la app está instalada ahora?
     * @param requestable ¿la fila está en "Pedir" / "Pedida" (no se puede usar todavía)?
     */
    fun tagsFor(access: String, status: String, installed: Boolean, requestable: Boolean): List<Tag> {
        val out = ArrayList<Tag>(2)
        when (access) {
            PlayStoreCatalog.ACCESS_NO_WEB -> out.add(Tag.NO_WEB)
            PlayStoreCatalog.ACCESS_DOMAINS -> out.add(Tag.DOMAINS)
        }
        if (requestable && installed) {
            // Instalada por la tienda y pausada hasta que el administrador la apruebe: es
            // más claro que repetir "A pedido".
            out.add(Tag.PAUSED)
        } else if (status == PlayStoreCatalog.STATUS_REQUEST) {
            out.add(Tag.ON_REQUEST)
        }
        return out
    }

    fun tagText(tag: Tag, lang: String?): String = when (tag) {
        Tag.NO_WEB -> PlayStoreText.t(PlayStoreText.Key.TAG_NO_WEB, lang)
        Tag.DOMAINS -> PlayStoreText.t(PlayStoreText.Key.TAG_DOMAINS, lang)
        Tag.ON_REQUEST -> PlayStoreText.t(PlayStoreText.Key.TAG_ON_REQUEST, lang)
        Tag.PAUSED -> PlayStoreText.t(PlayStoreText.Key.TAG_PAUSED, lang)
    }

    /** "Sin web · A pedido". Vacío si no hay nada que aclarar. */
    fun subtitle(tags: List<Tag>, lang: String?): String =
        tags.joinToString(" · ") { tagText(it, lang) }

    /** Letra del recuadro de una app sin ícono. */
    fun initial(label: String): String {
        val c = label.firstOrNull { it.isLetterOrDigit() } ?: return "•"
        return c.uppercaseChar().toString()
    }

    private val PALETTE = intArrayOf(
        0xFF2563EB.toInt(), // azul
        0xFF7C3AED.toInt(), // violeta
        0xFF0891B2.toInt(), // cian
        0xFFEA580C.toInt(), // naranja
        0xFF16A34A.toInt(), // verde
        0xFFDB2777.toInt(), // rosa
        0xFF4F46E5.toInt(), // índigo
        0xFF0D9488.toInt()  // verde azulado
    )

    /** Color del recuadro de una app sin ícono. El mismo paquete siempre da el mismo color. */
    fun tileColor(packageName: String): Int {
        val h = packageName.hashCode()
        val idx = ((h % PALETTE.size) + PALETTE.size) % PALETTE.size
        return PALETTE[idx]
    }

    /**
     * Junta los pedidos que trajo la lectura de Firebase con los que se mandaron desde esta
     * pantalla hace poco y la lectura todavía no trae (ver [LOCAL_GRACE_MS]). Lo que trae
     * Firebase manda siempre que el paquete esté: un pedido que el administrador ya
     * contestó no se pisa con la copia local.
     */
    fun mergeRequests(
        fetched: List<AppRequestManager.Solicitud>,
        sentHere: List<AppRequestManager.Solicitud>,
        ahoraMs: Long
    ): List<AppRequestManager.Solicitud> {
        val keep = sentHere.filter { local ->
            ahoraMs - local.pedidaEnMs in 0..LOCAL_GRACE_MS &&
                fetched.none { it.packageName == local.packageName }
        }
        return fetched + keep
    }

    /**
     * ¿Hay un pedido vivo de este paquete? (pendiente, o aprobado y todavía sin catálogo).
     * Se le pasan los pedidos ya filtrados con
     * [AppRequestManager.sinAprobacionesViejasDePlay].
     */
    fun isRequested(requests: Collection<AppRequestManager.Solicitud>, packageName: String): Boolean =
        requests.any {
            it.packageName == packageName &&
                (it.estado == AppRequestManager.PENDIENTE || it.estado == AppRequestManager.APROBADA)
        }
}
