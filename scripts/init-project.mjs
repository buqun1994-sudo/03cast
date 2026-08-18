#!/usr/bin/env node

import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const root = resolve(scriptDir, "..");
const statePath = join(root, ".new-project-template", "state.json");

const allowedTypes = new Set([
  "frontend-app",
  "backend-api",
  "cli-tool",
  "desktop-client",
  "website",
  "library-package",
  "generic",
]);

function parseArgs(argv) {
  const args = {};
  for (let index = 0; index < argv.length; index += 1) {
    const token = argv[index];
    if (!token.startsWith("--")) {
      throw new Error(`无法识别参数：${token}`);
    }
    const key = token.slice(2);
    const next = argv[index + 1];
    if (!next || next.startsWith("--")) {
      throw new Error(`参数缺少取值：--${key}`);
    }
    args[key] = next;
    index += 1;
  }
  return args;
}

function requireValue(args, key) {
  const value = args[key]?.trim();
  if (!value) {
    throw new Error(`缺少必填参数：--${key}`);
  }
  return value;
}

function writeUtf8(relativePath, content) {
  const target = join(root, relativePath);
  mkdirSync(dirname(target), { recursive: true });
  writeFileSync(target, `${content.trimEnd()}\n`, "utf8");
}

function readState() {
  if (!existsSync(statePath)) {
    return { state: "missing" };
  }
  return JSON.parse(readFileSync(statePath, "utf8"));
}

function createAgents(projectName, projectType, description, sourceDir, checkCommand) {
  return `# AGENTS.md

核心：用中文。

## 1. 项目定位

1. 本仓库是 ${projectName} 的项目仓库。
2. 一句话定位：${description}
3. 项目类型：${projectType}
4. 本仓库已经完成模板初始化，不再是通用模板本身。
5. 通用能力索引和 blueprints 只作为候选参考，未写入项目总纲和验证矩阵前不得视为已实现能力。

## 2. 默认目录边界

1. \`${sourceDir}/\`：项目源码主目录。
2. \`docs/\`：产品、架构、计划、安全、运维、测试、决策和进度文档。
3. \`.agents/skills/\`：高频 AI 工作流，按任务动态读取。
4. \`scripts/\`：仓库级快检、验证、生成、迁移和机械护栏。
5. \`.codex/\`：项目级 Codex 配置说明和可提交配置，不放密钥、个人 token 或机器私有路径。

## 3. 工作原则

1. 先定位真实目标，再决定改哪里；禁止只围绕表面报错堆分支。
2. 从底层架构和通用能力往上施工，先抽象稳定主链，再接具体调用点。
3. 不主动往降级、绕路或弱化验证的方向靠拢；用户提出的需求要尽可能完整完成。
4. 如果现有抽象不能表达需求，先升级抽象和契约，再扩展业务入口。
5. 每次发现可复用规则、重复失败或稳定施工经验，必须使用 \`rule-discovery\` skill 沉淀到正确分类文档。
6. 发现适合所有新项目默认具备的能力、skill、规则、检查或文档槽位时，先在当前项目记录为候选通用能力；去除项目专有事实后，回流到本地模板源 \`/Users/q/Documents/Projects/NewProject\`。本仓库是具体项目仓库，不能作为模板源使用。

## 4. 安全红线

1. 禁止提交真实密钥、token、私钥、生产配置、本地环境文件、数据库备份和部署日志。
2. 禁止把 \`.env\`、\`.env.*.local\`、\`*.local.*\`、本地缓存、构建产物、临时截图、录屏和调试日志作为普通改动提交。
3. 生产、支付、账号、权限、用户数据、数据库迁移、密钥变更和正式上线必须先取得用户明确确认。
4. 客户端可公开配置和服务端机密必须在 \`docs/security/安全与密钥边界.md\` 中分开记录。

## 5. 任务入口

1. 任务入口模板见 \`docs/plans/codex-task-intake-template.md\`。
2. 简单任务可以基于现有上下文直接施工；高风险任务必须先明确范围、完成标准、验证闭环和人工门禁。
3. 用户明确只要方案、分析、review 或状态检查时，不得擅自改文件。
4. 文档路由见 \`docs/README.md\`；规则分类见 \`docs/architecture/rules/README.md\`。

## 6. Skills 路由

1. 规则发现、经验沉淀、重复流程归纳或新规则分类时，使用 \`.agents/skills/rule-discovery/SKILL.md\`。
2. 收尾、更新进度、写摘要、准备提交、交接或 handoff 时，使用 \`.agents/skills/task-closeout/SKILL.md\`。
3. 创建、更新、拆分或审查项目级 skill 时，使用 \`.agents/skills/skill-authoring/SKILL.md\`。
4. 将适合所有新项目默认具备的能力去专有化并回流本地模板源时，使用 \`.agents/skills/template-feedback/SKILL.md\`。
5. 新增业务专项 skill 前，先确认它不是一次性规则，也不是已有文档、脚本或测试能承接的机械动作。
6. 参考其它项目 skill 时，只复制通用工作流骨架和可验证做法，必须去除其它项目专有约束、名称、路径、部署入口和业务能力。
7. 模板级候选能力必须通过 \`rule-discovery\` 判断，再通过 \`template-feedback\` 回流本地模板源 \`/Users/q/Documents/Projects/NewProject\`；当前项目只承载项目事实和候选记录。

## 7. 验证分级

1. 初始化完成度检查执行 \`node scripts/check-project-ready.mjs\`。
2. Skill 基础结构检查执行 \`node scripts/check-skills.mjs\`。
3. 当前最小验证命令：\`${checkCommand}\`。
4. 代码或工程行为变更后，必须按低成本闭环优先执行语法、类型、单测、集成测试或现有快检。
5. UI、启动、导航、输入、文件读写、登录、支付、部署等用户可见行为变化，需要运行级 smoke 或明确客观阻断。
6. 编译通过不等于交互可用；实现完成不等于验证完成。

## 8. Git 与收尾

1. 只暂存本轮任务直接相关文件，不回退用户未授权改动。
2. 收尾默认复用本轮已完成验证；若收尾阶段又改了代码或工程行为，补跑最低成本验证。
3. 交付必须说明改动摘要、验证命令与结果、Git 提交状态、本地服务状态、部署 / 上线状态、未执行验证原因和剩余最小手测。
4. 未经用户明确要求，不默认部署测试环境，不默认上线正式环境。`;
}

function createArchitecture(projectName, projectType, description, sourceDir, checkCommand) {
  return `# 项目长期总纲

## 1. 文档定位

1. 本文是 ${projectName} 的系统级真值。
2. 一句话定位：${description}
3. 项目类型：${projectType}
4. 本文不替代具体需求、设计稿、接口文档或测试文件；它只回答长期施工中不能反复猜的问题。

## 2. 当前主链

1. 源码主目录：\`${sourceDir}/\`。
2. 最小验证命令：\`${checkCommand}\`。
3. 通用能力索引和 blueprints 是候选参考，不代表本项目已经实现对应能力。
4. 新能力必须先写入本文或阶段方案，明确 owner、验证和不做范围，再进入施工。

## 3. 总原则

1. 成长性：项目必须从每次施工中沉淀规则。
2. 模板回流：适合所有新项目默认具备的能力、skill、规则、检查或文档槽位，必须先记录为候选通用能力，再去专有化回流到本地模板源 \`/Users/q/Documents/Projects/NewProject\`。
3. 基建能力：从领域模型、数据契约、模块边界、验证入口和运行护栏往上做。
4. 单一真值：需求、接口、数据、发布和验证必须各有 owner。
5. 防降级：不主动弱化需求、绕开主链或降低验证标准。

## 4. 待补项目事实

1. 目标用户。
2. 非目标用户。
3. 核心用户路径。
4. V1 成功标准。
5. V1 明确不做范围。
6. 领域词汇表和核心状态机。
7. 运行级 smoke 入口。

## 5. 验收标准

每个 L2 以上任务交付前至少能回答：

1. 目标是否已经从“改什么”上升到“达成什么结果”。
2. 改动是否落在正确层级和主链。
3. 是否避免第二套真值、重复状态机或重复验证口径。
4. 是否有自动验证、运行级 smoke 或清晰客观阻断。
5. 是否把新规则沉淀到文档、skill、脚本、测试或 checklist。
6. 是否把适合所有新项目的新增能力、skill、规则或检查回流到本地模板源，或明确记录为“待回流本地模板源”。`;
}

function createProduct(projectName, description) {
  return `# 产品需求基线

## 1. 产品定位

1. 产品名称：${projectName}
2. 一句话定位：${description}
3. 目标用户：待补齐。
4. 非目标用户：待补齐。
5. 核心问题：待补齐。

## 2. 核心路径

1. 用户第一条成功路径：待补齐。
2. V1 成功标准：待补齐。
3. 明确不做范围：待补齐。

## 3. 验收口径

1. 用户可观察到的完成现象：待补齐。
2. 必须自动验证的部分：待补齐。
3. 剩余最小人工验收：待补齐。`;
}

function createTesting(checkCommand) {
  return `# 验证矩阵

## 1. 当前最小入口

1. 项目初始化就绪：
   - \`node scripts/check-project-ready.mjs\`
2. Skill 基础结构：
   - \`node scripts/check-skills.mjs\`
3. 当前最小验证：
   - \`${checkCommand}\`

## 2. 分级

| 变更类型 | 必须验证 |
|---|---|
| 只读查询 | 不默认跑验证 |
| 文档、目录、模板底座 | \`node scripts/check-project-ready.mjs\`、\`node scripts/check-skills.mjs\`、\`git diff --check\` |
| Skill 新增或修改 | \`node scripts/check-skills.mjs\`、\`git diff --check\` |
| 代码或配置 | 语法 / 类型 / lint / 单测中最低成本可执行项 |
| 共享契约或跨模块逻辑 | 相关单测、集成测试、回归用例 |
| UI、启动、导航、输入、文件读写 | 运行级 smoke 或客观阻断说明 |
| 生产、数据、支付、账号、权限、密钥 | dry-run、相关测试、人工门禁、回滚说明 |

## 3. 待补

1. 技术栈快检命令。
2. 单元测试命令。
3. 集成测试命令。
4. 运行级 smoke 入口。`;
}

function createProgress(projectName, projectType, description) {
  const date = new Date().toISOString().slice(0, 10);
  return `# 项目进度

## ${date} 项目初始化

1. 目标：将新项目模板初始化为 ${projectName}。
2. 项目类型：${projectType}。
3. 一句话定位：${description}
4. 已完成：写入项目身份、项目长期总纲、产品需求基线和验证矩阵。
5. 验证：待执行 \`node scripts/check-project-ready.mjs\`。
6. 未完成：目标用户、核心路径、技术栈命令、运行级 smoke 和 V1 不做范围需继续补齐。

## 记录规则

1. 只记录事实、验证结果、未完成事项和下一步。
2. 不复制长日志，不把未来计划写成已完成事实。`;
}

function main() {
  try {
    const args = parseArgs(process.argv.slice(2));
    const projectName = requireValue(args, "name");
    const projectType = requireValue(args, "type");
    const description = requireValue(args, "description");
    const sourceDir = args["src"]?.trim() || "src";
    const checkCommand = args["check"]?.trim() || "node scripts/check-project-ready.mjs";

    if (!allowedTypes.has(projectType)) {
      throw new Error(`项目类型不受支持：${projectType}`);
    }

    const state = readState();
    if (state.state === "initialized") {
      throw new Error(`项目已经初始化：${state.projectName}`);
    }

    writeUtf8("AGENTS.md", createAgents(projectName, projectType, description, sourceDir, checkCommand));
    writeUtf8("docs/architecture/项目长期总纲.md", createArchitecture(projectName, projectType, description, sourceDir, checkCommand));
    writeUtf8("docs/product/产品需求基线.md", createProduct(projectName, description));
    writeUtf8("docs/testing/验证矩阵.md", createTesting(checkCommand));
    writeUtf8("docs/progress.md", createProgress(projectName, projectType, description));

    const nextState = {
      schemaVersion: 1,
      state: "initialized",
      templateName: "new-project-template",
      projectName,
      projectType,
      description,
      sourceDir,
      checkCommand,
      initializedAt: new Date().toISOString(),
    };
    writeUtf8(".new-project-template/state.json", JSON.stringify(nextState, null, 2));

    console.log(`项目初始化完成：${projectName}`);
    console.log(`项目类型：${projectType}`);
    console.log("下一步：node scripts/check-project-ready.mjs");
  } catch (error) {
    console.error(`项目初始化失败：${error.message}`);
    console.error("用法：node scripts/init-project.mjs --name <项目名> --type <类型> --description <一句话定位> [--src src] [--check <命令>]");
    process.exit(1);
  }
}

main();
