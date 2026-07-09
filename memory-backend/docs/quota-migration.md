# Storage Quota Migration

Run this once before enabling quota enforcement in an existing environment.
Files in the recycle bin still consume logical user capacity until permanently
removed.

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

For browser multipart uploads, configure MinIO CORS to allow the frontend
origin to use `PUT`, `GET`, and `HEAD`. The public MinIO endpoint used by
`MINIO_URL` must also be reachable from users' browsers.
