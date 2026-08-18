#!/usr/bin/env node

import { existsSync, readFileSync, readdirSync } from "node:fs";
import { extname, join, resolve } from "node:path";

const values = process.argv.slice(2);
const args = Object.fromEntries(values.flatMap((token, index) => token.startsWith("--") ? [[token.slice(2), values[index + 1]]] : []));
const directory = resolve(args.dir ?? "src/locales");

if (!existsSync(directory)) {
  console.error(`多语言检查失败：目录不存在 ${directory}`);
  process.exit(1);
}

function flatten(value, prefix = "", result = new Map()) {
  if (typeof value === "string") {
    result.set(prefix, value);
    return result;
  }
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error(`key ${prefix || "<root>"} 必须是字符串或对象`);
  for (const [key, child] of Object.entries(value)) flatten(child, prefix ? `${prefix}.${key}` : key, result);
  return result;
}

const files = readdirSync(directory).filter((file) => extname(file) === ".json").sort();
if (files.length < 2) {
  console.error("多语言检查失败：至少需要 fallback 与另一份 locale JSON");
  process.exit(1);
}

const failures = [];
const catalogs = new Map();
for (const file of files) {
  try {
    const catalog = flatten(JSON.parse(readFileSync(join(directory, file), "utf8")));
    for (const [key, value] of catalog) if (!value.trim()) failures.push(`${file} 的 ${key} 为空`);
    catalogs.set(file, catalog);
  } catch (error) {
    failures.push(`${file} 无法解析：${error.message}`);
  }
}

const referenceFile = files[0];
const referenceKeys = new Set(catalogs.get(referenceFile)?.keys() ?? []);
for (const [file, catalog] of catalogs) {
  const keys = new Set(catalog.keys());
  for (const key of referenceKeys) if (!keys.has(key)) failures.push(`${file} 缺少 key：${key}`);
  for (const key of keys) if (!referenceKeys.has(key)) failures.push(`${file} 多出 key：${key}`);
}

if (failures.length > 0) {
  console.error("多语言检查失败：");
  failures.forEach((failure) => console.error(`- ${failure}`));
  process.exit(1);
}

console.log(`多语言检查通过：${files.length} 个 locale，${referenceKeys.size} 个 key。`);
