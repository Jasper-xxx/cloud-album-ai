# 第二期查询到写预览桥接：新对话交接

后续收尾更新：用户已授权代理接管重点验证、修复与发布。第二期现已完成重点在线验收并发布本地 Dify；本交接中的原不运行约定属于初始历史，以[第二期验收收尾](dify-read-write-phase2-acceptance-2026-10-04.md)为最新状态。

交接日期：2026-10-04，Asia/Shanghai。项目 `D:/projects/Cloud-Album`，PowerShell。同一项目、同一本地工作区继续，不创建 worktree；第一期尚有大量未提交与未跟踪文件，不能从旧 HEAD 重新开始。

## 用户最新授权与第一期收尾

用户明确反馈：“测试结束了，没有问题。现在可以开始实现第二期了，但是需要换新对话进行，你自行交接一下任务，模型就用gpt6.1 Sol极高。”随后补充：“先进行第一期收尾再开新对话。”

第一期 A～C 已完成源码、DSL、静态检查、交付文档与本轮用户在线验收。按用户总体通过结论归档，未提供的完整矩阵逐项轨迹不补写成绩。自动化测试仍未运行。第二期实施已有直接授权，不再重复询问是否开始；原方案中“第二期暂不实施”是历史范围，已被最新授权取代。

新对话模型使用 `gpt-6.1-sol`，推理强度 `xhigh`（极高）。这是 Codex 新对话配置，Dify 工作流继续使用原 `qwen-flash`/通义提供方，不据此修改业务模型。

第一期冻结版本：`docs/releases/dify-read-loop-phase1-2026-10-04.yml`，911502 字节，SHA-256：`269227f5a45cbbf06a0aab75a0998a11ae7444255cb289f5578c5452a205536c`。与收尾时 `docs/云忆相册助手-write.yml` 字节一致，第二期不得覆盖冻结快照。需要回退时由用户导入该文件为副本并重绑原工具/模型，或关闭 `read_loop_config` 中的开关回原只读路径；不自行更改运行中应用。

实施前 Git HEAD：`4ccef7850fb77570cf5b601a41816abf1937a736`。这不是第一期最终代码：第一期在未提交工作区，保留全部现有修改、新文件及用户原有文档改动，不能 reset、stash 丢弃、clean 或从 HEAD 重建。没有自动 Git 提交。

## 第二期目标与范围

先读 `docs/dify-agent-loop-implementation-plan-2026-10-02.md` 第 9 节，再读第一期交付/验收说明。实现当前消息明确请求“先查询指定范围，再整理到普通相册或添加/移除标签”时的受控桥接；最终只生成一次真实写预览，下一条明确确认仍走现有确定性 Execute 门禁。

1. 当前写意图路由可能在只读循环前进入 Preview。增加明确的“需先解析目标范围”模式，不能仅从只读出口连一条写边。只有当前消息明确授权的操作与目标才可进入桥接；普通搜索不得自动创建预览。
2. 定义请求内桥接契约：操作、目标相册/标签、校验后的筛选、真实照片短引用集合、数量与分页完整性。写目标不由历史聊天恢复，不新增持久写目标或凭据记忆。
3. 代码仅从本次成功、未截断、归属仍须后端校验的证据映射解析真实 `fileIds`。模型不能编造 ID、引入未知/其它会话引用或读取确认凭据。
4. 区分“最多 N 张”与“所有匹配照片”。范围未拿全、预算不足、证据截断、重复/不完整资源、歧义或超操作上限时不能把第一页冒充全部，不创建部分范围预览，不拆成多次自动预览/执行；要求缩小范围或报告不能完成。
5. 查询目标完成后退出 Loop，至多调用一次对应原 Preview 工具，复用 `scripts/dify/render_preview.py` 与原 pending 凭据保存节点。先核对各操作真实上限与现有 DTO/OpenAPI，不猜范围。
6. 查询/预览失败、零结果、无变化或目标歧义时，不签发虚构确认话术、不覆盖或清除原有效 pending。
7. 确认、取消、状态、附件、所有权、会话绑定与幂等校验继续保留。Loop 不产生 `confirmed=true`，不直连 Execute，不读确认 Token。修改范围须重新请求和预览；刷新、超时或跨会话不恢复写目标。
8. 本期先做普通相册/标签整理。恢复、删除、分享、下载、附件保存、AI 标签提交、人物动作与后台相似任务继续原流程；不加入自动写入、复杂多动作执行、新数据库或新后端编排服务。

## 必须继续遵守的工作约定

- 不启动/重启任何服务，不运行 pytest/Maven/Vitest、构建或联调，不发起真实模型/业务工具请求，不导入/发布 Dify；用户自行在线验证。可编写有意义的测试代码、生成 DSL、做文件 YAML/AST/引用/差异/幂等检查。
- 只使用 `album_test_a`、`album_test_b` 及测试图片；本代理不实际操作照片或数据。不要为了造失败而关闭服务、改凭据、造重复相册或修改数据库。
- 不重复环境排查：此前 `ToolRuntimeInvocationError / Reached maximum retries (0)` 已由用户确认是后端未启动。修正错误文字后已归档，不能当作当前结果适配故障。
- 持续推进直到第二期可检查交付，不因已有禁止运行测试约定停在方案或要求重复授权。不自动升级 Dify，不移除既有安全门禁。
- 新阶段必要的路由、请求内桥接和生成代码可以修改；原 Execute/确认凭据/附件/所有权保护应精确保留。静态检查允许差异需逐项声明，不能为通过检查整体豁免原节点。

## 第一期代码与兼容基线

本地 Dify 源码依据为 `D:/Docker_data/dify-main`，版本 1.14.2，DSL 0.6.0；使用实际 Loop fixture。当前 DSL 175 个节点、59 个 Loop 子节点、24 个生成 Code 节点。原 110 节点中 108 个解析内容保持 HEAD，2 个只读节点有精确短答修正；原图仅 Start 插入配置及只读入口插入开关的两条边改变。

核心文件：

- `scripts/dify/read_loop_state.py`：`configure/initialize/prepare/authorize/observe/observe_failure/finalize`，请求内状态、冻结目标/约束、证据、预算、停止和回复。
- `scripts/dify/read_loop_validate.py`：严格规划 JSON、9 工具参数白名单、真实资源依赖、日期与冻结条件校验；首轮任务含最终目标，不把相册解析列表作为最终照片目标。
- `scripts/dify/read_loop_observation.py`：9 个真实接口响应适配，HTTP/业务状态、分页与结果格式、text/json 一致性及脱敏。
- `scripts/dify/read_continuation.py`：旧路径短答续页系统规则、统一肯定词和原 pending 含糊词集合扩充。
- `scripts/dify/repair_read_loop.py`：稳定节点 ID、AST 依赖内嵌、Native Loop 与失败分支；`scripts/dify/repair_workflow.py` 已调用该生成器。
- `scripts/dify/inspect_read_loop_static.py`：文件静态检查，当前对 HEAD 仅允许两处原只读精确改动和两条原边插入，并验证重新生成字节幂等。
- `evaluation/tests/test_read_loop_policy.py`、`evaluation/tests/test_dify_python_nodes.py`、`backend/src/test/java/com/memory/xzp/agent/DifyWorkflowContractTest.java`：已编写而未运行。
- `docs/dify-read-loop-delivery-2026-10-02.md`、`docs/dify-read-loop-acceptance-2026-10-03.md`：第一期交付、用户反馈、运行证据边界与回退。

当前配置：`enabled=True`、最多 3 轮规划/3 次查询、整条路径最多 5 次模型调用预算（写意图识别已占 1 次）、软时限 90 秒、默认 pageSize=20、最多 60 条收集记录、观察 6000 字符、状态 32768 字节。90 秒不能中断阻塞调用；不增加隐式重试。第二期若增加预览调用或必要的分类，要明确独立计入预算并记录变化，不能假装包含在旧计数中。

Loop 两个规划器无 `memory` 字段：Dify `window.enabled=false` 只关闭窗口限制，并非关闭记忆，否则会触发 USER 必须有 sys.query 的清单错误。只接本次脱敏 plannerInput，不把写历史塞入 Loop。旧 `extract_keyword` 保留最近 8 轮上下文。

短答行为：上一条唯一明确询问是否下一页、成功参数与 hasNext 可恢复且无 pending 时，“是/好/可以/行/嗯/ok”等保留工具、筛选和 size，只增加 current；末页不查，否定停止，上下文不可靠则澄清。存在 pending 时含糊短答不执行也不自动续页。“确认/执行/确认执行/开始执行/可以执行/同意执行/confirm/execute”仍是原确定性写确认专用词，不能用于翻页。

已修复的只读问题：简单未标签查询合法示例与 validationCode；相册依赖首轮冻结最终目标和筛选、后续省略参数继承已验证条件、每次照片查询仍需真实资源引用；传输错误与格式校验错误分开；肯定短答入口和上下文规则。

## 起步与交付顺序

1. 读取当前工作区和冻结 DSL，先记录第一期最终基线，核对原写/查询入口与 Preview 接口。不要把全套第一期在线测试重新作为启动前置条件。
2. 设计最小桥接契约并实现严格的当前消息授权、完整范围与证据解析；保留简单只读/直接写预览旧行为。
3. 实现生成器/DSL 和明确错误出口，补正常桥接、无结果、部分分页、预算不足、歧义/未知引用、pending 保留、一次预览及确认分离的静态/待执行契约用例。
4. 完成文件静态检查、生成幂等、精确保护差异，交付用户可导入 DSL、第二期说明、简易人工用例和回退方式。报告已实现/已静态核对/待用户运行，不自动执行测试或发布。

文件静态检查已有缓存 PyYAML，可在 PowerShell 临时设置：

```powershell
$env:PYTHONPATH='C:/Users/hasee/AppData/Local/uv/cache/archive-v0/e89Lw6zm3dHwkhB1'
python scripts/dify/repair_read_loop.py
python scripts/dify/inspect_read_loop_static.py
git diff --check
```

以上只生成/检查文件，不是策略测试或在线调用。不要把测试文件的 AST 解析写成测试通过。第一期冻结 DSL 比对和上述检查已在旧对话收尾完成，新对话只在新变化需要时复查。
