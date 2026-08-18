---
name: project-bootstrap
description: 当新项目从模板复制后需要初始化为具体项目实例、补齐项目名称 / 类型 / 一句话定位 / 源码目录 / 验证入口 / V1 不做范围，或发现仓库仍处于 INIT_REQUIRED 模板态时使用；初始化前不得开始业务施工。
---

# Project Bootstrap

## 1. 目标

把复制出来的模板态仓库转换为具体项目实例，避免新项目把自己误认为模板、blueprint 或通用能力对应项目。

## 2. 必读

1. 根 `AGENTS.md`。
2. `docs/README.md`。
3. `docs/architecture/新项目VibeCoding长期总纲.md`。
4. `docs/blueprints/README.md`。
5. 命中的项目类型 blueprint。

## 3. 必须先确认

初始化前必须拿到或合理推断：

1. 项目名称。
2. 一句话定位。
3. 项目类型：`frontend-app`、`backend-api`、`cli-tool`、`desktop-client`、`website`、`library-package` 或 `generic`。
4. 目标用户和非目标范围。
5. V1 成功标准。
6. V1 明确不做范围。
7. 源码目录。
8. 最小验证命令。

缺少项目名称、项目类型或一句话定位时，必须先追问，不得初始化。

## 4. 执行步骤

1. 检查模板状态：
   - `node scripts/check-template.mjs`
2. 执行初始化：
   - `node scripts/init-project.mjs --name "<项目名>" --type <项目类型> --description "<一句话定位>"`
3. 按项目事实补充：
   - `docs/product/产品需求基线.md`
   - `docs/architecture/项目长期总纲.md`
   - `docs/testing/验证矩阵.md`
   - `docs/progress.md`
4. 执行项目就绪检查：
   - `node scripts/check-project-ready.mjs`
5. 若需要新增专项 skill，先确认同类流程预计会重复出现。

## 5. 禁止

1. 初始化前不得开始业务功能施工。
2. 不得把 `docs/blueprints/` 当作项目事实。
3. 不得把 `docs/architecture/通用能力索引.md` 中的能力候选当作已实现能力。
4. 不得复制其它项目专有路径、部署入口、业务协议或历史兼容。
5. 不得为了快速启动而删除验证入口、安全边界或人工门禁。

## 6. 交付要求

1. 说明项目名称、项目类型和一句话定位。
2. 说明已写入哪些项目事实。
3. 说明选择了哪个 blueprint，以及哪些能力只是候选。
4. 说明 `check-template` 和 `check-project-ready` 的结果。
5. 明确项目仍未接入的能力和剩余最小人工确认。
