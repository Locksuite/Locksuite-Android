"use strict";

/**
 * play_lookup.js — consulta de apps en Google Play para el panel (29/10/2026, B.101).
 *
 * Para qué sirve: la Tienda Play del panel arranca con las apps de la tienda vieja de
 * LockSuite (`storeApps`), pero el dueño pidió que sean "las que hay en Play Store". Esta
 * consulta le dice al panel, por cada paquete, si Google Play lo tiene, y trae el nombre y
 * el ícono públicos para mostrarlos en la lista y en la pantalla de la tienda del celular
 * (la tienda no muestra el ícono de una app que todavía no está instalada sin tenerlo).
 *
 * ── LO QUE ES CIERTO Y LO QUE NO ──────────────────────────────────────────────────
 *  · "found"   → la página de la app respondió 200 y tiene título.
 *  · "missing" → Google Play respondió 404. Es lo único que se llama "no existe", y aun
 *                así el panel NO lo trata como un hecho: una app que solo se publica en
 *                otro país también da 404 desde el servidor. El panel lo muestra como
 *                "no aparece en Google Play (verificalo)" y deja decidir a la persona.
 *  · "unknown" → cualquier otra cosa (timeout, 429, 5xx, página de consentimiento,
 *                página sin título). NUNCA se interpreta como "no existe".
 *
 * ── LIMITACIONES, DICHAS DE FRENTE ───────────────────────────────────────────────
 *  · Esto lee HTML público de Google Play, que no es una API y cambia. Si cambia el
 *    marcado, el resultado degrada a "unknown" o a "found" sin ícono — no rompe nada: el
 *    celular muestra un cuadrito con la letra inicial cuando no hay ícono.
 *  · NO se pudo probar contra Google Play real al escribirlo (el entorno donde se escribió
 *    no tiene salida a play.google.com). Lo que sí está probado es el análisis de páginas
 *    de ejemplo y la clasificación (ver tools/test_play_lookup.js).
 *  · Los íconos solo se aceptan si son https hacia el CDN de Google (mismo criterio que
 *    PlayStoreCatalog.cleanIconUrl en el celular): el panel nunca puede hacer que el celular
 *    descargue una imagen de un sitio cualquiera.
 *
 * Es código sin dependencias de Firebase a propósito: se puede probar con `node` solo.
 */

const PACKAGE_RE = /^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$/;
const MAX_PACKAGES = 40;
const FETCH_TIMEOUT_MS = 8000;
const CONCURRENCY = 4;
const ICON_HOST_SUFFIXES = [".googleusercontent.com", ".ggpht.com", ".gstatic.com"];

function isValidPackage(pkg) {
  return typeof pkg === "string" && pkg.length <= 200 && PACKAGE_RE.test(pkg);
}

function decodeEntities(text) {
  return String(text)
    .replace(/&#x([0-9a-f]+);/gi, (_, h) => String.fromCodePoint(parseInt(h, 16)))
    .replace(/&#(\d+);/g, (_, d) => String.fromCodePoint(parseInt(d, 10)))
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&amp;/g, "&");
}

function escapeRegExp(text) {
  return text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/** Contenido de un `<meta>` buscado por `property`, `name` o `itemprop`, sin importar el orden de atributos. */
function metaContent(html, key) {
  const tag = new RegExp(
    "<meta\\s+[^>]*?(?:property|name|itemprop)\\s*=\\s*[\"']" + escapeRegExp(key) + "[\"'][^>]*>",
    "i"
  ).exec(html);
  if (!tag) return null;
  const content = /content\s*=\s*"([^"]*)"|content\s*=\s*'([^']*)'/i.exec(tag[0]);
  if (!content) return null;
  const value = decodeEntities(content[1] !== undefined ? content[1] : content[2]).trim();
  return value || null;
}

/** Quita el " - Apps on Google Play" (o su versión en otro idioma) del título. */
function cleanTitle(raw) {
  if (!raw) return null;
  let title = decodeEntities(raw).replace(/\s+/g, " ").trim();
  title = title.replace(/\s+[-–—|]\s+(Apps on Google Play|Apps en Google Play|Aplicaciones en Google Play|Google Play)\s*$/i, "").trim();
  if (!title || title.length > 120) return null;
  // El título de una página de error / consentimiento no es el nombre de una app.
  if (/^(before you continue|antes de continuar|error 404|not found|404)/i.test(title)) return null;
  return title;
}

/** Solo https hacia el CDN de Google. Devuelve la URL o null. */
function cleanIconUrl(raw) {
  if (typeof raw !== "string") return null;
  const url = raw.trim();
  if (url.length === 0 || url.length > 600 || !url.startsWith("https://")) return null;
  if (/[\s"'<>]/.test(url)) return null;
  const authority = url.slice("https://".length).split("/")[0].split("?")[0];
  if (authority.includes("@")) return null;
  const host = authority.split(":")[0].toLowerCase();
  if (!host) return null;
  return ICON_HOST_SUFFIXES.some((suffix) => host.endsWith(suffix)) ? url : null;
}

/** Pide un tamaño de ícono razonable al CDN (`=s128-rw`) si la URL trae opciones de tamaño. */
function iconAtSize(url) {
  return /=[swh]\d[a-z0-9-]*$/i.test(url) ? url.replace(/=[swh]\d[a-z0-9-]*$/i, "=s128-rw") : url;
}

/**
 * Nombre e ícono públicos de una página de Google Play. `title` es null si la página no
 * parece ser la de una app (error, consentimiento, etc.).
 */
function parsePlayPage(html) {
  const text = typeof html === "string" ? html : "";
  let title = cleanTitle(metaContent(text, "og:title"));
  if (!title) {
    const t = /<title[^>]*>([\s\S]*?)<\/title>/i.exec(text);
    title = t ? cleanTitle(t[1]) : null;
  }

  // El ícono: primero la etiqueta que Play marca como "Icon image", después los metadatos.
  const candidates = [];
  const iconImg = /<img\b[^>]*?\balt\s*=\s*["']Icon image["'][^>]*>/i.exec(text);
  if (iconImg) {
    const src = /\bsrc\s*=\s*"([^"]*)"|\bsrc\s*=\s*'([^']*)'/i.exec(iconImg[0]);
    if (src) candidates.push(decodeEntities(src[1] !== undefined ? src[1] : src[2]));
  }
  candidates.push(metaContent(text, "image"), metaContent(text, "og:image"));
  let iconUrl = null;
  for (const c of candidates) {
    const ok = cleanIconUrl(c);
    if (ok) { iconUrl = iconAtSize(ok); break; }
  }
  return { title, iconUrl };
}

/**
 * Consulta UN paquete. `fetchImpl` se inyecta para poder probar sin red.
 * Nunca lanza: cualquier fallo es `unknown`.
 */
async function lookupOne(pkg, fetchImpl, timeoutMs = FETCH_TIMEOUT_MS) {
  const result = { packageName: pkg, status: "unknown" };
  if (!isValidPackage(pkg)) return result;
  const url = "https://play.google.com/store/apps/details?id=" + encodeURIComponent(pkg) + "&hl=en&gl=US";
  const controller = typeof AbortController === "function" ? new AbortController() : null;
  const timer = controller ? setTimeout(() => controller.abort(), timeoutMs) : null;
  try {
    const res = await fetchImpl(url, {
      method: "GET",
      redirect: "follow",
      signal: controller ? controller.signal : undefined,
      headers: {
        "User-Agent": "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36",
        "Accept-Language": "en-US,en;q=0.9",
      },
    });
    result.httpStatus = res.status;
    if (res.status === 404) {
      result.status = "missing";
      return result;
    }
    if (res.status !== 200) return result; // 429, 5xx...: no se sabe
    const html = await res.text();
    const info = parsePlayPage(html);
    if (info.title) {
      result.status = "found";
      result.title = info.title;
      if (info.iconUrl) result.iconUrl = info.iconUrl;
    }
    return result;
  } catch (e) {
    return result; // red, timeout, abort: no se sabe
  } finally {
    if (timer) clearTimeout(timer);
  }
}

/**
 * Consulta una lista de paquetes (con tope y concurrencia limitada para no parecer un
 * ataque ni pasarse del tiempo de la Function). Devuelve un resultado por paquete válido,
 * en el orden recibido, sin repetidos.
 */
async function lookupMany(packages, fetchImpl, options = {}) {
  const max = options.max || MAX_PACKAGES;
  const concurrency = options.concurrency || CONCURRENCY;
  const seen = new Set();
  const list = [];
  for (const raw of Array.isArray(packages) ? packages : []) {
    const pkg = typeof raw === "string" ? raw.trim() : "";
    if (!isValidPackage(pkg) || seen.has(pkg)) continue;
    seen.add(pkg);
    list.push(pkg);
    if (list.length >= max) break;
  }
  const out = new Array(list.length);
  let next = 0;
  async function worker() {
    while (true) {
      const i = next++;
      if (i >= list.length) return;
      out[i] = await lookupOne(list[i], fetchImpl, options.timeoutMs);
    }
  }
  const workers = [];
  for (let i = 0; i < Math.min(concurrency, list.length); i++) workers.push(worker());
  await Promise.all(workers);
  return out;
}

module.exports = {
  MAX_PACKAGES,
  isValidPackage,
  decodeEntities,
  metaContent,
  cleanTitle,
  cleanIconUrl,
  iconAtSize,
  parsePlayPage,
  lookupOne,
  lookupMany,
};
