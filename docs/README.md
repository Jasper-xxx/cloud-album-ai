# Cloud-Album 文档索引

更新日期：2026-10-04。当前范围为个人版。仓库只分发使用说明、OpenAPI 和当前可导入 DSL；实施方案、交接、逐项验收记录、面试材料与发布快照保留本地，不作为使用依赖。

本文同步既有实现和验收摘要。本次整理没有启动服务、运行测试、调用模型或操作 Dify。历史数字不是新环境自动通过的证明。

## 当前使用说明

第一期受控查询循环已按用户反馈收尾，第二期查询后写预览已完成重点在线验证、修复和本地 Dify 发布。两项开关均已开启；相关 Python 62 项、Java 16 项通过，测试数据已恢复。导入、有限语法和回退见[工作流指南](dify-agent-write-workflow-guide.md)，已测与未测范围见[功能说明](云忆助手功能.md)。这些结论不等于完整浏览器或全部业务验收。

| 文档 | 用途 |
| --- | --- |
| [项目 README](../README.md) | 架构、环境配置、启动、数据库版本与常见故障 |
| [前端 README](../frontend/README.md) | Vite 环境文件、代理、助手入口和构建命令 |
| [Dify 工作流指南](dify-agent-write-workflow-guide.md) | 导入发布、查询循环、有限范围语法、预览确认、附件及回退 |
| [Dify 系统提示词](dify-agent-system-prompt.md) | 供配置参考的行为约束，不能替代 DSL 节点和后端校验 |
| [云忆助手功能](云忆助手功能.md) | 已有能力、接口和使用边界 |
| [配额校准与上传配置](../backend/docs/quota-migration.md) | 逻辑容量、Redis 预留、既有数据校准和分片上传；V0～V12 概览见项目 README |
| [评测工具 README](../evaluation/README.md) | 样例与真实数据采集、业务证据、指标口径及现有限制 |
| [可观测性 README](../ops/observability/README.md) | 监控编排、指标、告警与网络配置 |

导入文件：[OpenAPI 工具定义](dify-agent-openapi.yaml)、[工作流 DSL](云忆相册助手-write.yml)。查询循环及写预览桥接默认开启；仓库修改不会自动同步、导入或发布到 Dify 实例。

## 当前实现的关键边界

- **访问身份**：`/agent/capabilities` 公开；其它智能体接口使用登录身份，或 `X-Agent-Service-Key` 绑定 `AGENT_OWNER_USER_ID`。个人 Dify 应用不提供跨访问者的图库身份切换。旧 `AGENT_AUTH_ENABLED`、`AGENT_DEV_USER_ID` 已不再生效。
- **AI 调用**：后端使用 `X-AI-Service-Key` 访问 AI；AI 除 `/health` 外校验该密钥。AI 缺少有效密钥会启动失败，健康检查不能证明模型调用已成功。
- **配置来源**：后端与 AI 共用根目录 `.env`；AI 还读取可选的 `ai-service/.env`，环境变量可覆盖文件。前端 `envDir` 也指向根目录，只暴露 `VITE_` 配置。临时启动 JSON 不再是服务凭据来源。
- **前端网络**：开发服务器固定 `127.0.0.1:8080`，只代理 `/devApi` 到后端。端口占用时不会自动改用 8081；没有到 AI 的 `/mockApi` 代理。
- **会话与写操作**：预览凭证绑定用户、会话和冻结参数。工具传输 `sys.conversation_id`，确认、取消和状态查询不能靠模型自由生成凭证。
- **附件与会话界面**：WebApp `/chat/` 提供历史会话；附件功能需 Dify 发布配置开启。单张附件搜图不自动保存，明确保存才写入图库；自然语言识别和结果处理在工作流节点中完成。
- **回收站与资源链接**：代码已有回收站恢复、软删除、持久化资源授权和对象回收队列；存在实现不等于永久删除等延期场景已经完成真实验收。
- **相似发现**：接口受理后台任务，前端活动面板可查询/取消。相似结果为候选，不自动删图；进一步优化与完整性能验收仍不属于本次文档工作。
- **数据库**：仓库包含 V0～V12；V9～V12 分别涉及会话绑定、资源授权、对象回收队列、相似发现任务。文档更新没有执行数据库迁移。
- **评测**：旧报告的完成率不能直接作为采用独立业务状态与回复断言的新采集器的成绩。样例报告、历史报告和当前在线验收需分别解读。

## 验证与历史资料

内部历史报告、逐项轨迹和发布快照不随当前目录分发。公开验证摘要见[功能说明](云忆助手功能.md#六验证记录与延期)，重新评测方法见[评测工具 README](../evaluation/README.md)。使用记录中的 PID、版本、测试数量只能说明当时环境，不能推断当前实例运行状态。

仓库保留的[通用样例报告](../evaluation/reports/example/report.md)和[P0 样例报告](../evaluation/reports/p0-example/report.md)是示例或固定夹具结果，不是完整在线成绩。

`evaluation/reports/agent-real-baseline-20260805/`、`evaluation/reports/resume-real-20260805/` 中可能有本地留存报告；它们属于历史产物，不保证随仓库分发，也不应作为启动依赖。原始 JSON/JSONL 观察、样例和报告数字不因本次文档更新而重写。

真实 Dify WebApp 完整在线验收、完整 92 条评测、批量真实模型调用、进一步相似发现优化、全部 P3/P4 真实操作、永久删除和清空回收站、监控扩展、企业与多人能力仍不能据这些历史文件宣称完成。

## 维护依据

| 主题 | 代码或配置来源 |
| --- | --- |
| 服务配置与前端代理 | [application.yml](../backend/src/main/resources/application.yml)、[AI Settings](../ai-service/app/core/config.py)、[vite.config.ts](../frontend/vite.config.ts) |
| 智能体身份与会话 | [AgentAccessGuard](../backend/src/main/java/com/memory/xzp/config/AgentAccessGuard.java)、[AgentConversation](../backend/src/main/java/com/memory/xzp/config/AgentConversation.java)、[AgentPendingActionService](../backend/src/main/java/com/memory/xzp/service/AgentPendingActionService.java) |
| AI 鉴权与启动检查 | [access.py](../ai-service/app/core/access.py)、[main.py](../ai-service/app/main.py) |
| 附件分支与结果 | [attachment_intent.py](../scripts/dify/attachment_intent.py)、[attachment_result.py](../scripts/dify/attachment_result.py) |
| 助手界面 | [AgentAssistant.vue](../frontend/src/components/agent/AgentAssistant.vue)、[AgentActivityPanel.vue](../frontend/src/components/agent/AgentActivityPanel.vue) |
| 后台相似任务 | [AgentDiscoveryJobService](../backend/src/main/java/com/memory/xzp/service/AgentDiscoveryJobService.java) |
| 迁移 | [Flyway 迁移目录](../backend/src/main/resources/db/migration/) |
| 评测 | [collectors.py](../evaluation/cloud_album_eval/collectors.py)、[business.py](../evaluation/cloud_album_eval/business.py)、[metrics.py](../evaluation/cloud_album_eval/metrics.py) |

后续修改配置、接口或工作流时同步更新对应使用说明；历史报告只补状态说明，不把旧测试结果改写为新版本成绩。新增在线验证应记录实际日期、版本、数据范围和独立业务结果。
