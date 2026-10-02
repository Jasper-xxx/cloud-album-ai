# 存储配额校准与上传配置

2026-10-02 对照 `StorageQuotaService`、`UserStorageMapper`、`FileServiceImpl`、`RecycleServiceImpl` 和 `MultipartUploadService` 更新。

## 当前计费口径

- 配额单位是字节。`user_storage.used_space` 记录该用户持有的 `user_file` 关联对应文件的逻辑容量，不是 MinIO 桶内物理占用量。
- 回收站中的关联仍计入容量；移入回收站和恢复都不释放或重复扣减容量。永久移除用户关联时才释放该用户对应容量；对象仍被其他用户关联时不会因此删除共享对象。
- 上传先通过 Redis 原子预留检查 `used_space + reserved + 上传大小 <= total_space`，登记文件时再通过数据库条件更新扣减可用容量。完成、失败、取消等路径释放预留；预留过期时间使用 `upload.multipart.session-ttl-seconds`，默认 7200 秒。
- Redis 预留键是 `storage:reserved:<userId>`、`storage:reservations:<userId>` 与 `storage:reservation-expiry:<userId>`。它们不是数据库已用容量，不应混入下方汇总。

## 既有数据库校准

新建库的表结构由 Flyway `V0__init_schema.sql` 建立。下面是已有数据容量不一致时的**人工数据校准**，不是新的 Flyway 迁移，也不应在每次启动时运行。

校准前保留 `user_storage` 备份，并暂停上传、永久删除等会改变关联或容量的写入；让未完成上传结束或正常取消。校准期间仍有并发写入可能覆盖正确计数，不要用清空整个 Redis 来处理预留。

先只读比较当前值与应计入值：

```sql
SELECT storage.user_id,
       storage.used_space,
       COALESCE(usage_by_user.used_space, 0) AS expected_used_space,
       storage.total_space
FROM user_storage AS storage
LEFT JOIN (
    SELECT user_file.user_id, COALESCE(SUM(file.size), 0) AS used_space
    FROM user_file
    INNER JOIN file ON file.file_id = user_file.file_id
    GROUP BY user_file.user_id
) AS usage_by_user ON usage_by_user.user_id = storage.user_id
WHERE COALESCE(storage.used_space, 0) <> COALESCE(usage_by_user.used_space, 0);
```

确认差异后，才按同一口径执行校准。此 SQL 会更新现有的全部 `user_storage` 行；仅处理测试账号时，必须为外层 `UPDATE` 增加明确的 `WHERE storage.user_id IN (...)` 范围。

```sql
UPDATE user_storage AS storage
LEFT JOIN (
    SELECT user_file.user_id, COALESCE(SUM(file.size), 0) AS used_space
    FROM user_file
    INNER JOIN file ON file.file_id = user_file.file_id
    GROUP BY user_file.user_id
) AS usage_by_user ON usage_by_user.user_id = storage.user_id
SET storage.used_space = COALESCE(usage_by_user.used_space, 0);
```

汇总不按回收站标记过滤，也不按相册关联汇总，同一文件加入多个相册不会重复计算。该 SQL 不会补建缺失的用户容量行，不会更改 `total_space`，也不会修复孤立的文件关联；这些异常应单独核查。完成后再次比较，并确认已用容量超过总容量的账号是否需要调整容量策略，不能为使校验通过而随意截断实际用量。

## 浏览器分片上传

`MINIO_URL` 是后端 MinIO 客户端和分片预签名 URL 使用的入口，必须同时对后端和浏览器可达。浏览器直接 `PUT` 上传分片，之后由后端查询已上传分片并合并；分片流量不会经由 Vite 的 `/devApi` 代理。

MinIO CORS 需允许实际前端来源使用 `PUT`、`GET`、`HEAD`，并接受实际上传请求的请求头。默认开发来源为 `http://127.0.0.1:8080`，使用其他来源时需同步配置。后端 CORS 与 MinIO CORS 是两套独立配置；只调整后端无法解决浏览器直传的跨域失败。
