const fs = require("node:fs/promises");
const path = require("node:path");
const { minify } = require("terser");

async function main() {
  const [input, output] = process.argv.slice(2);
  const template = await fs.readFile(input, "utf8");
  // PageEdgeController substitutes expressions and one block of statements after loading the asset.
  const placeholders = new Map();
  const prefix = "__SUMMIT_TEMPLATE_";
  if (template.includes(prefix)) throw new Error("Template marker collides with script source");
  const parseable = template.replace(/%([A-Z_]+)%/g, (token, name) => {
    const marker = prefix + name + (name === "PILL_METRICS" ? "()" : "");
    placeholders.set(marker, token);
    return marker;
  });
  const { code } = await minify(parseable, {
    compress: false,
    mangle: { properties: false },
    format: { comments: false }
  });
  let result = code;
  for (const [marker, token] of placeholders) {
    result = result.replaceAll(marker, token);
  }
  if (result.includes(prefix)) throw new Error("Unresolved template marker");
  await fs.mkdir(path.dirname(output), { recursive: true });
  await fs.writeFile(output, result);
}

main().catch(error => { console.error(error); process.exitCode = 1; });
