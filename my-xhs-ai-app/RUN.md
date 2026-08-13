# my-xhs-ai-app 运行手册（D1）

> 版本：2026-08-10 | 真实环境：MySQL 21.130.247.89:3306（云主机）+ TeamoRouter 模型

## 0. 凭据（不入库）
- 仓库根 `.env.local`（**gitignored**，持久于工作区）：`TEAMO_API_KEY` + `MYXHS_DB_URL/USER/PASSWORD`。
- 若 `.env.local` 丢失：TEAMO key 在 TeamoRouter 控制台；DB 只读账号密码需**重新生成**（见 §3）。

## 1. 运行
```bash
cd my-xhs-ai-app
set -a; source ../.env.local; set +a     # 注入凭据
mvn test                                   # H2 契约 + 真实库集成断言（有凭据才跑真实断言）
mvn package -DskipTests
java -jar target/my-xhs-ai-app-1.0-SNAPSHOT.jar   # 端口 19020
```

## 2. 端点
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/ai/health` | 健康检查 |
| POST | `/api/ai/chat` | 单轮对话（模型直答）|
| POST | `/api/ai/chat/stream` | SSE 流式（token→done）|
| POST | `/api/ai/agent` | **Agent 工具循环**：意图→真实指标工具→带来源回答 |

```bash
curl -X POST :19020/api/ai/agent -H 'Content-Type: application/json' \
  -d '{"message":"查一下 2026-08-01 到 2026-08-07 的下单量"}'
curl -X POST :19020/api/ai/agent -H 'Content-Type: application/json' \
  -d '{"message":"查一下 2026-08-10 到 2026-08-13 的支付成功率"}'
```

## 3. 只读账号恢复（.env.local 丢失时）
```bash
# 用 root 重生成（root 密码见部署包/运维，勿入仓库）
RO_PW=$(openssl rand -hex 16)
mysql -h 21.130.247.89 -P 3306 -uroot -p'<root密码>' <<SQL
DROP USER IF EXISTS 'myxhs_ai_ro'@'%';
CREATE USER 'myxhs_ai_ro'@'%' IDENTIFIED BY '$RO_PW';
GRANT SELECT ON my_xhs_order.*, my_xhs_order_0.*, my_xhs_order_1.*, my_xhs_order_2.*, my_xhs_order_3.*,
       my_xhs_payment.*, my_xhs_content.*, my_xhs_analytics.* TO 'myxhs_ai_ro'@'%';
FLUSH PRIVILEGES;
SQL
# 把 MYXHS_DB_USER/MYXHS_DB_PASSWORD 写回 .env.local
```

## 4. 已知取舍 / 加固清单（部署时执行）
- [ ] **主机白名单**：生产将 `myxhs_ai_ro`@'%' 收敛为 AI 应用 IP（当前 dev 环境为 '%'，见 `d0/D1-real-e2e-review.md` §六）。
- [ ] **索引**：数据量增长后应用 `src/main/resources/db/index-migration.sql`（`(created_at, deleted)`）。
- [ ] 密码轮换（Secret Manager 替代 .env.local）。

## 5. A3 行为数据 seed（测试数据，可清理）
- **注入**（2026-08-10）：`my_xhs_content.t_user_behavior` 64 行骤降数据（id ≥ 9000000000000000001、user 1001-1302、单真实 note 2087825399137484801）。
- ⚠️ **这是测试数据混入真实库**：真实分析前若需剔除，执行：
```sql
DELETE FROM my_xhs_content.t_user_behavior WHERE id >= 9000000000000000001;
-- 或 WHERE user_id BETWEEN 1001 AND 1302
```
- 真实库集成测试 `MetricRealDbIntegrationTest#真实库_content_interaction_48` 依赖此 seed；清理后该用例需改期望或删除。
