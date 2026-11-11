const SHIM = new URL("./shim.mjs", import.meta.url).href;
const REDIRECT = new Set(["@firebase/rules-unit-testing", "firebase/database"]);

export async function resolve(specifier, context, nextResolve) {
  if (REDIRECT.has(specifier)) return { url: SHIM, shortCircuit: true };
  return nextResolve(specifier, context);
}
