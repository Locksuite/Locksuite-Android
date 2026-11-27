package com.ejemplo.locksuite.mdm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Historial local de respaldos del catálogo de lista blanca por dispositivo.
 * Guarda el texto `lockfox-dominios-v1` en `filesDir/whitelist_backups/` junto con un
 * índice JSON de metadatos. Las copias no favoritas expiran automáticamente a los 30 días.
 */
class WhitelistBackupStore(context: Context) {

    companion object {
        const val BACKUP_TTL_MS = 30L * 24 * 60 * 60 * 1000L
        private const val DIR_NAME = "whitelist_backups"
        private const val INDEX_FILE = "index.json"
    }

    data class BackupMeta(
        val id: String,
        val createdAt: Long,
        val reason: String,
        val note: String,
        val favorite: Boolean,
        val appCount: Int,
        val appAllow: Int,
        val appBlock: Int,
        val globalAllow: Int,
        val globalBlock: Int
    )

    private val dir = File(context.applicationContext.filesDir, DIR_NAME).apply { mkdirs() }
    private val indexFile = File(dir, INDEX_FILE)

    @Synchronized
    fun pruneExpired(nowMs: Long = System.currentTimeMillis()): Int {
        val all = readIndex()
        val kept = mutableListOf<BackupMeta>()
        var removed = 0
        for (m in all) {
            if (!m.favorite && m.createdAt > 0L && (nowMs - m.createdAt) >= BACKUP_TTL_MS) {
                File(dir, "${m.id}.txt").delete()
                removed++
            } else {
                kept.add(m)
            }
        }
        if (removed > 0) writeIndex(kept)
        return removed
    }

    @Synchronized
    fun saveSnapshot(
        catalog: WhitelistCatalogIo.DeviceWhitelistCatalog,
        reason: String,
        note: String = "",
        favorite: Boolean = false,
        deviceLabel: String = "",
        nowMs: Long = System.currentTimeMillis()
    ): BackupMeta {
        pruneExpired(nowMs)
        val id = "${nowMs}_${UUID.randomUUID().toString().take(8)}"
        val st = WhitelistCatalogIo.stats(catalog)
        val text = WhitelistCatalogIo.formatCatalogText(
            catalog = catalog,
            deviceLabel = deviceLabel,
            reason = reason,
            note = note,
            nowMs = nowMs
        )
        File(dir, "$id.txt").writeText(text, Charsets.UTF_8)
        val meta = BackupMeta(
            id = id,
            createdAt = nowMs,
            reason = reason,
            note = note,
            favorite = favorite,
            appCount = st.appCount,
            appAllow = st.appAllow,
            appBlock = st.appBlock,
            globalAllow = st.globalAllow,
            globalBlock = st.globalBlock
        )
        val list = readIndex().toMutableList()
        list.add(0, meta)
        writeIndex(list)
        return meta
    }

    @Synchronized
    fun list(nowMs: Long = System.currentTimeMillis()): List<BackupMeta> {
        pruneExpired(nowMs)
        return readIndex().sortedByDescending { it.createdAt }
    }

    @Synchronized
    fun readText(id: String): String? {
        val f = File(dir, "$id.txt")
        return if (f.exists()) f.readText(Charsets.UTF_8) else null
    }

    @Synchronized
    fun setFavorite(id: String, favorite: Boolean): Boolean {
        val list = readIndex().toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return false
        list[idx] = list[idx].copy(favorite = favorite)
        writeIndex(list)
        return true
    }

    @Synchronized
    fun setNote(id: String, note: String): Boolean {
        val list = readIndex().toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return false
        list[idx] = list[idx].copy(note = note.trim())
        writeIndex(list)
        return true
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val list = readIndex().toMutableList()
        val removed = list.removeAll { it.id == id }
        File(dir, "$id.txt").delete()
        if (removed) writeIndex(list)
        return removed
    }

    private fun readIndex(): List<BackupMeta> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(indexFile.readText(Charsets.UTF_8))
            val out = mutableListOf<BackupMeta>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "").trim()
                if (id.isEmpty()) continue
                out.add(
                    BackupMeta(
                        id = id,
                        createdAt = o.optLong("createdAt", 0L),
                        reason = o.optString("reason", ""),
                        note = o.optString("note", ""),
                        favorite = o.optBoolean("favorite", false),
                        appCount = o.optInt("appCount", 0),
                        appAllow = o.optInt("appAllow", 0),
                        appBlock = o.optInt("appBlock", 0),
                        globalAllow = o.optInt("globalAllow", 0),
                        globalBlock = o.optInt("globalBlock", 0)
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeIndex(list: List<BackupMeta>) {
        val arr = JSONArray()
        for (m in list) {
            val o = JSONObject()
            o.put("id", m.id)
            o.put("createdAt", m.createdAt)
            o.put("reason", m.reason)
            o.put("note", m.note)
            o.put("favorite", m.favorite)
            o.put("appCount", m.appCount)
            o.put("appAllow", m.appAllow)
            o.put("appBlock", m.appBlock)
            o.put("globalAllow", m.globalAllow)
            o.put("globalBlock", m.globalBlock)
            arr.put(o)
        }
        indexFile.writeText(arr.toString(), Charsets.UTF_8)
    }
}
