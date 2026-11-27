/* eslint-disable */
/**
 * dominios_clasificar.js — Clasificador asistido para la auditoría de dominios bloqueados.
 * Solo sugiere ("abrir" / "bloquear" / "revisar"); la decisión final siempre es del administrador.
 */
(function (root) {
  "use strict";

  var ACCION_ABRIR = "abrir";
  var ACCION_BLOQUEAR = "bloquear";
  var ACCION_REVISAR = "revisar";

  var REGLAS = [
    {
      s: ["supl.google.com", "supl.mediatek.com"],
      a: ACCION_ABRIR, c: "GPS asistido", k: "media",
      m: "Ayuda a que el GPS encuentre ubicación rápido (Waze, Uber, DiDi). No trae contenido web."
    },
    {
      s: ["time.android.com", "time.google.com", "pool.ntp.org", "ntp.org"],
      a: ACCION_ABRIR, c: "Hora del equipo", k: "alta",
      m: "Sincroniza la hora. Sin hora correcta fallan los certificados HTTPS."
    },
    {
      s: ["recaptcha.net", "hcaptcha.com", "arkoselabs.com", "funcaptcha.com", "challenges.cloudflare.com"],
      a: ACCION_ABRIR, c: "Captcha de inicio de sesión", k: "media",
      m: "Los logins de bancos y billeteras piden resolver este captcha."
    },
    {
      s: ["android-safebrowsing.google.com"],
      a: ACCION_BLOQUEAR, c: "Google: navegación segura", k: "media",
      m: "Lista de sitios para navegadores/WebView. Ninguna app esencial lo necesita."
    },
    {
      x: true,
      s: ["www.google.com", "google.com", "www.google.com.ar", "google.com.ar", "search.google.com", "ogs.google.com", "ogads-pa.clients6.google.com"],
      a: ACCION_BLOQUEAR, c: "Buscador de Google", k: "alta",
      m: "Abrirlo habilitaría buscar y navegar en Google desde cualquier app."
    },
    {
      s: ["lh3.googleusercontent.com", "lh4.googleusercontent.com", "lh5.googleusercontent.com", "lh6.googleusercontent.com", "googleusercontent.com", "ggpht.com"],
      a: ACCION_REVISAR, c: "Fotos de Google", k: "media",
      m: "Avatares e imágenes adjuntas. Abrir solo para la app que lo necesite."
    },
    {
      s: ["wallet.google.com", "pay.google.com", "payments.google.com"],
      a: ACCION_REVISAR, c: "Google Pay", k: "media",
      m: "Pagos con Google Wallet / Pay. Abrir solo si se usa."
    },
    {
      s: ["amplitude.com", "sentry.io", "bugsnag.com", "instabug.com", "crashlytics.com", "app-measurement.com",
          "google-analytics.com", "analytics.google.com", "googletagmanager.com", "appsflyersdk.com", "appsflyer.com",
          "singular.net", "adjust.com", "adjust.io", "kochava.com", "tenjin.com", "branch.io", "mixpanel.com",
          "segment.io", "segment.com", "heap.io", "heapanalytics.com", "fullstory.com", "hotjar.com", "hotjar.io",
          "datadoghq.com", "newrelic.com", "nr-data.net", "adobedtm.com", "demdex.net", "omtrdc.net", "2o7.net",
          "we-stats.com", "clevertap-prod.com", "clevertap.com", "braze.com", "appboy.com", "iterable.com",
          "airship.com", "urbanairship.com", "optimizely.com", "launchdarkly.com", "statsigapi.net", "split.io",
          "qualtrics.com", "medallia.com", "mouseflow.com", "smartlook.com", "contentsquare.net", "tealiumiq.com",
          "tiqcdn.com", "quantserve.com", "scorecardresearch.com", "moatads.com",
          "furycloud.io", "melidata.mercadolibre.com", "melidata.mercadolibre.com.ar"],
      a: ACCION_BLOQUEAR, c: "Analítica / telemetría", k: "alta",
      m: "Telemetría y seguimiento de uso. No hace falta para usar la app."
    },
    {
      s: ["doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com", "applovin.com",
          "unityads.unity3d.com", "ironsrc.com", "supersonicads.com", "mopub.com", "inmobi.com", "vungle.com",
          "chartboost.com", "adcolony.com", "criteo.com", "criteo.net", "taboola.com", "outbrain.com", "an.facebook.com",
          "adnxs.com", "rubiconproject.com", "pubmatic.com", "openx.net", "adsrvr.org", "casalemedia.com", "smaato.net",
          "tapjoy.com", "fyber.com", "digitalturbine.com", "liftoff.io", "mintegral.com", "pangleglobal.com"],
      a: ACCION_BLOQUEAR, c: "Anuncios", k: "alta",
      m: "Red de publicidad."
    },
    {
      s: ["facebook.com", "fbcdn.net", "fb.com", "instagram.com", "cdninstagram.com", "tiktok.com", "tiktokv.com",
          "tiktokcdn.com", "byteoversea.com", "ibytedtos.com", "twitter.com", "x.com", "twimg.com", "t.co",
          "youtube.com", "youtu.be", "ytimg.com", "googlevideo.com", "youtube-nocookie.com", "spotify.com", "scdn.co",
          "netflix.com", "nflxvideo.net", "twitch.tv", "ttvnw.net", "reddit.com", "redd.it", "redditmedia.com",
          "snapchat.com", "sc-cdn.net", "pinterest.com", "pinimg.com", "linkedin.com", "licdn.com", "discord.com",
          "discordapp.com", "telegram.org", "t.me", "whatsapp.com", "whatsapp.net", "imgur.com", "giphy.com",
          "tenor.com", "tenor.co", "vimeo.com", "dailymotion.com", "bitmoji.com", "kwai.com"],
      a: ACCION_BLOQUEAR, c: "Redes sociales / video / música", k: "alta",
      m: "Contenido social/multimedia que abriría navegación no kosher."
    },
    {
      s: ["mediatek.com", "miui.com", "xiaomi.com", "mi.com", "xiaomiev.com", "hicloud.com", "huawei.com", "hihonor.com",
          "baidu.com", "bdstatic.com", "duoqin.com"],
      a: ACCION_BLOQUEAR, c: "Fabricante / telemetría ROM", k: "media",
      m: "Servidores del fabricante del equipo."
    },
    {
      s: ["apple.com", "cdn-apple.com", "icloud.com", "mzstatic.com"],
      a: ACCION_BLOQUEAR, c: "Apple", k: "alta",
      m: "Recursos de «Iniciar sesión con Apple»."
    },
    {
      s: ["cloudfront.net", "akamaihd.net", "akamaized.net", "akamai.net", "edgekey.net", "edgesuite.net", "fastly.net",
          "azureedge.net", "amazonaws.com", "cdn77.org", "b-cdn.net", "vercel.app", "web.app", "firebaseapp.com",
          "appspot.com", "herokuapp.com", "netlify.app", "github.io"],
      a: ACCION_REVISAR, c: "CDN / hosting compartido", k: "media",
      m: "Hosting compartido: abrir solo el host exacto si la app lo necesita."
    }
  ];

  var BASES_GOOGLE = ["google.com", "google.com.ar", "gstatic.com", "googleapis.com", "gvt1.com", "gvt2.com", "gvt3.com"];
  var PREFIJOS_TELEMETRIA = ["o11y", "melidata", "telemetry", "metrics", "analytics", "tracking", "crashlytics", "beacon"];
  var ETIQUETAS_ANUNCIO = ["ad", "ads", "adserver", "adservice", "adtrack", "adsystem", "adsassets", "adx", "advertising"];

  function normalizar(raw) {
    var d = String(raw == null ? "" : raw).trim().toLowerCase();
    var esquema = d.indexOf("://");
    if (esquema >= 0) d = d.slice(esquema + 3);
    d = d.split("/")[0].split("?")[0].split("#")[0].split(":")[0];
    while (d.indexOf("*.") === 0 || d.charAt(0) === ".") d = d.charAt(0) === "." ? d.slice(1) : d.slice(2);
    return d.replace(/\.+$/, "");
  }

  function coincide(dominio, sufijo) {
    return dominio === sufijo || dominio.slice(-(sufijo.length + 1)) === "." + sufijo;
  }

  function clasificar(raw) {
    var d = normalizar(raw);
    if (!d || d.indexOf(".") < 0) {
      return { accion: ACCION_REVISAR, categoria: "No es un dominio", motivo: "No parece un dominio válido.", confianza: "alta", dominio: d };
    }
    for (var i = 0; i < REGLAS.length; i++) {
      var r = REGLAS[i];
      for (var j = 0; j < r.s.length; j++) {
        if (r.x ? d === r.s[j] : coincide(d, r.s[j])) {
          return { accion: r.a, categoria: r.c, motivo: r.m, confianza: r.k, dominio: d };
        }
      }
    }
    var etiquetas = d.split(".");
    for (var k = 0; k < etiquetas.length - 2; k++) {
      if (ETIQUETAS_ANUNCIO.indexOf(etiquetas[k]) >= 0) {
        return { accion: ACCION_BLOQUEAR, categoria: "Anuncios", confianza: "media", dominio: d,
          motivo: "El host parece de publicidad («" + etiquetas[k] + ".»)." };
      }
    }
    for (var t = 0; t < etiquetas.length - 2; t++) {
      for (var p = 0; p < PREFIJOS_TELEMETRIA.length; p++) {
        if (etiquetas[t].indexOf(PREFIJOS_TELEMETRIA[p]) === 0) {
          return { accion: ACCION_BLOQUEAR, categoria: "Analítica / telemetría", confianza: "media", dominio: d,
            motivo: "El host parece telemetría («" + etiquetas[t] + "»)." };
        }
      }
    }
    for (var g = 0; g < BASES_GOOGLE.length; g++) {
      if (coincide(d, BASES_GOOGLE[g])) {
        return { accion: ACCION_REVISAR, categoria: "Google (sin clasificar)", confianza: "baja", dominio: d,
          motivo: "Servicio de Google no listado. Abrirlo solo si una app falla al aparecer." };
      }
    }
    return {
      accion: ACCION_REVISAR, categoria: "Desconocido", confianza: "baja", dominio: d,
      motivo: "No está en el catálogo. Abrilo solo si la app deja de funcionar sin este host."
    };
  }

  var api = {
    ACCION_ABRIR: ACCION_ABRIR,
    ACCION_BLOQUEAR: ACCION_BLOQUEAR,
    ACCION_REVISAR: ACCION_REVISAR,
    normalizar: normalizar,
    clasificar: clasificar
  };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.DomClasif = api;
})(typeof self !== "undefined" ? self : this);
