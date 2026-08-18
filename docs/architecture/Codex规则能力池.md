# Codex 规则能力池

## 1. 定位

1. 本文回答“遇到什么需求应加载什么能力”。
2. 能力池只写路由和边界，不承载业务方案细节。
3. 具体能力由文档、skill、脚本、guard、smoke 或 eval 承接。
4. 能力池不是默认执行池；只有任务信号命中且当前范围需要时才调用。

## 2. 能力类型

| 类型 | 作用 | 适合承载 |
|---|---|---|
| 文档 | 领域知识、架构边界、验证口径 | 总纲、阶段方案、验证矩阵、运维说明 |
| Skill | 高频流程、动态上下文、需要判断的施工步骤 | 规则发现、任务收尾、专项施工 |
| Script | 机械动作、固定参数、可复跑检查 | 快检、启动、迁移、格式检查 |
| Guard / Hook | 不应依赖模型记忆的阻断规则 | 密钥、临时产物、部署边界、工作树卫生 |
| Eval | Agent 行为是否选对流程 | 文档路由、验证等级、人工门禁、无关改动 |

## 3. 使用流程

1. 先按 `docs/plans/codex-task-intake-template.md` 判断任务级别和风险。
2. 再按本文的需求信号查找能力，不自行发明新流程。
3. 命中专项施工能力时，先加载专项 skill 或专项文档。
4. 专项能力判断需要运行级验证、收尾、部署 plan 等下游动作时，再调用对应下游能力。
5. 下游能力只执行自己的闭环，不反向扩大业务范围。
6. 若没有命中能力池，按仓库现有文档、代码和最低成本验证继续。

## 4. 二次重复判断识别

AI 不能依赖“我记得之前遇到过”来判断重复。进入 L4 能力沉淀前必须有可检索证据：

1. 先用关键词检索：
   - `rg -n "<关键词>" docs .agents scripts`
2. 如果能力池已有条目，直接使用现有能力。
3. 如果没有条目，但在进度、评分表、规则文档、skill 或验证矩阵中找到同类记录，视为“已出现过一次”。
4. 本轮又再次出现同类临场判断、用户纠偏或重复手写流程，视为“二次重复判断”。
5. 二次重复判断的处理顺序：
   - 路由知识写入本文或专项文档。
   - 高频流程升级为 skill。
   - 机械动作升级为 script。
   - 必须阻断的错误升级为 guard / hook。
   - Agent 是否选对流程升级为 eval 用例。

## 5. 能力索引

| 需求信号 | 能力 | 类型 | 入口 | 反触发边界 |
|---|---|---|---|---|
| 复制模板、INIT_REQUIRED、项目初始化、选择项目类型 | 项目初始化 | Skill + Script | `.agents/skills/project-bootstrap/SKILL.md`、`node scripts/init-project.mjs` | 初始化前不得开始业务施工 |
| 新项目、项目底座、Vibe Coding | 新项目总纲 | 文档 | `docs/architecture/新项目VibeCoding长期总纲.md` | 单点 bug 不默认加载全文 |
| 能力归属、成熟度、owner | 通用能力索引 | 文档 | `docs/architecture/通用能力索引.md` | 不当作默认施工范围 |
| 工作流优化、任务分级、Agentic Engineering | Codex 工作法 | 文档 | `docs/architecture/Codex使用流程与AgenticEngineering差距分析.md` | 普通业务施工不必加载全文 |
| 规则发现、重复失败、经验沉淀 | 规则发现 | Skill | `.agents/skills/rule-discovery/SKILL.md` | 一次性事实不写入通用规则 |
| 创建、更新、拆分或审查项目级 skill | Skill 创建 | Skill | `.agents/skills/skill-authoring/SKILL.md` | 一次性事实或纯机械检查不创建 skill |
| 适合所有新项目的能力、skill、规则、检查或文档槽位 | 模板能力发现 | Skill + 文档 | `.agents/skills/rule-discovery/SKILL.md`、`docs/architecture/rules/README.md` | 项目专有事实不回流模板 |
| 通用能力已确认需要回流本地新项目模板 | 模板能力回流 | Skill | `.agents/skills/template-feedback/SKILL.md` | 未去专有化、模板源不可访问或模板源状态不明时不直接改模板 |
| 收尾、提交、handoff、交付说明 | 任务收尾 | Skill | `.agents/skills/task-closeout/SKILL.md` | 不默认部署或上线 |
| 文档、模板、目录底座检查 | 模板快检 | Script | `node scripts/check-template.mjs` | 只读查询不默认执行 |
| 项目初始化完成度 | 项目就绪检查 | Script | `node scripts/check-project-ready.mjs` | 模板源开发时不要求通过 |
| skill 结构、frontmatter、openai.yaml 元数据 | Skill 基础快检 | Script | `node scripts/check-skills.mjs` | 不替代人工审查 skill 内容质量 |
| 多 Agent 协作 | 多 Agent 协议 | 文档 | `docs/architecture/multi-agent-workflow.md` | 生产、密钥、数据、收尾提交不并行 |
| 工作流复盘 | 工作流评分表 | 文档 | `docs/testing/codex-workflow-scorecard.md` | 不记录每个极小任务 |
| Agent 行为评估 | 工作流 eval | 文档 | `docs/testing/codex-workflow-eval-cases.md` | 不替代真实测试 |

## 6. 维护规则

1. 新增能力前先检查是否已有同类能力，避免第二套真值。
2. 每个 skill 只负责一个工作流。
3. 每个 script 只负责可复跑的机械动作。
4. 能力池变更后执行模板快检和本轮相关文件 `git diff --check`。
5. 若能力池条目改变任务路由，同步更新 `AGENTS.md` 或 `docs/README.md`。
6. 能力索引和 blueprints 只提供候选能力；未经过 `project-bootstrap` 写入项目事实前，不得视为已实现能力。
7. 发现模板级能力候选时，先在当前项目记录候选和证据；确认跨项目通用后，通过 `template-feedback` 回流到本地模板源 `/Users/q/Documents/Projects/NewProject`，并执行模板快检。
