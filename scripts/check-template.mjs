#!/usr/bin/env node

import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(fileURLToPath(new URL(".", import.meta.url)), "..");

const requiredFiles = [
  "README.md",
  ".gitignore",
  "AGENTS.md",
  ".new-project-template/state.json",
  "docs/README.md",
  "docs/architecture/项目长期总纲.md",
  "docs/architecture/多语言与文案主链.md",
  "docs/architecture/新项目VibeCoding长期总纲.md",
  "docs/architecture/通用能力索引.md",
  "docs/architecture/Codex规则能力池.md",
  "docs/architecture/Codex使用流程与AgenticEngineering差距分析.md",
  "docs/architecture/multi-agent-workflow.md",
  "docs/architecture/rules/README.md",
  "docs/architecture/rules/code.md",
  "docs/architecture/rules/design.md",
  "docs/architecture/rules/product.md",
  "docs/architecture/rules/testing.md",
  "docs/architecture/rules/security.md",
  "docs/architecture/rules/operations.md",
  "docs/architecture/rules/ai-collaboration.md",
  "docs/plans/codex-task-intake-template.md",
  "docs/testing/验证矩阵.md",
  "docs/testing/codex-workflow-scorecard.md",
  "docs/testing/codex-workflow-eval-cases.md",
  "docs/security/安全与密钥边界.md",
  "docs/product/产品需求基线.md",
  "docs/decisions/.gitkeep",
  "docs/operations/.gitkeep",
  "docs/blueprints/README.md",
  "docs/blueprints/frontend-app.md",
  "docs/blueprints/backend-api.md",
  "docs/blueprints/cli-tool.md",
  "docs/blueprints/desktop-client.md",
  "docs/blueprints/website.md",
  "docs/blueprints/library-package.md",
  "docs/blueprints/generic.md",
  "docs/progress.md",
  ".agents/skills/project-bootstrap/SKILL.md",
  ".agents/skills/project-bootstrap/agents/openai.yaml",
  ".agents/skills/rule-discovery/SKILL.md",
  ".agents/skills/rule-discovery/agents/openai.yaml",
  ".agents/skills/skill-authoring/SKILL.md",
  ".agents/skills/skill-authoring/agents/openai.yaml",
  ".agents/skills/task-closeout/SKILL.md",
  ".agents/skills/task-closeout/agents/openai.yaml",
  ".agents/skills/template-feedback/SKILL.md",
  ".agents/skills/template-feedback/agents/openai.yaml",
  "scripts/init-project.mjs",
  "scripts/check-localization.mjs",
  "scripts/check-project-ready.mjs",
  "scripts/check-skills.mjs",
  ".codex/README.md",
  ".codex/config.toml",
];

const contentFiles = requiredFiles.filter((file) => !file.startsWith("scripts/"));

const requiredText = [
  ["AGENTS.md", "INIT_REQUIRED"],
  ["AGENTS.md", "不是具体项目"],
  ["docs/architecture/项目长期总纲.md", "干净通用基座"],
  ["docs/architecture/多语言与文案主链.md", "JSON locale"],
  ["docs/architecture/新项目VibeCoding长期总纲.md", "成长性"],
  ["docs/architecture/新项目VibeCoding长期总纲.md", "回流到本地模板源"],
  ["docs/architecture/通用能力索引.md", "能力分层"],
  ["docs/architecture/Codex规则能力池.md", "能力索引"],
  ["docs/architecture/Codex规则能力池.md", "模板能力回流"],
  ["docs/architecture/Codex使用流程与AgenticEngineering差距分析.md", "Agent = Model + Harness"],
  ["docs/architecture/multi-agent-workflow.md", "禁止场景"],
  ["docs/architecture/rules/README.md", "代码规则"],
  ["docs/architecture/rules/ai-collaboration.md", "待回流本地模板源"],
  ["docs/testing/codex-workflow-scorecard.md", "人工纠偏次数"],
  ["docs/testing/codex-workflow-eval-cases.md", "high-risk-boundary"],
  ["docs/testing/codex-workflow-eval-cases.md", "template-feedback-loop"],
  ["docs/blueprints/README.md", "不是项目事实"],
  [".agents/skills/project-bootstrap/SKILL.md", "初始化前不得开始业务施工"],
  [".agents/skills/rule-discovery/SKILL.md", "代码、设计、产品、验证、安全、运维、AI 协作"],
  [".agents/skills/rule-discovery/SKILL.md", "回流到本地模板源"],
  [".agents/skills/skill-authoring/SKILL.md", "创建、更新、拆分或审查项目级"],
  [".agents/skills/task-closeout/SKILL.md", "不默认部署测试环境"],
  [".agents/skills/template-feedback/SKILL.md", "/Users/q/Documents/Projects/NewProject"],
];

const forbiddenPatterns = [
  [/9\.9Studio/i, "模板不得包含 9.9Studio 专有名称"],
  [/Cloudflare|R2|D1|GitHub Releases/i, "模板不得预设专有云或发布平台"],
  [/SticAI|website-next|account-system|release-operations/i, "模板不得复制其它项目专项 skill"],
];

const failures = [];

function runCheck(label, script) {
  const scriptPath = join(root, script);
  if (!existsSync(scriptPath)) return;

  const result = spawnSync(process.execPath, [scriptPath], {
    cwd: root,
    encoding: "utf8",
  });
  if (result.status !== 0) {
    const output = `${result.stdout || ""}${result.stderr || ""}`.trim();
    failures.push(`${label}失败：${output || "无输出"}`);
  }
}

for (const file of requiredFiles) {
  const path = join(root, file);
  if (!existsSync(path)) {
    failures.push(`缺少必备文件：${file}`);
  }
}

const statePath = join(root, ".new-project-template/state.json");
if (existsSync(statePath)) {
  try {
    const state = JSON.parse(readFileSync(statePath, "utf8"));
    if (state.state !== "init-required") {
      failures.push("模板源必须保持 init-required 状态，不得提交已初始化项目态");
    }
    if (state.projectName !== null || state.projectType !== null) {
      failures.push("模板源不得写入具体 projectName 或 projectType");
    }
  } catch {
    failures.push(".new-project-template/state.json 不是合法 JSON");
  }
}

for (const [file, text] of requiredText) {
  const path = join(root, file);
  if (existsSync(path)) {
    const content = readFileSync(path, "utf8");
    if (!content.includes(text)) {
      failures.push(`文件缺少关键文本：${file} -> ${text}`);
    }
  }
}

for (const file of contentFiles) {
  const path = join(root, file);
  if (!existsSync(path)) continue;
  const content = readFileSync(path, "utf8");
  for (const [pattern, message] of forbiddenPatterns) {
    if (pattern.test(content)) {
      failures.push(`${message}：${file}`);
    }
  }
}

runCheck("skills 快检", "scripts/check-skills.mjs");

if (failures.length > 0) {
  console.error("模板快检失败：");
  for (const failure of failures) {
    console.error(`- ${failure}`);
  }
  process.exit(1);
}

console.log("模板快检通过。");
