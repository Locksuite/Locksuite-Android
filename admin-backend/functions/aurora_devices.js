"use strict";

/**
 * Perfiles de dispositivo Android para solicitar entregas multi-ABI a Google Play FDFE.
 * Se usan dos perfiles reales representativos de la flota LockSuite:
 *   - arm64_v8a   : Samsung Galaxy F34 5G (Android 14 / SDK 34, 64 bits + compat 32 bits)
 *   - armeabi_v7a : Samsung Galaxy A13 5G (Android 13 / SDK 33, 32 bits p/ CAT S22 y similares)
 */

const COMMON_FEATURES = [
  "android.hardware.sensor.proximity",
  "android.hardware.sensor.accelerometer",
  "android.hardware.sensor.compass",
  "android.hardware.sensor.gyroscope",
  "android.hardware.faketouch",
  "android.hardware.touchscreen",
  "android.hardware.touchscreen.multitouch",
  "android.hardware.touchscreen.multitouch.distinct",
  "android.hardware.camera",
  "android.hardware.camera.autofocus",
  "android.hardware.camera.flash",
  "android.hardware.camera.front",
  "android.hardware.microphone",
  "android.hardware.audio.output",
  "android.hardware.audio.low_latency",
  "android.hardware.bluetooth",
  "android.hardware.bluetooth_le",
  "android.hardware.location",
  "android.hardware.location.gps",
  "android.hardware.location.network",
  "android.hardware.wifi",
  "android.hardware.wifi.direct",
  "android.hardware.telephony",
  "android.hardware.telephony.gsm",
  "android.hardware.telephony.ims",
  "android.hardware.usb.accessory",
  "android.hardware.usb.host",
  "android.hardware.screen.portrait",
  "android.hardware.screen.landscape",
  "android.hardware.fingerprint",
  "android.hardware.ram.normal",
  "android.software.app_widgets",
  "android.software.autofill",
  "android.software.backup",
  "android.software.connectionservice",
  "android.software.controls",
  "android.software.device_admin",
  "android.software.home_screen",
  "android.software.input_methods",
  "android.software.managed_users",
  "android.software.picture_in_picture",
  "android.software.print",
  "android.software.sip",
  "android.software.sip.voip",
  "android.software.telecom",
  "android.software.verified_boot",
  "android.software.voice_recognizers",
  "android.software.webview",
  "android.software.window_magnification",
  "com.google.android.feature.GOOGLE_BUILD",
  "com.google.android.feature.GOOGLE_EXPERIENCE",
];

const COMMON_SHARED_LIBRARIES = [
  "android.ext.shared",
  "android.test.base",
  "android.test.mock",
  "android.test.runner",
  "com.google.android.gms",
  "javax.obex",
  "org.apache.http.legacy",
];

const COMMON_LOCALES = [
  "en", "en_AU", "en_CA", "en_GB", "en_US",
  "es", "es_419", "es_AR", "es_ES", "es_MX", "es_US",
  "he", "iw", "iw_IL",
];

const COMMON_GL_EXTENSIONS = [
  "GL_AMD_compressed_ATC_texture",
  "GL_EXT_debug_marker",
  "GL_EXT_discard_framebuffer",
  "GL_EXT_multisampled_render_to_texture",
  "GL_EXT_texture_filter_anisotropic",
  "GL_OES_compressed_ETC1_RGB8_texture",
  "GL_OES_depth24",
  "GL_OES_EGL_image",
  "GL_OES_EGL_image_external",
  "GL_OES_packed_depth_stencil",
  "GL_OES_rgb8_rgba8",
  "GL_OES_standard_derivatives",
  "GL_OES_texture_float",
  "GL_OES_vertex_array_object",
];

const DEVICE_PROFILES = {
  arm64_v8a: {
    key: "arm64_v8a",
    primaryAbi: "arm64-v8a",
    abis: ["arm64-v8a"],
    userReadableName: "Samsung F34 5G",
    vendingVersion: "84582130",
    vendingVersionString: "45.8.21-31 [0] [PR] 747433787",
    gsfVersion: 251434027,
    buildId: "UP1A.231005.007",
    release: "14",
    sdkVersion: 34,
    fingerprint: "samsung/m34xins/m34x:14/UP1A.231005.007/E346BXXU4CXE1:user/release-keys",
    hardware: "s5e8825",
    brand: "samsung",
    radio: "E346BXXU4CXE1,E346BXXU4CXE1",
    bootloader: "E346BXXU4CXE1",
    device: "m34x",
    model: "SM-E346B",
    manufacturer: "samsung",
    product: "m34xins",
    client: "android-google",
    cellOperator: "310",
    simOperator: "38",
    roaming: "mobile-notroaming",
    deviceConfiguration: {
      touchScreen: 3,
      keyboard: 1,
      navigation: 1,
      screenLayout: 2,
      hasHardKeyboard: false,
      hasFiveWayNavigation: false,
      screenDensity: 450,
      glEsVersion: 196610,
      systemSharedLibrary: COMMON_SHARED_LIBRARIES,
      systemAvailableFeature: COMMON_FEATURES,
      nativePlatform: ["arm64-v8a", "armeabi-v7a", "armeabi"],
      screenWidth: 1080,
      screenHeight: 2221,
      systemSupportedLocale: COMMON_LOCALES,
      glExtension: COMMON_GL_EXTENSIONS,
      deviceClass: 0,
      maxApkDownloadSizeMb: 50,
      smallestScreenWidthDP: 360,
      lowRamDevice: 0,
      totalMemoryBytes: "8354971648",
      maxNumOf_CPUCores: 8,
    },
  },
  armeabi_v7a: {
    key: "armeabi_v7a",
    primaryAbi: "armeabi-v7a",
    abis: ["armeabi-v7a"],
    userReadableName: "Samsung A13 5G (32-bit)",
    vendingVersion: "84582130",
    vendingVersionString: "45.8.21-31 [0] [PR] 747433787",
    gsfVersion: 251434027,
    buildId: "TP1A.220624.014",
    release: "13",
    sdkVersion: 33,
    fingerprint: "samsung/a13xsq/a13x:13/TP1A.220624.014/A136USQSCDXL2:user/release-keys",
    hardware: "mt6833",
    brand: "samsung",
    radio: "A136USQSCDXL2",
    bootloader: "A136USQSCDXL2",
    device: "a13x",
    model: "SM-A136U",
    manufacturer: "samsung",
    product: "a13xsq",
    client: "android-google",
    cellOperator: "310",
    simOperator: "38",
    roaming: "mobile-notroaming",
    deviceConfiguration: {
      touchScreen: 3,
      keyboard: 1,
      navigation: 1,
      screenLayout: 2,
      hasHardKeyboard: false,
      hasFiveWayNavigation: false,
      screenDensity: 300,
      glEsVersion: 196610,
      systemSharedLibrary: COMMON_SHARED_LIBRARIES,
      systemAvailableFeature: COMMON_FEATURES,
      nativePlatform: ["armeabi-v7a", "armeabi"],
      screenWidth: 720,
      screenHeight: 1600,
      systemSupportedLocale: COMMON_LOCALES,
      glExtension: COMMON_GL_EXTENSIONS,
      deviceClass: 0,
      maxApkDownloadSizeMb: 50,
      smallestScreenWidthDP: 360,
      lowRamDevice: 0,
      totalMemoryBytes: "4177485824",
      maxNumOf_CPUCores: 8,
    },
  },
};

function buildUserAgent(profile) {
  const abis = (profile.deviceConfiguration && profile.deviceConfiguration.nativePlatform || []).join(";");
  return (
    `Android-Finsky/${profile.vendingVersionString} ` +
    `(api=3,versionCode=${profile.vendingVersion},sdk=${profile.sdkVersion},` +
    `device=${profile.device},hardware=${profile.hardware},product=${profile.product},` +
    `platformVersionRelease=${profile.release},model=${profile.model},buildId=${profile.buildId},` +
    `isWideScreen=0,supportedAbis=${abis})`
  );
}

function buildCheckinProto(profile, nowMs = Date.now()) {
  return {
    build: {
      id: profile.fingerprint,
      product: profile.hardware,
      carrier: profile.brand,
      radio: profile.radio,
      bootloader: profile.bootloader,
      client: profile.client,
      timestamp: String(Math.floor(nowMs / 1000)),
      googleServices: profile.gsfVersion,
      device: profile.device,
      sdkVersion: profile.sdkVersion,
      model: profile.model,
      manufacturer: profile.manufacturer,
      buildProduct: profile.product,
      otaInstalled: false,
    },
    lastCheckinMsec: "0",
    cellOperator: profile.cellOperator,
    simOperator: profile.simOperator,
    roaming: profile.roaming,
    userNumber: 0,
  };
}

module.exports = {
  DEVICE_PROFILES,
  buildUserAgent,
  buildCheckinProto,
};
