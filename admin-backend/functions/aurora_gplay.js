"use strict";

/**
 * Cliente puro Node.js del protocolo Google Play FDFE / Checkin.
 * Implementa:
 *   - Intercambio OAuth token (oauth2_4/...) -> AAS token (aas_et/...)
 *   - Fetch desde un Token Dispenser externo con validación SSRF estricta
 *   - /checkin + /auth + /fdfe/uploadDeviceConfig + /fdfe/toc + /fdfe/acceptTos
 *   - /fdfe/details + /fdfe/purchase + /fdfe/delivery
 *   - Descarga en streaming con verificación SHA-256 / SHA-1
 *
 * Todo el I/O de red pasa por `fetchImpl` inyectable para pruebas unitarias determinísticas.
 */
const fs = require("fs");
const crypto = require("crypto");
const { Readable } = require("stream");
const proto = require("./aurora_proto");
const { buildUserAgent, buildCheckinProto } = require("./aurora_devices");

const BASE_URL = "https://android.clients.google.com";
const CLIENT_SIG = "38918a453d07199354f8b19af05ec6562ced5788";
const CALLER_SIG = "38918a453d07199354f8b19af05ec6562ced5788";
const ANDROID_VENDING = "com.google.android.gms";
const PLAY_SERVICES_VERSION = "19629032";
const PLAY_SERVICE_SCOPE = "oauth2:https://www.googleapis.com/auth/googleplay";
const MAX_APK_DOWNLOAD_BYTES = 450 * 1024 * 1024; // bajo el tope de 460 MB de SelfUpdater.kt

const DEFAULT_DFE_TARGETS =
  "CAESN/qigQYC2AMBFfUbyA7SM5Ij/CvfBoIDgxHqGP8R3xzIBvoQtBKFDZ4HAY4FrwSVMasHBO0O2Q8akgYRAQECAQO7AQEpKZ0CnwECAwRrAQYBr9PPAoK7sQMBAQMCBAkIDAgBAwEDBAICBAUZEgMEBAMLAQEBBQEBAcYBARYED+cBfS8CHQEKkAEMMxcBIQoUDwYHIjd3DQ4MFk0JWGYZEREYAQOLAYEBFDMIEYMBAgICAgICOxkCD18LGQKEAcgDBIQBAgGLARkYCy8oBTJlBCUocxQn0QUBDkkGxgNZQq0BZSbeAmIDgAEBOgGtAaMCDAOQAZ4BBIEBKUtQUYYBQscDDxPSARA1oAEHAWmnAsMB2wFyywGLAxol+wImlwOOA80CtwN26A0WjwJVbQEJPAH+BRDeAfkHK/ABASEBCSAaHQemAzkaRiu2Ad8BdXeiAwEBGBUBBN4LEIABK4gB2AFLfwECAdoENq0CkQGMBsIBiQEtiwGgA1zyAUQ4uwS8AwhsvgPyAcEDF27vApsBHaICGhl3GSKxAR8MC6cBAgItmQYG9QIeywLvAeYBDArLAh8HASI4ELICDVmVBgsY/gHWARtcAsMBpALiAdsBA7QBpAJmIArpByn0AyAKBwHTARIHAX8D+AMBcRIBBbEDmwUBMacCHAciNp0BAQF0OgQLJDuSAh54kwFSP0eeAQQ4M5EBQgMEmwFXywFo0gFyWwMcapQBBugBPUW2AVgBKmy3AR6PAbMBGQxrUJECvQR+8gFoWDsYgQNwRSczBRXQAgtRswEW0ALMAREYAUEBIG6yATYCRE8OxgER8gMBvQEDRkwLc8MBTwHZAUOnAXiiBakDIbYBNNcCIUmuArIBSakBrgFHKs0EgwV/G3AD0wE6LgECtQJ4xQFwFbUCjQPkBS6vAQqEAUZF3QIM9wEhCoYCQhXsBCyZArQDugIziALWAdIBlQHwBdUErQE6qQaSA4EEIvYBHir9AQVLmgMCApsCKAwHuwgrENsBAjNYswEVmgIt7QJnN4wDEnta+wGfAcUBxgEtEFXQAQWdAUAeBcwBAQM7rAEJATJ0LENrdh73A6UBhAE+qwEeASxLZUMhDREuH0CGARbd7K0GlQo";

const DEFAULT_DFE_PHENOTYPE =
  "H4sIAAAAAAAAAB3OO3KjMAAA0KRNuWXukBkBQkAJ2MhgAZb5u2GCwQZbCH_EJ77QHmgvtDtbv-Z9_H63zXXU0NVPB1odlyGy7751Q3CitlPDvFd8lxhz3tpNmz7P92CFw73zdHU2Ie0Ad2kmR8lxhiErTFLt3RPGfJQHSDy7Clw10bg8kqf2owLokN4SecJTLoSwBnzQSd652_MOf2d1vKBNVedzg4ciPoLz2mQ8efGAgYeLou-l-PXn_7Sna1MfhHuySxt-4esulEDp8Sbq54CPPKjpANW-lkU2IZ0F92LBI-ukCKSptqeq1eXU96LD9nZfhKHdtjSWwJqUm_2r6pMHOxk01saVanmNopjX3YxQafC4iC6T55aRbC8nTI98AF_kItIQAJb5EQxnKTO7TZDWnr01HVPxelb9A2OWX6poidMWl16K54kcu_jhXw-JSBQkVcD_fPsLSZu6joIBAAA";

function parseFormReply(text) {
  const out = {};
  for (const line of String(text || "").split(/\r?\n/)) {
    const idx = line.indexOf("=");
    if (idx <= 0) continue;
    out[line.slice(0, idx).trim().toLowerCase()] = line.slice(idx + 1).trim();
  }
  return out;
}

function isPrivateOrDisallowedHost(host) {
  const h = String(host || "").toLowerCase().replace(/^\[|\]$/g, "");
  if (!h) return true;
  if (h === "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal")) return true;
  if (h === "::" || h === "::1" || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80:") || h.startsWith("::ffff:")) return true;
  const ipv4 = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(h);
  if (ipv4) {
    const [a, b] = [Number(ipv4[1]), Number(ipv4[2])];
    if (a === 0 || a === 10 || a === 127) return true;
    if (a === 169 && b === 254) return true;
    if (a === 172 && b >= 16 && b <= 31) return true;
    if (a === 192 && b === 168) return true;
    if (a === 100 && b >= 64 && b <= 127) return true;
    if (a >= 224) return true;
  }
  return false;
}

function validateDispenserUrl(raw) {
  const s = typeof raw === "string" ? raw.trim() : "";
  if (!s || s.length > 500 || !s.startsWith("https://")) {
    throw new Error("La URL del Dispenser debe comenzar con https://");
  }
  let u;
  try {
    u = new URL(s);
  } catch (_) {
    throw new Error("La URL del Dispenser no es válida.");
  }
  if (u.protocol !== "https:") throw new Error("La URL del Dispenser debe usar HTTPS.");
  if (u.username || u.password) throw new Error("La URL del Dispenser no puede incluir credenciales.");
  if (u.port && u.port !== "443") throw new Error("La URL del Dispenser solo puede usar el puerto 443.");
  if (isPrivateOrDisallowedHost(u.hostname)) {
    throw new Error("La URL del Dispenser no puede apuntar a direcciones locales o privadas.");
  }
  return u.toString();
}

function decodeHashToHex(raw) {
  if (typeof raw !== "string" || !raw.trim()) return "";
  const s = raw.trim();
  if (/^[0-9a-f]{40}$/i.test(s) || /^[0-9a-f]{64}$/i.test(s)) return s.toLowerCase();
  const b64 = s.replace(/-/g, "+").replace(/_/g, "/");
  try {
    return Buffer.from(b64, "base64").toString("hex");
  } catch (_) {
    return "";
  }
}

async function defaultFetchWithTimeout(fetchImpl, url, init, timeoutMs = 30000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetchImpl(url, { ...init, signal: controller.signal });
  } finally {
    clearTimeout(timer);
  }
}

/**
 * Intercambia un oauth_token (ej. oauth2_4/...) de https://accounts.google.com/EmbeddedSetup
 * por un token AAS reutilizable (aas_et/...). El oauth_token solo funciona una vez.
 */
async function exchangeOAuthForAas({ email, oauthToken, fetchImpl = globalThis.fetch }) {
  const cleanEmail = String(email || "").trim();
  const cleanOAuth = String(oauthToken || "").trim();
  if (!cleanEmail || !cleanEmail.includes("@")) throw new Error("Falta un email de Google válido.");
  if (!cleanOAuth) throw new Error("Falta el oauth_token obtenido desde EmbeddedSetup.");

  const body = new URLSearchParams({
    lang: "en",
    google_play_services_version: PLAY_SERVICES_VERSION,
    sdk_version: "28",
    device_country: "us",
    Email: cleanEmail,
    service: "ac2dm",
    get_accountid: "1",
    ACCESS_TOKEN: "1",
    callerPkg: ANDROID_VENDING,
    add_account: "1",
    Token: cleanOAuth,
    callerSig: CALLER_SIG,
    droidguard_results: "null",
  }).toString();

  const res = await defaultFetchWithTimeout(fetchImpl, `${BASE_URL}/auth`, {
    method: "POST",
    headers: {
      app: ANDROID_VENDING,
      "content-type": "application/x-www-form-urlencoded",
      "user-agent": "",
    },
    body,
  }, 25000);

  const text = await res.text();
  const reply = parseFormReply(text);
  if (reply.error) {
    throw new Error(`Google rechazó el token OAuth: ${reply.error}`);
  }
  if (!res.ok) {
    throw new Error(`Google /auth respondió HTTP ${res.status}`);
  }
  if (!reply.token) {
    throw new Error("Google /auth no devolvió un token AAS.");
  }
  return reply.token;
}

/**
 * Obtiene credenciales temporales o AAS desde un Token Dispenser compatible con Aurora.
 */
async function fetchFromDispenser({ dispenserUrl, profile, fetchImpl = globalThis.fetch }) {
  const url = validateDispenserUrl(dispenserUrl);
  const payload = profile ? {
    userReadableName: profile.userReadableName,
    sdkVersion: profile.sdkVersion,
    buildDevice: profile.device,
    buildId: profile.buildId,
    abis: profile.deviceConfiguration && profile.deviceConfiguration.nativePlatform,
  } : {};

  let res = await defaultFetchWithTimeout(fetchImpl, url, {
    method: "POST",
    redirect: "error",
    headers: {
      "content-type": "application/json",
      accept: "application/json",
      "user-agent": "LockSuite-Aurora-Sync/1.0",
    },
    body: JSON.stringify(payload),
  }, 20000);

  if (res.status === 405) {
    res = await defaultFetchWithTimeout(fetchImpl, url, {
      method: "GET",
      redirect: "error",
      headers: {
        accept: "application/json",
        "user-agent": "LockSuite-Aurora-Sync/1.0",
      },
    }, 20000);
  }

  if (!res.ok) {
    throw new Error(`El Dispenser respondió HTTP ${res.status}`);
  }
  const data = await res.json().catch(() => null);
  if (!data || typeof data !== "object") {
    throw new Error("El Dispenser no devolvió JSON válido.");
  }
  const email = typeof data.email === "string" ? data.email.trim() : "";
  const authToken = typeof (data.auth || data.authToken) === "string" ? (data.auth || data.authToken).trim() : "";
  const aasToken = typeof (data.aasToken || data.token) === "string" ? (data.aasToken || data.token).trim() : "";
  if (!email || (!authToken && !aasToken)) {
    throw new Error("El Dispenser debe devolver { email, auth } o { email, aasToken }.");
  }
  return { email, authToken: authToken || null, aasToken: aasToken || null, dfeCookie: data.dfeCookie || null, gsfId: data.gsfId || null };
}

class GPlayClient {
  constructor({
    profile,
    email,
    aasToken = null,
    authToken = null,
    locale = "es_US",
    timezone = "America/Argentina/Buenos_Aires",
    fetchImpl = globalThis.fetch,
  }) {
    if (!profile) throw new Error("Falta profile en GPlayClient.");
    if (!fetchImpl) throw new Error("Falta fetchImpl.");
    this.profile = profile;
    this.email = String(email || "").trim();
    this.aasToken = aasToken ? String(aasToken).trim() : null;
    this.authToken = authToken ? String(authToken).trim() : null;
    this.locale = locale;
    this.timezone = timezone;
    this.fetchImpl = fetchImpl;
    this.gsfIdHex = null;
    this.consistencyToken = null;
    this.deviceConfigToken = null;
    this.dfeCookie = null;
    this.tosToken = null;
  }

  authHeaders() {
    const h = {
      app: ANDROID_VENDING,
      "User-Agent": `GoogleAuth/1.4 (${this.profile.device} ${this.profile.buildId})`,
    };
    if (this.gsfIdHex) h.device = this.gsfIdHex;
    return h;
  }

  defaultFdfeHeaders() {
    const h = {
      "user-agent": buildUserAgent(this.profile),
      "accept-language": this.locale.replace("_", "-"),
      "X-DFE-Encoded-Targets": DEFAULT_DFE_TARGETS,
      "X-DFE-Phenotype": DEFAULT_DFE_PHENOTYPE,
      "X-DFE-Client-Id": "am-android-google",
      "X-DFE-Network-Type": "4",
      "X-DFE-Content-Filters": "",
      "X-Limit-Ad-Tracking-Enabled": "false",
      "X-DFE-Request-Params": "timeoutMs=8000",
    };
    if (this.authToken) h.Authorization = `Bearer ${this.authToken}`;
    if (this.gsfIdHex) h["X-DFE-Device-Id"] = this.gsfIdHex;
    if (this.consistencyToken) h["X-DFE-Device-Checkin-Consistency-Token"] = this.consistencyToken;
    if (this.deviceConfigToken) h["X-DFE-Device-Config-Token"] = this.deviceConfigToken;
    if (this.dfeCookie) h["X-DFE-Cookie"] = this.dfeCookie;
    if (this.profile.simOperator) h["X-DFE-MCCMCN"] = String(this.profile.simOperator);
    return h;
  }

  async checkin() {
    const reqBytes = proto.encode("AndroidCheckinRequest", {
      id: "0",
      checkin: buildCheckinProto(this.profile),
      locale: this.locale,
      timeZone: this.timezone,
      version: 3,
      deviceConfiguration: this.profile.deviceConfiguration,
      fragment: 0,
    });
    const headers = {
      ...this.authHeaders(),
      "content-type": "application/x-protobuf",
      host: "android.clients.google.com",
    };
    const res = await defaultFetchWithTimeout(this.fetchImpl, `${BASE_URL}/checkin`, {
      method: "POST",
      headers,
      body: reqBytes,
    }, 25000);
    if (!res.ok) throw new Error(`Checkin falló (HTTP ${res.status})`);
    const buf = Buffer.from(await res.arrayBuffer());
    const resp = proto.decode("AndroidCheckinResponse", buf);
    this.consistencyToken = resp.deviceCheckinConsistencyToken || null;
    if (resp.androidId != null) {
      this.gsfIdHex = proto.longToUnsignedHex(resp.androidId);
    }
    if (!this.gsfIdHex) {
      throw new Error("Checkin no devolvió androidId (GSF ID).");
    }
    return resp;
  }

  async requestAuthToken() {
    if (!this.email || !this.aasToken) {
      throw new Error("Faltan email y aasToken para autenticar con Google Play.");
    }
    const q = new URLSearchParams({
      sdk_version: String(this.profile.sdkVersion),
      Email: this.email,
      google_play_services_version: String(this.profile.gsfVersion),
      device_country: "us",
      lang: "en",
      callerSig: CALLER_SIG,
      app: "com.android.vending",
      client_sig: CLIENT_SIG,
      callerPkg: ANDROID_VENDING,
      Token: this.aasToken,
      oauth2_foreground: "1",
      token_request_options: "CAA4AVAB",
      check_email: "1",
      system_partition: "1",
      service: PLAY_SERVICE_SCOPE,
    });
    if (this.gsfIdHex) q.set("androidId", this.gsfIdHex);

    const headers = {
      ...this.authHeaders(),
      "content-length": "0",
    };
    const res = await defaultFetchWithTimeout(this.fetchImpl, `${BASE_URL}/auth?${q.toString()}`, {
      method: "POST",
      headers,
      body: Buffer.alloc(0),
    }, 25000);
    const text = await res.text();
    const reply = parseFormReply(text);
    if (reply.error) {
      throw new Error(`Autenticación Play rechazada: ${reply.error}`);
    }
    if (!res.ok || !reply.auth) {
      throw new Error(`Google /auth no devolvió auth token (HTTP ${res.status})`);
    }
    this.authToken = reply.auth;
    return reply.auth;
  }

  async executeFdfe(endpoint, { query = null, body = null, contentType = null } = {}) {
    let url = `${BASE_URL}/fdfe/${endpoint}`;
    if (query) {
      const qs = new URLSearchParams(query).toString();
      if (qs) url += `?${qs}`;
    }
    const headers = this.defaultFdfeHeaders();
    if (contentType) headers["content-type"] = contentType;
    if (body !== null && body.length === 0) headers["content-length"] = "0";

    const res = await defaultFetchWithTimeout(this.fetchImpl, url, {
      method: body !== null ? "POST" : "GET",
      headers,
      body: body !== null ? body : undefined,
    }, 30000);

    const buf = Buffer.from(await res.arrayBuffer());
    let wrapper = null;
    if (buf.length > 0) {
      try {
        wrapper = proto.decode("ResponseWrapper", buf);
      } catch (_) {
        wrapper = null;
      }
    }
    const serverMsg = wrapper && wrapper.commands && wrapper.commands.displayErrorMessage;
    if (serverMsg) {
      throw new Error(`Google Play (/fdfe/${endpoint}): ${serverMsg}`);
    }
    if (!res.ok) {
      throw new Error(`Google Play /fdfe/${endpoint} respondió HTTP ${res.status}`);
    }
    if (!wrapper) {
      throw new Error(`Google Play /fdfe/${endpoint} devolvió una respuesta vacía o ilegible.`);
    }
    return wrapper;
  }

  async uploadDeviceConfig() {
    const reqBytes = proto.encode("UploadDeviceConfigRequest", {
      deviceConfiguration: this.profile.deviceConfiguration,
      manufacturer: this.profile.manufacturer,
    });
    const wrapper = await this.executeFdfe("uploadDeviceConfig", {
      body: reqBytes,
      contentType: "application/x-protobuf",
    });
    const token = wrapper.payload && wrapper.payload.uploadDeviceConfigResponse && wrapper.payload.uploadDeviceConfigResponse.uploadDeviceConfigToken;
    if (!token) throw new Error("uploadDeviceConfig no devolvió uploadDeviceConfigToken.");
    this.deviceConfigToken = token;
    return token;
  }

  async toc() {
    const wrapper = await this.executeFdfe("toc");
    const tocResp = wrapper.payload && wrapper.payload.tocResponse;
    if (!tocResp) throw new Error("toc no devolvió tocResponse.");
    if (tocResp.tosToken || tocResp.tosContent) {
      this.tosToken = tocResp.tosToken || null;
      return { needsTos: true, tosToken: this.tosToken };
    }
    if (tocResp.cookie) this.dfeCookie = tocResp.cookie;
    return { needsTos: false, dfeCookie: this.dfeCookie };
  }

  async acceptTos(tosToken) {
    const form = new URLSearchParams({
      tost: String(tosToken || this.tosToken || ""),
      toscme: "false",
    }).toString();
    await this.executeFdfe("acceptTos", {
      body: Buffer.from(form, "utf8"),
      contentType: "application/x-www-form-urlencoded",
    });
  }

  async login() {
    await this.checkin();
    if (!this.authToken) {
      await this.requestAuthToken();
    }
    await this.uploadDeviceConfig();
    const toc1 = await this.toc();
    if (toc1.needsTos && toc1.tosToken) {
      await this.acceptTos(toc1.tosToken);
      await this.toc();
    }
    return {
      gsfIdHex: this.gsfIdHex,
      deviceConfigToken: this.deviceConfigToken,
      dfeCookie: this.dfeCookie,
    };
  }

  async details(packageName) {
    if (!this.authToken) throw new Error("Sesión no iniciada en Google Play.");
    const wrapper = await this.executeFdfe("details", { query: { doc: packageName } });
    const item = wrapper.payload && wrapper.payload.detailsResponse && wrapper.payload.detailsResponse.item;
    const app = item && item.details && item.details.appDetails;
    if (!item || !app) {
      throw new Error(`La app ${packageName} no devolvió detalles en Google Play.`);
    }
    return {
      packageName: app.packageName || packageName,
      title: item.title || app.title || packageName,
      developerName: app.developerName || item.creator || "",
      versionCode: app.versionCode != null ? Number(app.versionCode) : 0,
      versionName: app.versionString || "",
      infoDownloadSize: app.infoDownloadSize != null ? Number(app.infoDownloadSize) : 0,
      splitIds: Array.isArray(app.splitId) ? app.splitId : [],
      certificateHashes: Array.isArray(app.certificateHash) ? app.certificateHash : [],
    };
  }

  async getDelivery(packageName, versionCode) {
    if (!this.authToken) throw new Error("Sesión no iniciada en Google Play.");
    let vc = versionCode;
    if (!vc || vc <= 0) {
      const det = await this.details(packageName);
      vc = det.versionCode;
    }
    if (!vc || vc <= 0) {
      throw new Error(`No se pudo obtener versionCode para ${packageName}.`);
    }

    const purchaseWrapper = await this.executeFdfe("purchase", {
      query: { ot: "1", doc: packageName, vc: String(vc) },
      body: Buffer.alloc(0),
    });
    const buyResp = purchaseWrapper.payload && purchaseWrapper.payload.buyResponse;
    if (!buyResp) {
      throw new Error(`Google Play /fdfe/purchase no devolvió buyResponse para ${packageName}.`);
    }

    let deliveryData =
      buyResp.purchaseStatusResponse && buyResp.purchaseStatusResponse.appDeliveryData;

    if (!deliveryData || !deliveryData.downloadUrl) {
      if (!buyResp.encodedDeliveryToken) {
        const statusMsg =
          (buyResp.purchaseStatusResponse && (buyResp.purchaseStatusResponse.statusMsg || buyResp.purchaseStatusResponse.statusTitle)) ||
          "No se pudo adquirir (puede ser app paga o restringida por dispositivo/región).";
        throw new Error(`${packageName}: ${statusMsg}`);
      }
      const delivWrapper = await this.executeFdfe("delivery", {
        query: {
          ot: "1",
          doc: packageName,
          vc: String(vc),
          dtok: buyResp.encodedDeliveryToken,
        },
      });
      deliveryData =
        delivWrapper.payload &&
        delivWrapper.payload.deliveryResponse &&
        delivWrapper.payload.deliveryResponse.appDeliveryData;
    }

    if (!deliveryData || !deliveryData.downloadUrl) {
      throw new Error(`Google Play no devolvió URL de descarga para ${packageName} (vc=${vc}).`);
    }

    const cookies = Array.isArray(deliveryData.downloadAuthCookie)
      ? deliveryData.downloadAuthCookie.filter((c) => c && c.name && c.value != null)
      : [];

    const splits = (Array.isArray(deliveryData.splitDeliveryData) ? deliveryData.splitDeliveryData : [])
      .filter((s) => s && s.name && s.downloadUrl)
      .map((s) => ({
        name: s.name,
        downloadUrl: s.downloadUrl,
        downloadSize: Number(s.downloadSize || 0),
        sha1Hex: decodeHashToHex(s.sha1),
        sha256Hex: decodeHashToHex(s.sha256),
      }));

    return {
      versionCode: vc,
      downloadUrl: deliveryData.downloadUrl,
      downloadSize: Number(deliveryData.downloadSize || 0),
      sha1Hex: decodeHashToHex(deliveryData.sha1),
      sha256Hex: decodeHashToHex(deliveryData.sha256),
      cookies,
      splits,
      hasObb: Array.isArray(deliveryData.additionalFile) && deliveryData.additionalFile.length > 0,
    };
  }

  async downloadToFile(url, cookies, destPath, { expectedSha256 = "", expectedSha1 = "", maxBytes = MAX_APK_DOWNLOAD_BYTES } = {}) {
    const headers = {
      "user-agent": buildUserAgent(this.profile),
    };
    if (Array.isArray(cookies) && cookies.length > 0) {
      headers.cookie = cookies.map((c) => `${c.name}=${c.value}`).join("; ");
    }
    const res = await defaultFetchWithTimeout(this.fetchImpl, url, { method: "GET", headers }, 180000);
    if (!res.ok) {
      throw new Error(`Descarga desde CDN de Play falló (HTTP ${res.status}).`);
    }

    const sha256Hash = crypto.createHash("sha256");
    const sha1Hash = crypto.createHash("sha1");
    let totalBytes = 0;

    await new Promise((resolve, reject) => {
      const ws = fs.createWriteStream(destPath);
      const nodeStream = res.body && typeof res.body.getReader === "function"
        ? Readable.fromWeb(res.body)
        : Readable.from(Buffer.from(""));

      const readPromise = res.body && typeof res.body.getReader === "function"
        ? null
        : Promise.resolve(res.arrayBuffer()).then((ab) => Buffer.from(ab));

      if (readPromise) {
        readPromise.then((buf) => {
          totalBytes = buf.length;
          if (totalBytes > maxBytes) {
            ws.destroy();
            reject(new Error(`El archivo excede el tope de ${Math.round(maxBytes / (1024 * 1024))} MB.`));
            return;
          }
          sha256Hash.update(buf);
          sha1Hash.update(buf);
          ws.end(buf, resolve);
        }).catch(reject);
        return;
      }

      nodeStream.on("data", (chunk) => {
        totalBytes += chunk.length;
        if (totalBytes > maxBytes) {
          nodeStream.destroy(new Error(`El archivo excede el tope de ${Math.round(maxBytes / (1024 * 1024))} MB.`));
          ws.destroy();
          return;
        }
        sha256Hash.update(chunk);
        sha1Hash.update(chunk);
      });
      nodeStream.on("error", (err) => {
        ws.destroy();
        reject(err);
      });
      ws.on("error", reject);
      ws.on("finish", resolve);
      nodeStream.pipe(ws);
    });

    const sha256 = sha256Hash.digest("hex");
    const sha1 = sha1Hash.digest("hex");
    if (expectedSha256 && sha256 !== expectedSha256.toLowerCase()) {
      throw new Error(`SHA-256 inválido al descargar desde Google Play (esperado ${expectedSha256}, real ${sha256}).`);
    }
    if (expectedSha1 && sha1 !== expectedSha1.toLowerCase()) {
      throw new Error(`SHA-1 inválido al descargar desde Google Play (esperado ${expectedSha1}, real ${sha1}).`);
    }
    return { sizeBytes: totalBytes, sha256, sha1 };
  }
}

module.exports = {
  GPlayClient,
  exchangeOAuthForAas,
  fetchFromDispenser,
  validateDispenserUrl,
  isPrivateOrDisallowedHost,
  decodeHashToHex,
  parseFormReply,
  MAX_APK_DOWNLOAD_BYTES,
};
