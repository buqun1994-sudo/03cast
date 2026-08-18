#!/usr/bin/env node

import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { join, resolve, dirname, basename } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const root = resolve(scriptDir, "..");
const skillsRoot = join(root, ".agents", "skills");

const failures = [];

function read(relativePath) {
  const path = join(root, relativePath);
  if (!existsSync(path)) {
    failures.push(`缺少文件：${relativePath}`);
    return "";
  }
  return readFileSync(path, "utf8");
}

function parseFrontmatter(content, skillName) {
  const normalized = content.replace(/\r\n/g, "\n");
  if (!normalized.startsWith("---\n")) {
    failures.push(`${skillName}/SKILL.md 缺少 YAML frontmatter 起始分隔符`);
    return {};
  }

  const endIndex = normalized.indexOf("\n---\n", 4);
  if (endIndex === -1) {
    failures.push(`${skillName}/SKILL.md 缺少 YAML frontmatter 结束分隔符`);
    return {};
  }

  const frontmatter = normalized.slice(4, endIndex).trim();
  const fields = {};
  for (const line of frontmatter.split("\n")) {
    const match = line.match(/^([A-Za-z][A-Za-z0-9_-]*):\s*(.*)$/);
    if (!match) {
      failures.push(`${skillName}/SKILL.md frontmatter 行格式不合法：${line}`);
      continue;
    }
    fields[match[1]] = match[2].replace(/^["']|["']$/g, "").trim();
  }
  return fields;
}

function checkSkill(skillDir) {
  const skillName = basename(skillDir);
  if (!/^[a-z0-9-]{1,63}$/.test(skillName)) {
    failures.push(`skill 目录名不合法：${skillName}`);
  }

  const skillPath = join(skillDir, "SKILL.md");
  if (!existsSync(skillPath)) {
    failures.push(`缺少 ${skillName}/SKILL.md`);
    return;
  }

  const content = readFileSync(skillPath, "utf8");
  const fields = parseFrontmatter(content, skillName);

  if (!fields.name) {
    failures.push(`${skillName}/SKILL.md 缺少 name`);
  } else if (fields.name !== skillName) {
    failures.push(`${skillName}/SKILL.md name 必须等于目录名`);
  }

  if (!fields.description || fields.description.length < 20) {
    failures.push(`${skillName}/SKILL.md description 过短或缺失`);
  }

  const body = content.replace(/\r\n/g, "\n").split("\n---\n").slice(1).join("\n---\n").trim();
  if (!body) {
    failures.push(`${skillName}/SKILL.md 正文为空`);
  }

  const openaiYaml = read(`.agents/skills/${skillName}/agents/openai.yaml`);
  for (const key of ["display_name", "short_description", "default_prompt"]) {
    if (!new RegExp(`\\b${key}:\\s*`).test(openaiYaml)) {
      failures.push(`${skillName}/agents/openai.yaml 缺少 ${key}`);
    }
  }
}

if (!existsSync(skillsRoot)) {
  failures.push("缺少 .agents/skills 目录");
} else {
  const skillDirs = readdirSync(skillsRoot)
    .map((entry) => join(skillsRoot, entry))
    .filter((entry) => statSync(entry).isDirectory())
    .sort();

  if (skillDirs.length === 0) {
    failures.push(".agents/skills 目录下没有任何 skill");
  }

  for (const skillDir of skillDirs) {
    checkSkill(skillDir);
  }
}

if (failures.length > 0) {
  console.error("skills 快检失败：");
  for (const failure of failures) {
    console.error(`- ${failure}`);
  }
  process.exit(1);
}

console.log("skills 快检通过。");
