# 文档索引

## 1. 定位

1. 本目录是新项目的长期上下文包，负责让 AI 和人都能快速判断“该读什么、该改哪里、该验证什么、什么不能碰”。
2. 文档只记录稳定事实、长期规则、阶段方案和可追溯结果；一次性聊天判断不直接写成长期规则。
3. 具体产品事实随项目落地逐步补齐，模板中的占位原则不得冒充已完成事实。

## 2. 文档路由

| 任务信号 | 必读文档 |
|---|---|
| 复制模板后的第一轮初始化 | `.agents/skills/project-bootstrap/SKILL.md` |
| 项目定位、系统边界、主链、不变量 | `docs/architecture/项目长期总纲.md` |
| 界面语言、locale、fallback 与文案资源 | `docs/architecture/多语言与文案主链.md` |
| 新项目工作法、Vibe Coding、工程化升级 | `docs/architecture/新项目VibeCoding长期总纲.md` |
| 通用能力、能力成熟度、owner 边界 | `docs/architecture/通用能力索引.md` |
| Codex 工作流、任务分级、Agentic Engineering | `docs/architecture/Codex使用流程与AgenticEngineering差距分析.md` |
| 文档 / skill / script / guard / eval 路由 | `docs/architecture/Codex规则能力池.md` |
| 创建、更新、拆分或审查项目级 skill | `.agents/skills/skill-authoring/SKILL.md` |
| 通用能力去专有化并回流本地新项目模板 | `.agents/skills/template-feedback/SKILL.md` |
| 多 Agent 协作、并行边界、合并顺序 | `docs/architecture/multi-agent-workflow.md` |
| 需求、用户路径、商业边界、验收口径 | `docs/product/产品需求基线.md` |
| 任务入口、风险分级、完成标准 | `docs/plans/codex-task-intake-template.md` |
| 阶段施工方案、迁移方案、复杂改造 | `docs/plans/` |
| AirPlay / DLNA 协议路线与许可证决策 | `docs/decisions/0001-AirPlay与DLNA协议主链.md` |
| 验证命令、smoke、剩余手测 | `docs/testing/验证矩阵.md` |
| Codex 工作流复盘 | `docs/testing/codex-workflow-scorecard.md` |
| Codex 行为 eval | `docs/testing/codex-workflow-eval-cases.md` |
| 密钥、PII、日志、权限、生产数据 | `docs/security/安全与密钥边界.md` |
| 部署、环境、回滚、运维事实 | `docs/operations/` |
| 决策理由、路线取舍 | `docs/decisions/` |
| 当前事实、收尾记录、未完成事项 | `docs/progress.md` |
| 可复用规则、重复经验、设计和代码规范 | `docs/architecture/rules/README.md` |
| 项目类型选择、初始化能力包 | `docs/blueprints/README.md` |

## 3. 写作口径

1. 总纲写边界、主链、不变量和验收标准。
2. 计划写未来怎么做、物理锚点和验证闭环。
3. 进度写已经做了什么、验证了什么、还剩什么。
4. 决策写为什么选这条路线，以及拒绝哪些路线。
5. 规则文档按分类沉淀稳定经验，不把项目专属临时选择写成通用原则。
6. 能力池只写路由，不复制大型方案。
7. Eval 只评估 Agent 行为，不替代业务测试或用户手测；交互 smoke 不属于 AI 默认门禁，只有明确授权或故障诊断时才执行。

## 4. 更新规则

1. 每次发现新项目应具备的通用规则、能力、skill、检查或文档槽位，先使用 `rule-discovery` skill 判断分类。
2. 如果内容适合所有新项目默认具备，必须去专有化后回流到本地模板源 `/Users/q/Documents/Projects/NewProject`；复制后的项目只记录候选，不把自己当作模板源。
3. 如果规则只对某个项目成立，写入该项目自己的文档，不反向污染模板。
4. 如果规则已经可以机械检查，优先补脚本、hook、测试或 checklist，不只停留在自然语言。
5. 初始化前只能修改项目身份和项目事实，不得直接开始业务施工。
