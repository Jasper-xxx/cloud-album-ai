# Cloud-Album 统一测评

这套工具把五类测评统一为 JSONL 观测记录，计算指标、执行门槛判断，并同时输出机器可读的 `report.json` 和中文 `report.md`。工具只使用 Python 标准库。

## 先跑通示例

在仓库根目录执行：

```powershell
python evaluation/run.py evaluate `
  --manifest evaluation/manifest.example.json `
  --output-dir evaluation/reports/example
```

示例 Manifest 中 `sample_data=true`，报告会醒目标注“只验证评测链路，不代表真实系统效果”。复制 Manifest 开始真实测评时必须改成 `false`。

## 指标口径

### 1. 以图搜图

- `Recall@K`：先对每个查询计算 `|TopK ∩ Relevant| / |Relevant|`，再对全部查询做宏平均。单个查询只有一个相关图片时，它等价于 Hit@K。
- `P95`：从客户端发出请求到读完响应的端到端耗时，使用 nearest-rank P95；请求失败仍以 Recall=0 计入，避免只统计成功请求造成幸存者偏差。
- 报告还包含 Hit@5、Hit@10 和请求错误率，方便定位问题。

建议人工构造至少 100 个查询，覆盖原图轻编辑、裁剪、压缩、颜色变化、截图/水印、局部物体和相似但不相关的 hard negative。查询图不要直接用图库中的原文件，否则结果会虚高。每个查询由两名标注者独立确认 relevant fileId，分歧再仲裁。

金标格式见 [image-search-gold.example.jsonl](datasets/image-search-gold.example.jsonl)。在线采集：

```powershell
$env:EVAL_USER_TOKEN = '<测试账号的 Sa-Token>'
python evaluation/run.py collect-image-search `
  --dataset evaluation/datasets/image-search-gold.jsonl `
  --base-url http://127.0.0.1:8088 `
  --output evaluation/observations/image-search.jsonl `
  --repeats 3
```

默认每次请求间隔 5.1 秒，以遵守 `/imageSearch/search` 当前 0.2 req/s 的限流。若在隔离压测环境关闭限流，可显式传 `--delay-ms 0`。延迟正式统计前应先做 5～10 次不计分预热，并固定图库规模、特征模型版本和运行资源。

### 2. 人脸聚类

- `Pairwise Precision`：预测为同一簇的人脸对中，真实同人的比例。
- `Pairwise Recall`：真实同人的人脸对中，被聚到同一簇的比例。
- `Pairwise F1`：上述两者的调和平均。
- `错误合并率`：`FP / predicted_positive_pairs`，也就是 `1 - Pairwise Precision`。
- `人工修正率`：需要移动、拆分或重新归组的人脸数 / 总人脸数；只有提供 `corrected` 或 `corrected_cluster_id` 时才计算。

工具用簇计数和交集计数组合数计算 Pairwise 指标，不会枚举 O(n²) 的所有人脸对。`predicted_cluster_id=null` 的人脸按互相独立的 singleton 处理。

人工金标建议至少覆盖 50 个身份、每个身份 3～20 张图，并加入单人脸、多人合照、侧脸、遮挡、跨年龄、低照度和相似外貌 hard cases。格式见 [face-clustering.example.jsonl](datasets/face-clustering.example.jsonl)。当前后端没有“真实身份”字段，因此 `truth_person_id` 必须来自人工标注；`predicted_cluster_id` 使用系统 `person_id`。

### 3. Agent

- `工具选择准确率`：整条工具名序列与金标完全一致的用例数 / 总用例数。
- `参数抽取准确率`：对齐工具调用后，在期望参数与实际参数的字段并集上做严格字段级准确率；意外多传的参数也会扣分。
- `任务完成率`：`actual.completed=true` 且 `actual.verification.passed=true` 的用例数 / 总用例数。`completed` 与 `passed` 必须是 JSON 布尔值，字符串 `"false"` 会作为输入错误拒绝。
- `验证覆盖率`：具有明确验证来源且验证通过的用例比例。只读任务可使用响应断言，写任务必须使用后端状态或业务表验收，不能采信模型自述。

P0 已提供 96 条可复现金标 [agent-p0-gold.jsonl](datasets/agent-p0-gold.jsonl)，覆盖只读单工具路由、写操作预览、精确确认词执行、待确认状态/取消以及含糊确认拒绝。匹配的示例预测位于 [agent-p0-predictions.jsonl](examples/agent-p0-predictions.jsonl)，`manifest.example.json` 以 `case_count >= 80` 作为硬门槛。真实 OperationId 以 [dify-agent-openapi.yaml](../docs/dify-agent-openapi.yaml) 为准。

把 Dify 调试轨迹整理为 prediction 文件的 `actual.tool_calls`，并由独立验收器填写 `actual.completed` 与 `actual.verification`。正式运行时只替换 predictions，不修改 gold。记录格式由 [agent-gold.schema.json](schemas/agent-gold.schema.json) 和 [agent-prediction.schema.json](schemas/agent-prediction.schema.json) 固定；生成器 [generate_p0_fixtures.py](datasets/generate_p0_fixtures.py) 用于确定性重建基准夹具。

### 4. 安全测试

每条场景必须同时验证响应和副作用。采集器先读取 `side_effect_check.request` 的状态快照，发起攻击请求，再次读取相同状态；只有 `response_blocked=true` 且 `side_effect_safe=true` 才生成 `blocked=true`。三个字段和 `skipped` 都必须是 JSON 布尔值。

- `unauthorized_access`：用户 A 读取或修改用户 B 的 fileId、albumId、personId、taskId。
- `confirmation_bypass`：不传 `confirmed`、传 false、直接调用 execute、复用旧确认。
- `parameter_tampering`：预览后替换 action、fileIds、albumId、tagName 或扩大数量。

P0 场景集 [security-p0-scenarios.jsonl](datasets/security-p0-scenarios.jsonl) 共 72 条，三类攻击各 24 条；示例门禁要求三类各不少于 20 条、`skipped_case_count == 0`、副作用验证率和拦截率均为 100%。格式由 [security-scenario.schema.json](schemas/security-scenario.schema.json) 与 [security-observation.schema.json](schemas/security-observation.schema.json) 固定。

```powershell
$env:EVAL_USER_A_TOKEN = '<隔离测试账号 A 的 token>'
$env:EVAL_USER_B_TOKEN = '<隔离测试账号 B 的 token>'
$env:EVAL_OTHER_USER_TASK_ID = '<账号 B 的 taskId>'
$env:EVAL_PENDING_ALBUM_ID = '<账号 A 的待确认相册操作 ID>'
$env:EVAL_PENDING_TAG_ID = '<账号 A 的待确认标签操作 ID>'
python evaluation/run.py collect-security `
  --scenarios evaluation/datasets/security-p0-scenarios.jsonl `
  --base-url http://127.0.0.1:8088 `
  --output evaluation/observations/security-p0.jsonl `
  --allow-mutating
```

带 `mutating=true` 的参数篡改场景默认拒绝执行。只能在可回滚的隔离账号中显式加入 `--allow-mutating`，并在执行后核对/清理测试标签。

正式采集前需准备场景文件中引用的全部 `EVAL_*` 环境变量。副作用探针必须读取能反映真实业务状态的字段，例如待确认操作的 `$.data.status`；不要用固定常量或无关健康检查冒充副作用验证。

### 5. 异步任务

- `最终成功率`：故障注入用例最终进入 SUCCESS 的比例。
- `重复执行数`：每个任务 `max(execution_count - 1, 0)` 的总和。重试/恢复允许重新执行 handler，因此它不是重复副作用。
- `重复业务副作用数`：每个任务 `max(business_effect_count - 1, 0)` 的总和，推荐作为幂等性硬门槛。
- `恢复耗时`：从故障注入到 SUCCESS/DEAD/CANCELLED 的时间；报告平均值和 P95。

本次新增的数据库迁移 `V5__async_task_execution_count.sql` 会增加 `async_task.execution_count`，每次数据库 claim 成功时原子加一。它能统计“claim 后 Worker 崩溃再恢复”这种 `retry_count` 漏记的执行。部署评测环境前先执行 Flyway 迁移。

建议每种故障至少重复 20 次：AI 服务连续 503、MinIO 超时、Worker claim 后强制退出、RabbitMQ 不可用、MySQL 短暂断连。故障注入必须在隔离环境完成，并按以下顺序记录：

1. 创建任务并记下 taskId、taskKey 和注入时间。
2. 注入单一故障，确认任务进入 RUNNING/FAILED 或 Outbox 积压。
3. 恢复依赖，不手工篡改任务终态；等待调度/补偿扫描。
4. 用业务表或对象存储检查 `business_effect_count`，不能拿 handler 调用次数代替。
5. 轮询终态并生成观测记录。

轮询采集格式见 [async-task-observations.example.jsonl](datasets/async-task-observations.example.jsonl)：

```powershell
$env:EVAL_USER_TOKEN = '<测试账号 token>'
python evaluation/run.py collect-async-tasks `
  --cases evaluation/datasets/async-task-cases.jsonl `
  --base-url http://127.0.0.1:8088 `
  --output evaluation/observations/async-tasks.jsonl `
  --timeout-seconds 900
```

若用例不提供 `fault_injected_at` 或后端没有 `completedAt`，采集器会把自身轮询耗时作为恢复耗时下界，并标记 `recovery_time_is_lower_bound=true`。

## 生成真实总报告

复制 [manifest.example.json](manifest.example.json) 为 `manifest.real.json`，把 `sample_data` 改成 `false`：普通 suite 的 `input` 指向真实观测文件，Agent 保留 `gold` 并把 `predictions` 指向真实轨迹。门槛是工程下限，不是行业标准，应按业务风险、图库规模和硬件基线评审后冻结。

```powershell
python evaluation/run.py evaluate `
  --manifest evaluation/manifest.real.json `
  --output-dir evaluation/reports/baseline-001
```

进程退出码为 0 表示全部门槛通过，2 表示指标计算完成但至少一项门槛失败，1 表示输入或运行错误，适合直接接入 CI。

## 自测

```powershell
python -m unittest discover -s evaluation/tests -v
mvn -f backend/pom.xml -q test
mvn -f backend/pom.xml -q verify
```

Python 测试覆盖指标公式、严格布尔类型、JSON schema 文档、96/72 条数据规模、三类安全数量门槛、execute 凭证白名单、副作用前后快照，以及示例 Manifest 的完整 P0 门禁。
