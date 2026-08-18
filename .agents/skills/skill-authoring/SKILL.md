---
name: skill-authoring
description: 当需要创建、更新、拆分或审查项目级 .agents/skills 下的 skill，补齐 SKILL.md、agents/openai.yaml、能力池路由、验证矩阵和进度记录，或判断重复流程是否应升级为 skill 时使用。
---

# Skill Authoring

## 1. 目标

把重复出现、需要动态判断、不能只靠静态文档或脚本承接的高频工作流，沉淀为项目级 skill。

## 2. 先判断

1. 如果只是一次性事实，写入 `docs/progress.md` 或阶段方案，不创建 skill。
2. 如果只是固定机械检查，优先创建 script、hook 或测试，不创建 skill。
3. 如果流程需要 AI 根据上下文选择步骤、读取不同文档或做边界判断，才创建 skill。
4. 如果能力适合所有新项目默认具备，先用 `template-feedback` 评估是否回流本地模板源。

## 3. 必读

1. `AGENTS.md`
2. `docs/architecture/Codex规则能力池.md`
3. `docs/architecture/rules/README.md`
4. 现有 `.agents/skills/*/SKILL.md`
5. 本轮任务直接相关的项目文档或阶段方案

## 4. 创建步骤

1. 选择 skill 名称：只用小写字母、数字和连字符，目录名必须等于 frontmatter `name`。
2. 创建 `.agents/skills/<skill-name>/SKILL.md`。
3. 创建 `.agents/skills/<skill-name>/agents/openai.yaml`。
4. `SKILL.md` frontmatter 只保留 `name` 和 `description`。
5. `description` 必须写清能力内容和触发场景，因为这是自动触发的主要依据。
6. 正文只写执行该工作流必须知道的步骤、边界、验证和交付要求。
7. 不创建 README、安装说明、变更日志或无关资源。

## 5. openai.yaml 要求

1. 至少包含：
   - `interface.display_name`
   - `interface.short_description`
   - `interface.default_prompt`
2. `default_prompt` 必须显式包含 `$<skill-name>`。
3. 不写密钥、私有 token、机器专有路径或业务机密。

## 6. 同步点

1. 在 `docs/architecture/Codex规则能力池.md` 增加需求信号、能力、类型、入口和反触发边界。
2. 如影响会话入口或高频规则，同步 `AGENTS.md` 或 `docs/README.md`。
3. 如新增验证口径，同步 `docs/testing/验证矩阵.md`。
4. 如属于稳定规则，同步 `docs/architecture/rules/ai-collaboration.md` 或对应分类。
5. 更新 `docs/progress.md`，只记录事实、验证结果和下一步。

## 7. 验证

1. 必须执行 `node scripts/check-skills.mjs`。
2. 必须执行 `node scripts/check-project-ready.mjs`。
3. 若当前目录是 Git 仓库，执行本轮相关文件 `git diff --check`。
4. 若当前目录不是 Git 仓库，执行文本尾随空白检查，并在交付中说明 `git diff --check` 未执行原因。

## 8. 交付

1. 说明新增或更新了哪个 skill。
2. 说明为什么它不是一次性规则。
3. 说明同步了哪些路由和验证文档。
4. 说明执行了哪些检查以及结果。
