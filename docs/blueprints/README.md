# 项目类型 Blueprints

## 1. 定位

1. Blueprints 是初始化时的项目类型参考，不是项目事实。
2. 只有 `project-bootstrap` 选择并写入项目文档后，某个 blueprint 的内容才成为当前项目的事实。
3. 未选择的 blueprint 不得作为默认能力、默认目录或默认验证入口。

## 2. 类型

| 类型 | 文件 | 适用场景 |
|---|---|---|
| `frontend-app` | `frontend-app.md` | 浏览器前端、Web App、管理台、可视化工具 |
| `backend-api` | `backend-api.md` | API 服务、Worker、Server、数据服务 |
| `cli-tool` | `cli-tool.md` | 命令行工具、脚本工具、自动化工具 |
| `desktop-client` | `desktop-client.md` | 桌面客户端、本地 GUI、本地工具 |
| `website` | `website.md` | 官网、文档站、营销页、内容站 |
| `library-package` | `library-package.md` | SDK、组件库、协议包、可复用包 |
| `generic` | `generic.md` | 尚未明确技术形态的项目 |

## 3. 使用规则

1. 初始化时必须选择一个主类型。
2. 可以记录未来可能接入的次类型，但不得默认施工。
3. 类型切换必须更新 `AGENTS.md`、项目长期总纲、验证矩阵和进度记录。
4. 类型选择不决定业务需求，只决定初始目录、验证和风险提示。
