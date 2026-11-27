package com.ejemplo.locksuite.mdm

import com.ejemplo.locksuite.dns.DomainRuleTrie
import com.ejemplo.locksuite.dns.DomainSafety
import com.ejemplo.locksuite.dns.RuleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WhitelistPerDeviceTest {

    private fun loadDefaultCatalogAsset(): String {
        val candidates = listOf(
            File("src/main/assets/default_whitelist_catalog.txt"),
            File("app/src/main/assets/default_whitelist_catalog.txt")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("No se encontro default_whitelist_catalog.txt desde ${File(".").absolutePath}")
        return file.readText(Charsets.UTF_8)
    }

    @Test
    fun `DomainSafety rechaza bases compartidas y sufijos publicos y acepta subdominios concretos`() {
        for (bad in listOf("amazonaws.com", "cloudfront.net", "googleapis.com", "google.com", "com.ar", "co.il", "*.waze.com")) {
            assertNotNull("Debio rechazar $bad", DomainSafety.rejectReason(bad))
            assertFalse(DomainSafety.isSafeToAllow(bad))
        }
        for (good in listOf("api.waze.com", "whatsapp.net", "pagos.mercadopago.com.ar", "d1a3f4spazzrp4.cloudfront.net")) {
            assertNull("Debio aceptar $good", DomainSafety.rejectReason(good))
            assertTrue(DomainSafety.isSafeToAllow(good))
        }
    }

    @Test
    fun `WhitelistCatalogIo parsea el catalogo por defecto completo con 25 apps 16 allow y 242 block`() {
        val raw = loadDefaultCatalogAsset()
        val res = WhitelistCatalogIo.parseCatalogText(raw)
        assertTrue("Parseo fallido: ${res.error}", res.ok)
        val cat = res.catalog!!
        assertEquals(25, cat.apps.size)
        assertEquals(16, cat.globalAllow.size)
        assertEquals(242, cat.globalBlock.size)

        val waze = cat.apps.find { it.packageName == "com.waze" }
        assertNotNull(waze)
        assertEquals(WhitelistCatalogIo.ACCESS_NOWEB, waze!!.access)

        val serialized = WhitelistCatalogIo.formatCatalogText(cat)
        val reparsed = WhitelistCatalogIo.parseCatalogText(serialized)
        assertTrue(reparsed.ok)
        assertEquals(cat.apps.size, reparsed.catalog!!.apps.size)
        assertEquals(cat.globalAllow, reparsed.catalog.globalAllow)
        assertEquals(cat.globalBlock, reparsed.catalog.globalBlock)
    }

    @Test
    fun `WhitelistCatalogIo lee formato docx leyendo word document xml`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:r><w:t># lockfox-dominios-v1</w:t></w:r></w:p>
                <w:p><w:r><w:t>[APP: com.waze]</w:t></w:r></w:p>
                <w:p><w:r><w:t>Nombre: Waze</w:t></w:r></w:p>
                <w:p><w:r><w:t>Estado: habilitada</w:t></w:r></w:p>
                <w:p><w:r><w:t>Acceso: noweb</w:t></w:r></w:p>
                <w:p><w:r><w:t>Permitidos: waze.com, wazestatic.com</w:t></w:r></w:p>
                <w:p><w:r><w:t>[GLOBAL: PROHIBIDOS]</w:t></w:r></w:p>
                <w:p><w:r><w:t>tiktok.com</w:t></w:r></w:p>
              </w:body>
            </w:document>
        """.trimIndent()
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("word/document.xml"))
            zos.write(xml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        val txt = WhitelistCatalogIo.extractTextFromDocx(baos.toByteArray())
        val parsed = WhitelistCatalogIo.parseCatalogText(txt)
        assertTrue("Docx fallo: ${parsed.error}", parsed.ok)
        val cat = parsed.catalog!!
        assertEquals(1, cat.apps.size)
        assertEquals("com.waze", cat.apps[0].packageName)
        assertEquals(WhitelistCatalogIo.ACCESS_NOWEB, cat.apps[0].access)
        assertEquals(listOf("waze.com", "wazestatic.com"), cat.apps[0].allow)
        assertEquals(listOf("tiktok.com"), cat.globalBlock)
    }

    @Test
    fun `buildWhitelistModeRules respeta precedencia enabled disabled blocked globalBlock y FORCE`() {
        val cat = WhitelistCatalogIo.DeviceWhitelistCatalog(
            apps = listOf(
                WhitelistCatalogIo.WhitelistAppEntry(
                    packageName = "com.waze",
                    label = "Waze",
                    status = WhitelistCatalogIo.STATUS_ENABLED,
                    allow = listOf("waze.com", "cloudfront.net"),
                    block = listOf("ads.waze.com")
                ),
                WhitelistCatalogIo.WhitelistAppEntry(
                    packageName = "com.example.off",
                    label = "Off",
                    status = WhitelistCatalogIo.STATUS_DISABLED,
                    allow = listOf("disabledapp.com"),
                    block = listOf("bad-off.com")
                )
            ),
            globalAllow = listOf("example.com"),
            globalBlock = listOf("example.com", "blocked-global.com")
        )
        val rules = WhitelistManager.buildWhitelistModeRules(cat, includeSharedCdn = false)
        assertEquals(RuleType.ALLOW, rules["waze.com"])
        assertEquals(RuleType.FORCE_BLOCK, rules["ads.waze.com"])
        // cloudfront.net es base de CDN compartida: DomainSafety no deja abrirlo como ALLOW
        assertNull(rules["cloudfront.net"])
        // App deshabilitada no abre su allow, pero mantiene su block
        assertNull(rules["disabledapp.com"])
        assertEquals(RuleType.FORCE_BLOCK, rules["bad-off.com"])
        // globalBlock le gana a globalAllow
        assertEquals(RuleType.FORCE_BLOCK, rules["example.com"])

        // Simulacion de preferencia con Trie: user FORCE_ALLOW y FORCE_BLOCK ganan
        val merged = LinkedHashMap<String, RuleType>(rules)
        merged["example.com"] = RuleType.FORCE_ALLOW
        merged["waze.com"] = RuleType.FORCE_BLOCK
        val trie = DomainRuleTrie.build(merged)
        assertEquals(RuleType.FORCE_ALLOW, trie.resolve("sub.example.com"))
        assertEquals(RuleType.FORCE_BLOCK, trie.resolve("api.waze.com"))
    }
}
