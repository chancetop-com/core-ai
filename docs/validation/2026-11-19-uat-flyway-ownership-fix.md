# UAT Flyway V42 所有权修复

- 日期：2026-11-19
- 环境：`FBRDevAKSCluster / uat-ai / postgresql-2`（primary）
- 问题：`app.conversation_requests` 及索引 owner 为 `postgres`，`core_ai` 执行 V42 `CREATE INDEX` 报 `must be owner of table`，Flyway history 停在 V41
- 结论：**FIXED**，UAT Flyway 已到 V45

## 修复 SQL

以 `postgres` 超级用户在 primary 执行：

```sql
ALTER TABLE app.conversation_requests OWNER TO core_ai;
ALTER INDEX app.conversation_requests_pkey OWNER TO core_ai;
ALTER INDEX app.conversation_requests_session_idx OWNER TO core_ai;
ALTER INDEX app.conversation_requests_expires_idx OWNER TO core_ai;
```

验证：

```sql
select c.relname, pg_get_userbyid(c.relowner)
from pg_class c join pg_namespace n on n.oid = c.relnamespace
where n.nspname = 'app' and c.relname like 'conversation_requests%';
```

四个对象 owner 均为 `core_ai`。

## Flyway

```powershell
kubectl -n uat-ai port-forward pod/postgresql-2 15433:5432
.\gradlew :core-ai-server-db-migration:flywayMigrate -Penv=uat
```

结果：

- V42/V43/V44/V45 通过；V42 表/索引已存在，Flyway 记录成功；
- V43–V45 为 `IF NOT EXISTS` 幂等重跑；
- `app.flyway_schema_history` 最新为 `45 messaging commands`。

## dev 复核

- dev `app.flyway_schema_history` 已是 `45 messaging commands`；
- 此前 `flywayMigrate -Penv=dev` 已成功应用 V45。

## 备注

UAT 应用仍保留 `SYS_TRACE_SPAN_MIGRATION=skip`（另一会话工作区改动）；本次只修 Flyway 所有权，未改应用配置。
