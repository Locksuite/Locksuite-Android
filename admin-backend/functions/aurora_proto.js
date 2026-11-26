"use strict";

/**
 * Esquema Protobuf minimalista del protocolo Google Play FDFE / Checkin.
 * Armado en memoria con protobufjs (sin archivos .proto externos).
 * Números de campo verificados contra googleplay-protobuf / EFForg/rs-google-play.
 */
const protobuf = require("protobufjs");

const SCHEMA_JSON = {
  nested: {
    gp: {
      nested: {
        AndroidBuildProto: {
          fields: {
            id: { type: "string", id: 1 },
            product: { type: "string", id: 2 },
            carrier: { type: "string", id: 3 },
            radio: { type: "string", id: 4 },
            bootloader: { type: "string", id: 5 },
            client: { type: "string", id: 6 },
            timestamp: { type: "int64", id: 7 },
            googleServices: { type: "int32", id: 8 },
            device: { type: "string", id: 9 },
            sdkVersion: { type: "int32", id: 10 },
            model: { type: "string", id: 11 },
            manufacturer: { type: "string", id: 12 },
            buildProduct: { type: "string", id: 13 },
            otaInstalled: { type: "bool", id: 14 },
          },
        },
        AndroidCheckinProto: {
          fields: {
            build: { type: "AndroidBuildProto", id: 1 },
            lastCheckinMsec: { type: "int64", id: 2 },
            cellOperator: { type: "string", id: 6 },
            simOperator: { type: "string", id: 7 },
            roaming: { type: "string", id: 8 },
            userNumber: { type: "int32", id: 9 },
          },
        },
        DeviceConfigurationProto: {
          fields: {
            touchScreen: { type: "int32", id: 1 },
            keyboard: { type: "int32", id: 2 },
            navigation: { type: "int32", id: 3 },
            screenLayout: { type: "int32", id: 4 },
            hasHardKeyboard: { type: "bool", id: 5 },
            hasFiveWayNavigation: { type: "bool", id: 6 },
            screenDensity: { type: "int32", id: 7 },
            glEsVersion: { type: "int32", id: 8 },
            systemSharedLibrary: { rule: "repeated", type: "string", id: 9 },
            systemAvailableFeature: { rule: "repeated", type: "string", id: 10 },
            nativePlatform: { rule: "repeated", type: "string", id: 11 },
            screenWidth: { type: "int32", id: 12 },
            screenHeight: { type: "int32", id: 13 },
            systemSupportedLocale: { rule: "repeated", type: "string", id: 14 },
            glExtension: { rule: "repeated", type: "string", id: 15 },
            deviceClass: { type: "int32", id: 16 },
            maxApkDownloadSizeMb: { type: "int32", id: 17 },
            smallestScreenWidthDP: { type: "int32", id: 18 },
            lowRamDevice: { type: "int32", id: 19 },
            totalMemoryBytes: { type: "int64", id: 20 },
            maxNumOf_CPUCores: { type: "int32", id: 21 },
          },
        },
        AndroidCheckinRequest: {
          fields: {
            id: { type: "int64", id: 2 },
            digest: { type: "string", id: 3 },
            checkin: { type: "AndroidCheckinProto", id: 4 },
            locale: { type: "string", id: 6 },
            loggingId: { type: "int64", id: 7 },
            timeZone: { type: "string", id: 12 },
            version: { type: "int32", id: 14 },
            deviceConfiguration: { type: "DeviceConfigurationProto", id: 18 },
            fragment: { type: "int32", id: 20 },
          },
        },
        AndroidCheckinResponse: {
          fields: {
            statsOk: { type: "bool", id: 1 },
            timeMsec: { type: "int64", id: 3 },
            digest: { type: "string", id: 4 },
            marketOk: { type: "bool", id: 6 },
            androidId: { type: "fixed64", id: 7 },
            securityToken: { type: "fixed64", id: 8 },
            deviceCheckinConsistencyToken: { type: "string", id: 12 },
          },
        },
        UploadDeviceConfigRequest: {
          fields: {
            deviceConfiguration: { type: "DeviceConfigurationProto", id: 1 },
            manufacturer: { type: "string", id: 2 },
            gcmRegistrationId: { type: "string", id: 3 },
          },
        },
        UploadDeviceConfigResponse: {
          fields: {
            uploadDeviceConfigToken: { type: "string", id: 1 },
          },
        },
        TocResponse: {
          fields: {
            tosContent: { type: "string", id: 3 },
            tosToken: { type: "string", id: 7 },
            requiresUploadDeviceConfig: { type: "bool", id: 11 },
            cookie: { type: "string", id: 22 },
          },
        },
        AcceptTosResponse: {
          fields: {},
        },
        ServerCommands: {
          fields: {
            clearCache: { type: "bool", id: 1 },
            displayErrorMessage: { type: "string", id: 2 },
          },
        },
        AppDetails: {
          fields: {
            developerName: { type: "string", id: 1 },
            versionCode: { type: "int64", id: 3 },
            versionString: { type: "string", id: 4 },
            title: { type: "string", id: 5 },
            infoDownloadSize: { type: "int64", id: 9 },
            packageName: { type: "string", id: 14 },
            infoUpdatedOn: { type: "string", id: 16 },
            certificateHash: { rule: "repeated", type: "string", id: 19 },
            variesWithDevice: { type: "bool", id: 21 },
            splitId: { rule: "repeated", type: "string", id: 25 },
            targetSdkVersion: { type: "int32", id: 32 },
          },
        },
        DocumentDetails: {
          fields: {
            appDetails: { type: "AppDetails", id: 1 },
          },
        },
        Item: {
          fields: {
            id: { type: "string", id: 1 },
            title: { type: "string", id: 5 },
            creator: { type: "string", id: 6 },
            details: { type: "DocumentDetails", id: 13 },
          },
        },
        DetailsResponse: {
          fields: {
            item: { type: "Item", id: 4 },
          },
        },
        HttpCookie: {
          fields: {
            name: { type: "string", id: 1 },
            value: { type: "string", id: 2 },
          },
        },
        AppFileMetadata: {
          fields: {
            fileType: { type: "int32", id: 1 },
            versionCode: { type: "int32", id: 2 },
            size: { type: "int64", id: 3 },
            downloadUrl: { type: "string", id: 4 },
            sha1: { type: "string", id: 8 },
          },
        },
        SplitDeliveryData: {
          fields: {
            name: { type: "string", id: 1 },
            downloadSize: { type: "int64", id: 2 },
            compressedSize: { type: "int64", id: 3 },
            sha1: { type: "string", id: 4 },
            downloadUrl: { type: "string", id: 5 },
            compressedDownloadUrl: { type: "string", id: 6 },
            sha256: { type: "string", id: 9 },
          },
        },
        AndroidAppDeliveryData: {
          fields: {
            downloadSize: { type: "int64", id: 1 },
            sha1: { type: "string", id: 2 },
            downloadUrl: { type: "string", id: 3 },
            additionalFile: { rule: "repeated", type: "AppFileMetadata", id: 4 },
            downloadAuthCookie: { rule: "repeated", type: "HttpCookie", id: 5 },
            compressedDownloadUrl: { type: "string", id: 13 },
            compressedSize: { type: "int64", id: 14 },
            splitDeliveryData: { rule: "repeated", type: "SplitDeliveryData", id: 15 },
            sha256: { type: "string", id: 19 },
          },
        },
        PurchaseStatusResponse: {
          fields: {
            status: { type: "int32", id: 1 },
            statusMsg: { type: "string", id: 2 },
            statusTitle: { type: "string", id: 3 },
            appDeliveryData: { type: "AndroidAppDeliveryData", id: 8 },
          },
        },
        BuyResponse: {
          fields: {
            purchaseStatusResponse: { type: "PurchaseStatusResponse", id: 39 },
            encodedDeliveryToken: { type: "string", id: 55 },
          },
        },
        DeliveryResponse: {
          fields: {
            status: { type: "int32", id: 1 },
            appDeliveryData: { type: "AndroidAppDeliveryData", id: 2 },
          },
        },
        Payload: {
          fields: {
            detailsResponse: { type: "DetailsResponse", id: 2 },
            buyResponse: { type: "BuyResponse", id: 4 },
            tocResponse: { type: "TocResponse", id: 6 },
            purchaseStatusResponse: { type: "PurchaseStatusResponse", id: 8 },
            deliveryResponse: { type: "DeliveryResponse", id: 21 },
            acceptTosResponse: { type: "AcceptTosResponse", id: 22 },
            androidCheckinResponse: { type: "AndroidCheckinResponse", id: 26 },
            uploadDeviceConfigResponse: { type: "UploadDeviceConfigResponse", id: 28 },
          },
        },
        ResponseWrapper: {
          fields: {
            payload: { type: "Payload", id: 1 },
            commands: { type: "ServerCommands", id: 2 },
          },
        },
      },
    },
  },
};

const root = protobuf.Root.fromJSON(SCHEMA_JSON);

function lookup(typeName) {
  return root.lookupType("gp." + typeName);
}

function encode(typeName, obj) {
  const T = lookup(typeName);
  const msg = T.fromObject(obj || {});
  return Buffer.from(T.encode(msg).finish());
}

function decode(typeName, buf) {
  const T = lookup(typeName);
  const bytes = Buffer.isBuffer(buf) ? new Uint8Array(buf) : new Uint8Array(Buffer.from(buf));
  const msg = T.decode(bytes);
  return T.toObject(msg, { longs: String, enums: Number, defaults: false });
}

/**
 * Convierte un valor fixed64/int64 (Long, string decimal, number o BigInt) a hex sin signo (64 bits).
 * Crítico para androidId/gsfId en el header X-DFE-Device-Id y androidId en /auth.
 */
function longToUnsignedHex(val) {
  if (val == null || val === "") return "";
  if (typeof val === "object" && typeof val.toUnsigned === "function") {
    return val.toUnsigned().toString(16);
  }
  const bi = BigInt.asUintN(64, BigInt(String(val)));
  return bi.toString(16);
}

module.exports = {
  root,
  encode,
  decode,
  longToUnsignedHex,
};
