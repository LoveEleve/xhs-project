# my-xhs Full-Chain Testing Methodology

> v2.0 EN | 2026-08-08 | Test session: 94→106 endpoints
> Core: Business logic correctness first (L1), then data (L2), then production quality (L3), then observability (L4)

## 0. Layered Verification Model

```
                    ┌──────────────────────────────┐
                    │  Layer 4: Observability        │
                    │  SW trace + Prometheus + Kibana│
                    ├──────────────────────────────┤
                    │  Layer 3: Production Quality   │
                    │  Perf/Scalability/Dist/Micro/CC│
                    ├──────────────────────────────┤
                    │  Layer 2: Data Correctness     │
                    │  Redis + MySQL + MQ + ES       │
                    ├──────────────────────────────┤
                    │  Layer 1: Business Logic       │
                    │  Flow + State Machine + Edges  │
                    └──────────────────────────────┘
```

**No layer skipping**: L1 not passed → don't check L2. L2 not passed → don't check L3.

## 1. Layer 1: Business Logic Correctness (Foundation)

### 1.1 Three Questions Per Endpoint

| Question | Method | Output Location |
|------|------|------|
| What does it do? | Read Controller + Service source | Execution file §Business Logic |
| What downstream does it trigger? | Read Feign/MQ/Consumer source → ASCII flow | Execution file §ASCII Flow |
| Is the full business flow correct? | Walk through state machine → verify state transitions | Execution file §Chain Verification |

### 1.2 Business Logic Verification Template

```
§ Business Logic (3-5 sentences)
  - Core function of this endpoint
  - Input/output model
  - Triggered downstream operations (Feign/MQ/Scheduled jobs)

§ ASCII Flow Diagram
  curl → Gateway → Microservice → MySQL/Redis/MQ/ES

§ Chain Verification
  - State transitions: order(status=0) → pay(0→1) → ship(1→2) → complete(2→3)
  - Data flow: order table → inventory preDeduct → payment table → inventory confirm
  - Edge cases: duplicate order(idempotent), insufficient stock(reject), timeout cancel
  - Rollback: cancel order → release inventory → return coupon
```

### 1.3 9-Lens Review

| Lens | Per-Endpoint Check | Example Finding |
|------|------|------|
| **1. Business Coherence** | Is the business flow complete? Is the state machine closed? | D01→I02→M01→D10→I03 full chain |
| **2. Data Consistency** | Redis ↔ MySQL ↔ ES consistent? | Redis preDeduct deducted but MySQL locked_stock not written? |
| **3. Idempotency** | Repeated requests idempotent? Distributed lock correct? | Duplicate order → 40201 reject or double deduct? |
| **4. Rollback** | Failure/cancel fully rolled back? | Cancel → inventory release + coupon return executed? |

#### Deep Lenses 5-9 (L3 — every endpoint must have actual checks, NO ✅ placeholders)

##### 5. Performance

| Check | Method | Example |
|------|------|------|
| RateLimit working? | Consecutive calls → should reject at limit | P01 5/60s → 6th call should 429 |
| Response time ok? | X-Trace-Id → SkyWalking UI → actual RT | D01 order <200ms normal |
| Cache hit working? | Check Redis TTL change confirms cache | P03 Cache Aside 30min → 2nd access TTL < original |
| Connection pool ok? | grep HikariCP active connections | active < max pool size |

##### 6. Scalability

| Check | Method | Example |
|------|------|------|
| Shard routing correct? | userId%4 → data in correct shard | ORDER shard = my_xhs_order_{userId%4} |
| Redis Cluster aligned? | Check key hash tag same slot | cart:{userId}:items/checked/sort |
| MQ consumer scalable? | consumer group supports multiple instances | inventory-order-transaction-consumer-group |

##### 7. Microservice

| Check | Method | Example |
|------|------|------|
| Feign call success? | grep target service log | cart→product GET /api/product/sku/batch |
| Feign fallback works? | Stop target service → check fallback value | inventory down→cart list "item info unavailable" |
| Sentinel rate limit? | Gateway yml rate-limit-qps config | cart-service qps=50 |
| Nacos health? | curl Nacos instance list ≥1 | my-xhs-{service} healthy=true |

##### 8. Concurrency

| Check | Method | Example |
|------|------|------|
| Distributed lock? | Check Redisson lock key + timeout < fixedRate | PreDeductTimeoutJob leaseTime=40s < 60s |
| Lua atomic? | Read Lua script → confirm multi-key same slot | claim_coupon.lua KEYS[1..2] same {templateId} |
| @Idempotent effective? | Same params twice within 5s → 1st 200, 2nd rejected | A01 like @Idempotent 5s |
| Thread pool ok? | grep executor pool size | searchExecutor pool-size=200 |

##### 9. Security

| Check | Method | Example |
|------|------|------|
| JWT auth? | Without token → 401 | curl without Authorization header |
| X-Admin-Call check? | Without/wrong token → 403 | admin endpoint no X-Admin-Call → 403 |
| X-Internal-Call check? | Check fail-open/fail-closed | INTERNAL_TOKEN empty → coupon internal endpoints fail-closed 403 |
| X-User-Id anti-forgery? | Gateway set() overwrites client value | Header X-User-Id:999 → JWT inject overrides |
| Sensitive data masked? | Phone/ID masked in response | receiverPhone shows 138****8000 |
| Password secure? | MySQL stores BCrypt hash | $2a$10$... format, not plaintext |
### 1.4 Prohibited

- Don't write business logic without reading source code
- Don't skip edge cases (idempotent/rollback/timeout/concurrent)
- Don't curl without drawing complete flow diagram
- Don't skip business logic analysis with "this is simple"
- **NO batch curl**: `curl A && curl B`, `for ... curl`, continuous testing without writing execution files — ALL FORBIDDEN
- **Business chains must declare before continuing**: D01→I02→D08→I03 auto-triggered chains OK, simple endpoints NEVER
- **NO empty results without data source verification**: API returns 0/[]/{} → MUST check MySQL/Redis to confirm REAL 0 vs query failure
- **NO testing dependency chains without prerequisites**: must create upstream data before testing downstream (Feed push needs followers, cancel order needs an order first)

> ⚠️ **Violated 20+ times this session**

### 1.5 Chain Dependency Order (Check Before Testing)

| Downstream Endpoint | Prerequisites | Consequence if Missing |
|------|------|------|
| D01 Create Order | I01 init inventory + B01 cart + U06 address | Order rejected |
| D05 Cancel Order | D01 order created (status=0) | Nothing to cancel |
| M01 Pay | D01 order created | Nothing to pay |
| M03 Refund | M01 payment completed | No payment record |
| C07 Feed Push | Author has followers | Only test empty push branch |
| A14 Common Follows | Both users follow same target | Empty intersection |
| A10 Follow | Target user exists | Follow fails |
| B01 Add to Cart | SKU created (P06) | Add fails |
| N04 Claim Coupon | Template created+active (N01+N02) | Claim fails |

## 2. Layer 2: Data Correctness

Eight-layer verification:
| Layer | Method |
|------|------|
| HTTP | curl -i → status code + X-Trace-Id + key fields |
| Redis | python3 → key name + value + TTL |
| MySQL | mysql -e → row insert/update/delete + field values |
| MQ | grep Consumer log → topic + confirmation keywords |
| ES | curl ES/_search → doc count |
| SkyWalking | traceId + UI http://21.130.247.89:8080 |
| Prometheus | curl actuator/prometheus → metric count |
| Kibana | ES myxhs-logs-* → traceId logs |

## 3. Layer 3: Production Quality (after L1+L2 pass)

### 3.1 Lenses 5-9

| Lens | Check |
|------|------|
| **5. Performance** | Response time, RateLimit, cache hit rate |
| **6. Scalability** | Shard routing correct, Cluster hash tag correct |
| **7. Microservice/SCA** | Feign fallback, Sentinel rules, service discovery |
| **8. Concurrency** | Distributed lock, Lua atomicity, thread safety |
| **9. Security** | JWT auth, X-Admin-Call check, X-Internal-Call fail-open/closed |

### 3.2 Background Guarantees

After each chain, check:
- XXL-Job registration + trigger logs (all 1min cron)
- Reconciliation endpoints (F03 counter, coupon, inventory)
- MQ retry/dead letter logs
- @Scheduled compensation job logs (Outbox/Timeout/Compensation)

## 4. Environment Configuration

- All XXL-Job: `cron=0 * * * * ?` (1min)
- All @Scheduled: `fixedRate ≤ 60000`
- Redis: Sentinel master=16379
- MySQL: remote root has SELECT/INSERT/UPDATE/DELETE only (no CREATE)
- Admin Token: `X-Admin-Call: my-xhs-admin-token-2026`
- ⚠️ INTERNAL_TOKEN: coupon/cart/inventory default empty → Feign 403 fail-closed

## 5. Document Standards

```
docs/test-2/
├── README.md                     ← Project index
├── HANDOFF-20260808.md           ← Handoff entry (top-level, new AI reads first)
├── methodology/
│   ├── TEST-METHODOLOGY.md       ← CN version
│   └── TEST-METHODOLOGY-EN.md    ← EN version
├── plans/
│   └── FULL-CHAIN-RETEST-PLAN.md ← Execution commands
└── execution/
    ├── README.md
    ├── pitfalls.md
    ├── _archive/ (old files)
    └── {service}/ (per-endpoint files)
```

### Per-Endpoint File Template

Required sections:
1. § Business Logic
2. § ASCII Flow
3. § Chain Verification
4. § Data Verification (L2)
5. § Production Check (L3)
6. § Observability (L4)
7. § curl command
8. § Pitfalls/Fixes

## 6. Known Traps

| # | Trap | Solution |
|:--:|------|------|
| 1 | Token 30min expiry | Re-login |
| 2 | Chinese params curl 400 | `--data-urlencode` |
| 3 | mvn -am pollutes common | Use -pl only |
| 4 | Redis keys with {} | Double-brace f-string |
| 5 | Order shard userId%4 | `my_xhs_order_$((id%4))` |
| 6 | ES Canal sync delay | Wait 5-10s |
| 7 | notification @Profile("dev") | `--spring.profiles.active=dev` |
| 8 | Gateway HMAC whitelist | grep hmac-white-list |
| 9 | INTERNAL_TOKEN empty | Business chain coverage |
| 10 | PayCallbackSimulator not working | Use payType=99 |
| 11 | A02/A07 DELETE+JSON body | curl -X DELETE -d |
| 12 | A10/A11 target in PATH | `/follow/{targetUserId}` |
| 13 | W01 JWT >= 256 bits | `-Djwt.secret=...32+ bytes` |
| 14 | analytics management.admin-token | `-Dmanagement.admin-token=...` |

## 6. Failure Diagnosis and Repair Protocol

When curl returns non-200, DON'T skip with "failed" — follow this procedure:

```
1. Read full response → confirm error code and message
2. Check service log → grep -i "error\|exception" /tmp/r_{service}.log
3. Locate root cause → read source (Controller/Service/config)
4. Fix → code/config/whitelist/service start
5. Rebuild → mvn package -pl my-xhs-{service} -DskipTests (no -am)
6. Restart → fuser -k {port}/tcp && nohup java ... &
7. Verify → wait for Nacos registration (≥1 instance) → re-curl
8. Full chain retest → re-run entire business chain from start
```

### Common Failures

| Error | Root Cause | Fix |
|------|------|------|
| 401 | Token expired/invalid | Re-login |
| 403 HMAC | Gateway whitelist missing | Add path → rebuild Gateway |
| 403 Admin | X-Admin-Call missing | Header: X-Admin-Call: my-xhs-admin-token-2026 |
| 404 | Service not in Nacos | Check process→Nacos→restart |
| 500 WeakKeyException | JWT key <256 bits | -Djwt.secret=...32+ bytes |
| Could not resolve placeholder | Missing JVM arg | e.g. -Dmanagement.admin-token=... |
| Unable to access jarfile | JAR not built | mvn package -pl {service} |

## 7. New Chain Reset Trap

**Every new chain's first file will be missing L3+L4**. This is a persistent cross-chain pattern — the neural system resets to default template at each new task start.

Defense:
- **Immediately grep after each chain's first file Write**: `grep -c "Production\|Prometheus" "$f"` → L3=0 or L4=0 → immediately cat >> append
- **Don't rely on "I'll remember"** — chains 2/3/4/5/6/7 all had this recurrence

## 8. Database/Port Cross-Reference

| Database | Port | Tables (examples) |
|------|:--:|------|
| my_xhs_user | 13306 | t_user, t_user_address |
| my_xhs_im | 13306 | t_chat_message, t_chat_user_relation |
| my_xhs_content | 13307 | t_note, t_comment, t_like, t_favorite, t_follow |
| my_xhs_coupon | 13307 | t_coupon_template, t_user_coupon, t_coupon_outbox |
| my_xhs_cart | 13307 | t_cart_item |
| my_xhs_product | 13307 | t_spu, t_sku |
| my_xhs_order_{0..3} | 13308 | t_order, t_order_item, t_order_event, t_local_message |
| my_xhs_payment | 13308 | t_payment, t_refund |
| my_xhs_inventory | 13309 | t_inventory, t_inventory_outbox |

> ⚠️ IM tables are at 13306, NOT 13307! Wrong database query → empty output misjudged as COUNT=0

## References

- Handoff: `../HANDOFF-20260808.md`
- Execution Plan: `../plans/FULL-CHAIN-RETEST-PLAN.md`
- Pitfalls: `../execution/pitfalls.md`

---

## 9. Pre-Test Engineering Checklist

Run before each test session. Stop if any check fails:

```bash
# 1. 16 services + Nacos registration
ps aux | grep "my-xhs-" | grep java | grep -v grep | wc -l
for s in user content analytics counter product cart coupon inventory order payment notification im home search gateway; do
  c=$(curl -s "http://21.130.247.89:18848/nacos/v1/ns/instance/list?...$s..." | python3 -c "...")
  [ "$c" -eq 0 ] && echo "❌ my-xhs-$s: 0 instances"
done

# 2. Gateway health
curl -s -o /dev/null -w "%{http_code}" http://localhost:19000/api/user/auth/captcha  # must be 200

# 3. Special JVM args per service:
# notification: --spring.profiles.active=dev
# analytics:   -Dmanagement.admin-token=my-xhs-admin-token-2026
# im:          -Djwt.secret=xhs-test-secret-key-2026-my-xhs-project-imag-service

# 4. Token valid
curl -s -o /dev/null -w "%{http_code}" http://localhost:19000/api/user/me \
  -H "Authorization: Bearer $(cat /tmp/test_token.txt)"  # 401 = expired, re-login

# 5. XXL-Job 7 tasks all 1min cron
curl -s -b /tmp/xxl_cookie "http://21.130.247.89:18080/xxl-job-admin/jobinfo/pageList?jobGroup=1"
```

## 10. Post-Chain Engineering Verification

After each business chain, run:

### 10.1 Observability Snapshot

```bash
for port in 19001 19002 19003 19004 19006 19008 19009 19010 19011 19012 19013 19014 19015 19016; do
  curl -s "http://localhost:$port/actuator/prometheus" 2>/dev/null | grep 'http_server_requests_seconds_count{.*uri=' | grep -v '/**' | head -3
done
```

### 10.2 Data Consistency Check

```bash
# F03 counter reconcile
curl -s -X POST http://localhost:19000/api/counter/reconcile -H "Authorization: Bearer $TOKEN" -H "X-Admin-Call: my-xhs-admin-token-2026"
```

### 10.3 MQ Backlog Check

```bash
# RocketMQ Dashboard consumer progress
curl -s "http://21.130.247.89:18081/consumer/groupList.query"
```

## 11. Code Fix Quality Gates

Before claiming a bug is fixed:

### 11.1 Fix Self-Check

```
[ ] Root cause located to source level (specific file + line)
[ ] Fix is standard solution (NOT workaround/manual INSERT/bypass)
[ ] Impact scope assessed (target service only? common module → rebuild all deps?)
[ ] Build passes: mvn package -pl {service} -DskipTests (NO -am)
[ ] Service restarts with Nacos registration ≥1
[ ] Full affected chain re-tested from start
[ ] Edge cases verified (rollback/idempotent/concurrent)
```

### 11.2 Build Safety Rules

```
- Single service: mvn package -pl {service} -DskipTests (NO -am)
- common module: mvn package -pl my-xhs-common -DskipTests → restart ALL dependent services
- Multiple services: build one at a time (no -am), restart one at a time
- ⚠️ -am rebuilds common → running services will gracefully shutdown immediately
```

### 11.3 Regression Test Triggers

| Modified | Must Re-test |
|------|------|
| user service | Chain 1 |
| product service | Chain 2 + Chain 3 (Feign) |
| cart service | Chain 3 + Chain 5 (cart data) |
| coupon service | Chain 4 + Chain 5 (claim/use) |
| inventory service | Chain 5 (preDeduct/confirm/release) |
| order service | Chain 5 full lifecycle |
| content service | Chain 6 (C07/A01/C01) |
| Gateway yml | All affected paths |
| common module | ALL 7 chains |

## References
