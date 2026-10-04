# Cloud-Album 文档索引

核对日期：2026-10-02。当前范围为个人版；本文及各使用说明依据本次工作区源码更新。没有启动服务、运行测试、调用模型、导入 Dify 或验证在线环境。

文档校正阶段盘点了工作区 24 份 Markdown 文档（含本索引及本地历史报告），同时校正环境示例、OpenAPI 和 Swagger 中的相似任务说明；之后另行新增了受控循环方案。静态检查中，本地文件链接、代码块配对、OpenAPI YAML 解析与 schema 引用、Git 差异空白检查均通过。部分历史文件被 Git 忽略，更新保留在本地工作区；提交当前变更不会自动包含这些文件。

## 当前使用说明

第一期受控查询循环已收尾，[最终 DSL 快照](releases/dify-read-loop-phase1-2026-10-04.yml)保留。第二期查询后写预览已完成重点在线验证、修复和本地 Dify 发布，独立开关已开启；Python 62 项、Java 16 项相关测试通过，测试数据已恢复。实际范围及限制见[第二期验收收尾](dify-read-write-phase2-acceptance-2026-10-04.md)，源码/有限语法/回退见[第二期交付](dify-read-write-phase2-delivery-2026-10-04.md)，[最终 DSL](releases/dify-read-write-phase2-2026-10-04-final.yml)与工作文件一致。第一期历史见[验收记录](dify-read-loop-acceptance-2026-10-03.md)。

| 文档 | 用途 |
| --- | --- |
| [项目 README](../README.md) | 架构、环境配置、启动、数据库版本与常见故障 |
| [前端 README](../frontend/README.md) | Vite 环境文件、代理、助手入口和构建命令 |
| [个人版 Dify 导入说明](dify-personal-import-2026-09-14.md) | 工具鉴权、会话参数、DSL 导入及人工验证；文件名保留原创建日期，正文已更新 |
| [Dify 写工作流指南](dify-agent-write-workflow-guide.md) | 预览、确认、取消、写操作与附件分支 |
| [Dify 系统提示词](dify-agent-system-prompt.md) | 供配置参考的行为约束，不能替代 DSL 节点和后端校验 |
| [云忆助手功能](云忆助手功能.md) | 已有能力、接口和使用边界 |
| [配额校准与上传配置](../backend/docs/quota-migration.md) | 逻辑容量、Redis 预留、既有数据校准和分片上传；V0～V12 概览见项目 README |
| [评测工具 README](../evaluation/README.md) | 样例与真实数据采集、业务证据、指标口径及现有限制 |
| [可观测性 README](../ops/observability/README.md) | 监控编排、指标、告警与网络配置 |
| [面试材料](../Cloud-Album面试.md) | 基于当前代码讲解实现；历史数字仍受原验证范围限制 |
| [智能体拓展方案](智能体拓展方案_持续更新.md) | 已实现与延期规划，不能把未来项当作当前能力或本轮任务 |

导入文件：[OpenAPI 工具定义](dify-agent-openapi.yaml)、[工作流 DSL](云忆相册助手-write.yml)。DSL 查询循环默认开启；仓库修改不会自动同步、导入或发布到 Dify 实例。

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

## 历史报告与证据

下列文件保留原检查日期、问题现场、修复步骤和历史测试数字。它们中的 PID、应用发布 ID、运行命令结果仅表示当时现场，不是当前运行状态。

| 文档 | 历史用途 |
| --- | --- |
| [2026-09-13 审计](agent-audit-2026-09-13.md) | 原问题、优先级与当时验证；部分问题已有后续修复 |
| [2026-09-13 修复进度](agent-repair-progress-2026-09-13.md) | 分阶段实施与检查记录 |
| [2026-09-14 个人版交付](agent-personal-delivery-2026-09-14.md) | 当时缩减交付范围和遗留事项 |
| [2026-09-15 对话修复](agent-chat-fix-2026-09-15.md) | Dify 对话错误与修复记录 |
| [2026-09-15 相册回收修复](agent-album-recycle-fix-2026-09-15.md) | 自然语言删除、预览和确认问题 |
| [2026-09-15 恢复与附件修复](agent-restore-ui-fix-2026-09-15.md) | 恢复分支、WebApp 界面、附件鉴权与配置问题 |
| [AI 应用量化测试报告](AI应用量化测试报告.md) | 历史实测及其样本和证据边界 |
| [通用样例报告](../evaluation/reports/example/report.md) | 示例数据生成结果，不是在线成绩 |
| [P0 样例报告](../evaluation/reports/p0-example/report.md) | 固定夹具结果，不是完整个人版验收 |

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
