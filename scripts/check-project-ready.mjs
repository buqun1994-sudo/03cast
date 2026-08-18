#!/usr/bin/env node

import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { join, resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const root = resolve(scriptDir, "..");

const failures = [];
const warnings = [];

function read(relativePath) {
  const path = join(root, relativePath);
  if (!existsSync(path)) {
    failures.push(`缺少文件：${relativePath}`);
    return "";
  }
  return readFileSync(path, "utf8");
}

function runCheck(label, relativePath) {
  const scriptPath = join(root, relativePath);
  if (!existsSync(scriptPath)) {
    failures.push(`缺少脚本：${relativePath}`);
    return;
  }

  const result = spawnSync(process.execPath, [scriptPath], {
    cwd: root,
    encoding: "utf8",
  });
  if (result.status !== 0) {
    const output = `${result.stdout || ""}${result.stderr || ""}`.trim();
    failures.push(`${label}失败：${output || "无输出"}`);
  }
}

const stateText = read(".new-project-template/state.json");
let state = null;
if (stateText) {
  try {
    state = JSON.parse(stateText);
  } catch {
    failures.push(".new-project-template/state.json 不是合法 JSON");
  }
}

if (!state || state.state !== "initialized") {
  failures.push("项目尚未初始化：必须先运行 project-bootstrap 或 scripts/init-project.mjs");
}

if (state) {
  for (const key of ["projectName", "projectType", "description", "sourceDir", "checkCommand"]) {
    if (!state[key]) {
      failures.push(`初始化状态缺少字段：${key}`);
    }
  }
}

const agents = read("AGENTS.md");
const architecture = read("docs/architecture/项目长期总纲.md");
const product = read("docs/product/产品需求基线.md");
const testing = read("docs/testing/验证矩阵.md");

for (const [file, content] of [
  ["AGENTS.md", agents],
  ["docs/architecture/项目长期总纲.md", architecture],
  ["docs/product/产品需求基线.md", product],
  ["docs/testing/验证矩阵.md", testing],
]) {
  if (/INIT_REQUIRED|待初始化状态|不是具体项目/.test(content)) {
    failures.push(`${file} 仍包含模板态文本`);
  }
}

if (state?.projectName) {
  for (const [file, content] of [
    ["AGENTS.md", agents],
    ["docs/architecture/项目长期总纲.md", architecture],
    ["docs/product/产品需求基线.md", product],
  ]) {
    if (!content.includes(state.projectName)) {
      failures.push(`${file} 未写入项目名称：${state.projectName}`);
    }
  }
}

if (state?.projectType && !existsSync(join(root, "docs", "blueprints", `${state.projectType}.md`))) {
  failures.push(`缺少项目类型 blueprint：${state.projectType}`);
}

if (/待补齐/.test(product)) {
  warnings.push("产品需求基线仍有待补齐项，允许继续，但 L2 以上任务前应补齐核心路径和验收口径。");
}

if (/待补/.test(testing)) {
  warnings.push("验证矩阵仍有待补项，代码施工前应补齐技术栈快检和相关测试入口。");
}

runCheck("skills 快检", "scripts/check-skills.mjs");

if (failures.length > 0) {
  console.error("项目就绪检查失败：");
  for (const failure of failures) {
    console.error(`- ${failure}`);
  }
  for (const warning of warnings) {
    console.warn(`提示：${warning}`);
  }
  process.exit(1);
}

console.log("项目就绪检查通过。");
for (const warning of warnings) {
  console.warn(`提示：${warning}`);
}
