# 第二期查询后写预览交付

交付及收尾日期：2026-10-04（Asia/Shanghai）。第二期已实现、完成重点在线验证与修复，并发布本地 Dify。用户最新授权代理接管验证与收尾，覆盖最初仅静态检查的约定。实际成绩和未测范围见[验收收尾记录](dify-read-write-phase2-acceptance-2026-10-04.md)。第一期及第二期初始交付快照保留，没有重建工作区或自动提交 Git。

## 当前交付与启用状态

可导入工作文件：[云忆相册助手-write.yml](云忆相册助手-write.yml)。最终冻结文件：[第二期最终 DSL](releases/dify-read-write-phase2-2026-10-04-final.yml)；[最终核对记录](releases/dify-read-write-phase2-2026-10-04-final-inspection.json)记录实际测试结果。最终 DSL 为 1010647 字节，SHA-256：`4843f58823e8b06a9a3a4e68eb05bf66ce59165d6bad4993d789f0399691fd45`，与工作文件字节相同。

初始交付的[DSL](releases/dify-read-write-phase2-2026-10-04.yml)及[静态记录](releases/dify-read-write-phase2-2026-10-04-inspection.json)是修复前历史，不是当前发布版本。初始快照 1006953 字节，SHA-256：`7a245c7643ecf62a713fa30d4b3f28eecf26a6ddeeeb99bf0933054a9d1a2682`。

当前 `enabled=True`、`writePreviewEnabled=True`。初始交付时桥接默认关闭，待实际后端的显式 ID 保护确认后开启。本轮已在线证明：非空 fileIds 经所有权校验为空时返回零变化，不回退目标标签范围。后端没有改变 DTO、OpenAPI、数据库或确认协议。现有服务已经加载该补丁，无须重启。

本地 Dify 已备份草稿、同步修复、开启桥接并发布；原通义 `qwen-flash` 和 Cloud-Album 提供方保留。新环境导入前仍应先部署同一后端保护，并配置正确业务身份；仓库文件本身不会同步其它 Dify 实例。

桥接关闭时，能识别的复合查询写请求会说明未生成预览，避免回退为旧宽范围选择。普通查询、旧直接预览、确认/取消/状态和附件仍走原路径。

## 本次请求的范围契约

新增确定性入口 `read_loop_scope_entry` 位于原 `parse_write_action` 之后、原 Preview 路由之前。原确认、取消、状态及 AI 任务状态已经由原解析器确定；这些模式直接继续原流程。新模式 `need_scope` 只从当前消息拆出照片范围、一个操作、一个相册或标签目标；不使用历史、附件 ID、pending 或确认 Token。

支持的写目标是整理到一个普通相册、为照片添加一个标签或移除一个标签。目标名称最多 30 个 Java 字符，与原 DTO/控制器一致。已有目标相册通过本次完整普通相册列表确认唯一性，ID 仅由代码解析；未存在且列表完整时使用原 `create_album_and_add_files` 预览，确认后可创建相册。重名、列表不是完整第一页、名称脱敏或 ID 数值不安全时停止。

为避免模型遗漏条件后扩大写范围，本期使用有限语法，必须消费完整范围文本；未支持的修饰不能丢弃后继续。

| 范围 | 支持的表达示例 |
| --- | --- |
| 基本范围 | `所有照片`、`未标签的照片`、`已有标签的照片` |
| 地点 | 裸 `北京/上海/天津/重庆`；其它值显式写 `城市「杭州」`、`省份「浙江」`、`国家「中国」`、`区县「西湖区」` |
| 源相册 | `相册「源相册」里未标签的照片`；需本次完整列表和唯一真实源相册引用 |
| 标签、设备、关键词 | `标签「旧标签」的照片`、`设备品牌「Canon」设备型号「EOS R」的照片`、`关键词「测试」的照片`；只支持一个精确源标签 |
| 日期 | `今天/昨天/今年/去年/最近 N 天`，或 `2026-01-01 至 2026-09-30`；相对日期按本次 Asia/Shanghai 参考日期冻结 |
| 完整性 | `未加入普通相册`、`已加入普通相册`、`缺少地理位置/特征/AI分析`、`有地理位置/有GPS` |
| 数量 | `前5张/最多5张/至多5张` 表示至多前 N 张；未明确数字时按全部匹配照片解析；“全部”和数字上限同时出现视为歧义 |
| 排序 | 默认拍摄时间降序；`按上传时间` 使用上传时间降序；`按拍摄时间` 使用拍摄时间降序 |

本期桥接将“照片/图片”固定为 `mediaTypes=["picture"]`。视频、GIF、人物范围、多源标签 AND/OR、语义同义词替代、多动作和无法完整消费的筛选表达需重新明确，不使用部分范围生成预览。旧直接选择“最新 N 张”、显式 ID、空相册创建和原支持的简单选择方式继续原流程。恢复、删除、分享、下载、附件、AI 标签任务、人物动作、相似后台任务不接入桥接。

可直接尝试的输入：

```text
先查找北京未标签的照片，再整理到相册「测试旅行」
先查找城市「杭州」去年未标签的照片，前5张，再添加标签「待整理」
先查找标签「旧标签」的照片，再移除标签「旧标签」
先查找相册「源相册」里未标签的照片，再整理到相册「测试旅行」
```

桥接每轮使用代码按冻结范围和真实证据产生的 `requiredPlan`，由短系统提示要求模型输出，再进行严格校验；写目标名称留在代码侧，避免目标被当作来源。普通只读请求沿用通用规划提示。第一轮规划的 `task` 必须严格匹配代码已固定的最终筛选、全部目标和数量。后续只能使用原冻结条件及本次成功证据做必要依赖或下一页。目标写标签不会混入源查询标签；目标相册不会混入源相册条件。模型只能提出九个只读白名单查询，不能提供照片 ID、写参数、确认或 Preview 调用。

## 分页和退出后的单次 Preview

全部范围需从第一页开始连续读取，页大小、总数和页数保持一致，最后一页 `hasNext=false`，成功照片数等于 total。前 N 张需收集 `min(N,total)` 张；页大小使用 N 的约数，避免改变 SQL 分页偏移或超取后随意截断。重复 ID、跨页重叠、未知引用、错误/缺页、条件不符、证据/状态截断、预算停止和零结果都不放行预览。前 N 张的查询还有下一页是允许的；全部范围的第一页有下一页时不能声称完成。

Loop 退出后，`read_loop_preview_request` 再核对成功观察、真实照片映射、连续页和数量，仅输出本次证据里的原生 fileIds 数组。`read_loop_preview_route` 至多选一个原 `previewAlbumAction` 或 `previewTagAction` 端点；两个工具节点在 Loop 外，无重试。查询与 Preview 分别绑定原生会话 ID。

桥接 Preview 与旧直接 Preview 的互斥返回经聚合器送入原 `capture_pending_album/tag`，继续复用原 `render_preview.py`、原 pending 保存和展示节点。失败/无变化保留原有效 pending；查询失败、零结果和不完整范围根本不进入 pending 保存。下一条确认依旧走原确定性 Execute 路由，业务参数只来自服务端冻结载荷。本次 Loop 及后续路径均不读取确认 Token、不生成 `confirmed=true`、不连接 Execute，也不会从续页、刷新或历史恢复待写范围。

Preview 的传输结果未知时保留本地凭据，并沿用原状态查询指引；不能推断服务端注册成功，或推断服务端旧记录状态完全未变。没有新增自动重试。

后端可能因为照片所有权、活动状态、共享或已无变化而缩小实际影响范围；以真实 Preview 警告和影响文件为准。文件分页接口没有数据库快照协议：总数变化、重复/缺页可被拒绝，但无法证明并发修改下的全局时间点快照。新相册名在列表查询之后被外部创建或修改也不提供事务快照保证；原服务端仍在 Preview 和 Execute 做业务校验。

## 预算与精确变更

原三轮规划、三次只读查询、最多五次显式模型调用（含写意图识别）、90 秒软时限、每页最多 20、60 条证据、6000 字符观察、32768 字节状态上限均未提高。目标普通相册列表也占一次查询和一轮规划；源和目标相册可由同一次完整列表共同确定，避免重复列表请求；最多三次查询通常可以读取两页照片。新增预算为 Loop 外 **最多一次 Preview**，因此桥接路径至多三次只读 HTTP + 一次 Preview HTTP；没有额外模型总结。90 秒仍不能中断已经开始的模型/HTTP 调用。实际 SDK 重试由用户核对轨迹。

本期图共 186 个节点，59 个 Loop 子节点，28 个新流程嵌入 Code 节点。相对第一期新增 11 个节点。原 110 个节点中，本期两个预览接收节点的输入 selector 改变；在线复验另精确修复附件复合指令判断与只读 pending 指引；相对 HEAD 的另两个只读节点差异仍是第一期精确短答修正。没有整体豁免保护节点。

| 相对第一期的原节点/边 | 必要差异 |
| --- | --- |
| `capture_pending_album/tag` | 只改 `raw` 和 `expected_family` 输入：互斥 Preview 结果、端点固定操作族；Code 源码、凭据输入和原 assigner 全部保留 |
| `parse_write_action → route_preview` | 插入当前消息范围入口；非桥接模式继续原 Preview/Execute/取消/状态路由 |
| 原两个 Preview 成功边 | 插入结果聚合及固定操作族节点，再进入原接收节点；原工具参数和失败边不变 |
| 第一期 Loop 出口和 init | 查询范围/冻结桥接传入公共 init；退出后加范围核对和互斥 Preview 路由；普通搜索不会创建预览 |
| `attachment_intent`、`validate_read_plan` | 精确修复复合附件保存授权和 pending 短答指引被清理器覆盖的问题 |
| `AgentController.resolveTagActionFileIds` | 唯一生产 Java 差异：显式非空 fileIds 经所有权校验为空时不再按标签扩大范围 |

Start 的配置插入、原只读入口开关边是第一期既有差异。原 Execute、取消、状态、所有权、会话绑定、幂等和其它操作族节点及边按精确基线保留；附件意图节点只增加复合/询问拒绝，附件工具不变。

## 文件核对和已执行契约

已运行文件生成及静态脚本，核对 YAML/AST、嵌入依赖、Code 输入/输出、selector、节点/边、Loop 归属、白名单、完整后续路径可达性、第一期快照哈希、原保护节点精确差异、Java 唯一生产分支差异及生成字节幂等性。静态核对没有执行策略函数或测试用例，不能代替 Dify 导入或运行成绩。

已执行相关文件（Python 62 项、Java 16 项通过）：

- [第二期 Python 契约](../evaluation/tests/test_read_write_bridge.py)：当前消息授权、普通查询/控制入口、冻结条件、相册歧义、连续分页、前 N 张、失败/预算/截断、未知引用、源/目标分离、无变化保留 pending。
- [Java DSL 契约](../backend/src/test/java/com/memory/xzp/agent/DifyWorkflowContractTest.java)：Loop 内只读、Loop 外互斥 Preview、原生 ID 数组/会话、原 pending 接收、无 Execute 可达路径。
- [Java 标签范围回归](../backend/src/test/java/com/memory/xzp/controller/AgentControllerSecurityTest.java)：显式移除标签的 ID 全部失效时返回零变化、不按标签检索其它照片。

本轮实际执行命令：

```text
python -m unittest evaluation.tests.test_read_loop_policy evaluation.tests.test_read_write_bridge evaluation.tests.test_dify_python_nodes
cd backend
mvn -Dtest=DifyWorkflowContractTest,AgentControllerSecurityTest test
```

生成命令仍为 `python scripts/dify/repair_workflow.py`；仅更新 Loop/桥接为 `python scripts/dify/repair_read_loop.py`；文件静态核对为 `python scripts/dify/inspect_read_loop_static.py`。本机已有 PyYAML 缓存可通过交接说明中的 PYTHONPATH 使用，不安装新依赖。

## 简易人工验证

以下保留为后续回归参考，实际已测项目与成绩以[验收收尾记录](dify-read-write-phase2-acceptance-2026-10-04.md)为准；不要求重做整套第一期在线矩阵。

| 场景 | 输入/检查点 |
| --- | --- |
| 普通查询 | `查找北京未标签的照片，最多5张`：只读结果，无 Preview、无新 pending |
| 查询后相册 | 用上面的北京相册输入；目标唯一且范围完整时，先 listAlbums，再 advancedSearchFiles，退出后一次 previewAlbumAction；展示后端真实预览，未执行 |
| 添加/移除标签 | 用上面的标签输入；查询参数只含源筛选，目标标签单独冻结；范围完整后一次 previewTagAction，无其它写工具 |
| 前 N 张与全部 | 用至少有下一页的既有测试范围；“前5张”只冻结至多5个 ID；不写数量时必须读完整范围，预算不足则不 Preview |
| 不完整/零结果/重名 | 使用现有夹具或已知空标签；不创建新的确认、保持原有效 pending；没有相应数据则记待验证，不制造破坏性故障 |
| 原 pending 保留 | 先取得测试预览但不确认，再查零结果或进入失败/无变化桥接；查询原状态，凭据仍对应原操作 |
| 下一条确认 | 取得本期真实预览后先答“好”：不执行；明确选择执行再答“确认”：原门禁只发送服务端凭据与 confirmed=true。同账号同会话；刷新/新会话不能从历史恢复范围 |
| 单次边界和后端补丁 | 核对实际轨迹：最多3次查询+1次 Preview，无自动 Execute。显式 ID 全失效用离线 Java 用例或现有隔离夹具验证，不删除真实照片制造故障 |
| 原入口 | 只核对必要回归：取消/状态、直接最新N张预览、空相册、附件、恢复及任务入口仍走原流程 |

每项只记录脱敏的本次请求、真实工具/调用数、实际筛选/页码/总数、影响数量、停止原因和回复；不复制确认 Token、幂等键、密钥或签名 URL。

## 回退

先设 `writePreviewEnabled=False` 并同步发布到 Dify，即可停用桥接、保留第一期查询 Loop。要同时回退查询 Loop，再设 `enabled=False`。图格式/导入问题可直接导入第一期冻结快照 [dify-read-loop-phase1-2026-10-04.yml](releases/dify-read-loop-phase1-2026-10-04.yml)，SHA-256 为 `269227f5a45cbbf06a0aab75a0998a11ae7444255cb289f5578c5452a205536c`；本期没有覆盖它。

后端显式 ID 收紧可与第一期 DSL 兼容，保留该补丁即可，无数据库回滚。已有有效 pending 按原状态/取消流程处理，不能手工清空凭据或用旧会话恢复写目标。本次已更新并发布本地应用，旧发布版本保留；具体备份与发布 ID 见验收收尾记录。
