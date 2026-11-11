// Redirige los dos paquetes que importa test_rules.mjs al evaluador sin emulador (shim.mjs).
//
//     cd tools/rules_tests
//     node --import ./offline/register.mjs test_rules.mjs
//     RULES=/ruta/a/otras.rules.json node --import ./offline/register.mjs test_rules.mjs
//
// Ver el encabezado de shim.mjs para saber qué imita y qué no.
import { register } from "node:module";
register("./hooks.mjs", import.meta.url);
