---
name: template-feedback
description: 当项目发现适合所有新项目默认具备的通用能力、skill、规则、检查、脚本、文档槽位或工作流，需要去除项目专有信息并回流到本地新项目模板仓库 /Users/q/Documents/Projects/NewProject 时使用。
---

# Template Feedback

## 1. 目标

把具体项目中证明有复用价值的能力去项目专有化，并回流到本地新项目模板源 `/Users/q/Documents/Projects/NewProject`，让后续新项目默认继承。

## 2. 触发信号

1. 某个 skill、脚本、检查、规则或文档槽位预计会在多个新项目复用。
2. 用户明确要求“回流模板”“放进新项目模板”“以后每个新项目都有”。
3. 当前项目里出现第二次相同工作流判断，且不依赖项目专有事实。
4. 发现模板源缺少基础护栏，例如 skill 创建、模板回流、验证入口或文档槽位。

## 3. 必读

1. 当前项目 `AGENTS.md`
2. 当前项目 `docs/architecture/Codex规则能力池.md`
3. 当前项目 `docs/architecture/rules/README.md`
4. 本地模板源 `/Users/q/Documents/Projects/NewProject/AGENTS.md`
5. 本地模板源对应的目标文件

## 4. 判定

1. 项目专有事实留在当前项目：产品名、业务能力、框架选择、路径、部署入口、历史兼容和具体用户承诺。
2. 模板通用能力可回流：工作流骨架、skill 结构、检查脚本、文档槽位、验证矩阵规则、AI 协作规则。
3. 如果无法确认跨项目通用，只在当前项目记录“候选通用能力”，不直接改模板源。
4. 如果模板源不可访问或不是干净状态，停止回流，在交付说明中写清待回流内容和建议落点。

## 5. 回流步骤

1. 检查模板源状态：
   - `git -C /Users/q/Documents/Projects/NewProject status --short --branch`
2. 读取模板源目标文件，不凭当前项目文件直接覆盖。
3. 去除项目专有词、路径、业务能力、部署入口和临时兼容。
4. 用最小 diff 修改模板源。
5. 在当前项目记录候选来源、回流动作和验证结果。
6. 不自动提交模板源，除非用户明确要求。

## 6. 验证

1. 在模板源执行：
   - `node scripts/check-template.mjs`
   - `node scripts/check-skills.mjs`
   - `git diff --check`
2. 在当前项目执行：
   - `node scripts/check-project-ready.mjs`
   - `node scripts/check-skills.mjs`
3. 如果任一命令因目录不是 Git 仓库或模板源不可访问而无法执行，说明客观原因和剩余最小人工确认。

## 7. 交付

1. 说明回流到模板源的文件。
2. 说明去除了哪些项目专有信息。
3. 说明当前项目同步了哪些记录。
4. 说明模板源和当前项目分别执行了哪些验证。
5. 说明是否提交；未提交时说明原因。
