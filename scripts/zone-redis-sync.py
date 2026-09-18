#!/usr/bin/env python3
"""
Zone Redis 双向同步 Worker（双主仿真，D3-8）
- 双侧 keyspace 通知驱动 + 周期全量对账（SCAN 差异）
- 原子复制：DUMP/RESTORE REPLACE（保留类型与 TTL）
- 冲突解决：LWW（每 key 影子时间戳 __sync:ts:<key>；同 ts 时 zone-a 优先）
- 防回环：值相等即跳过（RESTORE 触发的对侧事件因值相同被忽略）
"""
import logging
import threading
import time
import sys
import redis

A_SIDE, B_SIDE = "zone-a", "zone-b"
A = dict(host="127.0.0.1", port=6379, password="Xhs@2026#Redis", socket_timeout=5)
B = dict(host="127.0.0.1", port=6381, password="Xhs@2026#Redis", socket_timeout=5)
SYNC_PREFIX = "__sync:"
TS_PREFIX = "__sync:ts:"
RECONCILE_INTERVAL = 30
WATCHED = {
    "set", "setex", "psetex", "mset", "hset", "hmset", "hdel", "hincrby", "hincrbyfloat",
    "incr", "incrby", "decr", "decrby", "lpush", "rpush", "lpop", "rpop", "lset", "ltrim",
    "sadd", "srem", "spop", "zadd", "zrem", "zincrby", "zremrangebyscore", "del", "unlink",
    "expire", "pexpire", "persist", "getset", "setnx", "getdel", "smove", "copy", "expired",
    "rename_from", "rename_to", "setrange", "append", "incrbyfloat", "hsetnx",
}

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s", stream=sys.stdout)
log = logging.getLogger("zone-redis-sync")


class SyncWorker:
    def __init__(self):
        self.a = redis.Redis(decode_responses=False, **A)
        self.b = redis.Redis(decode_responses=False, **B)
        self.conns = {A_SIDE: self.a, B_SIDE: self.b}
        self.lock = threading.Lock()
        self.stopping = False

    # ---------- 基础工具 ----------
    @staticmethod
    def _txt(v):
        return v.decode("utf-8", "replace") if isinstance(v, bytes) else str(v)

    def _is_internal(self, key):
        return key.startswith(SYNC_PREFIX.encode())

    def _dump(self, side, key):
        try:
            return self.conns[side].dump(key)
        except redis.RedisError as e:
            log.warning("[%s] DUMP %s 失败: %s", side, self._txt(key), e)
            return None

    def _ts(self, side, key):
        try:
            v = self.conns[side].get(TS_PREFIX.encode() + key)
            return int(v) if v else 0
        except redis.RedisError:
            return 0

    def _set_ts(self, side, key, ts):
        try:
            self.conns[side].set(TS_PREFIX.encode() + key, ts, ex=7 * 86400)
        except redis.RedisError as e:
            log.warning("[%s] SET ts %s 失败: %s", side, self._txt(key), e)

    def _restore(self, side, key, payload_ttl):
        payload, ttl = payload_ttl
        conn = self.conns[side]
        if payload is None:
            conn.delete(key)
            return True
        ttl = ttl if ttl and ttl > 0 else 0
        conn.restore(key, ttl, payload, replace=True)
        return True

    # ---------- 同步核心 ----------
    def sync_key(self, src_side, key, reason="event"):
        """把 src_side 的 key 状态同步到对侧（LWW）"""
        if self._is_internal(key):
            return
        dst_side = B_SIDE if src_side == A_SIDE else A_SIDE
        with self.lock:
            try:
                d_src = self._dump(src_side, key)
                d_dst = self._dump(dst_side, key)
            except redis.RedisError:
                return
            if d_src == d_dst:
                return  # 值一致：无需同步（同时防回环）
            ttl = self.conns[src_side].pttl(key)
            ttl = ttl if ttl and ttl > 0 else 0
            now = int(time.time() * 1000)
            ts_dst = self._ts(dst_side, key)
            if reason.startswith("event:"):
                # 事件驱动：源侧刚发生写入，时间戳记为当前时间参与 LWW
                ts_src = now
                self._set_ts(src_side, key, now)
            else:
                # 周期对账：按存量时间戳判断
                ts_src = self._ts(src_side, key)
                if ts_src == 0 and ts_dst == 0:
                    # 双 0（无时间戳）：存在优先（避免误删单侧数据）；都存在时 zone-a 优先（确定性）
                    if d_dst is None and d_src is not None:
                        ts_src, ts_dst = 1, 0
                    elif d_src is None and d_dst is not None:
                        ts_src, ts_dst = 0, 1
                    else:
                        ts_src = 1 if src_side == A_SIDE else 0
                        ts_dst = 0 if src_side == A_SIDE else 1
            winner_is_src = ts_src >= ts_dst
            if winner_is_src:
                try:
                    self._restore(dst_side, key, (d_src, ttl))
                except redis.RedisError as e:
                    log.warning("[%s->%s] RESTORE %s 失败: %s", src_side, dst_side, self._txt(key), e)
                    return
                self._set_ts(src_side, key, now)
                self._set_ts(dst_side, key, now)
                log.info("[%s] %s: %s -> %s (ts=%d)", reason, self._txt(key), src_side, dst_side, now)
            else:
                # 对侧更新，反向同步
                d_win = self._dump(dst_side, key)
                ttl_win = self.conns[dst_side].pttl(key)
                ttl_win = ttl_win if ttl_win and ttl_win > 0 else 0
                try:
                    self._restore(src_side, key, (d_win, ttl_win))
                except redis.RedisError as e:
                    log.warning("[%s->%s] RESTORE %s 失败: %s", dst_side, src_side, self._txt(key), e)
                    return
                self._set_ts(src_side, key, now)
                self._set_ts(dst_side, key, now)
                log.info("[%s] %s: %s -> %s (LWW 对侧较新, ts=%d)", reason, self._txt(key), dst_side, src_side, now)

    # ---------- 事件订阅 ----------
    def _listen(self, side):
        """事件监听（带断线重连：Redis 重启后自动恢复订阅）"""
        while not self.stopping:
            try:
                conn = self.conns[side]
                pubsub = conn.pubsub()
                pubsub.psubscribe("__keyevent@0__:*")
                log.info("[%s] 订阅 keyspace 通知", side)
                for msg in pubsub.listen():
                    if self.stopping:
                        break
                    if msg.get("type") != "pmessage":
                        continue
                    event = self._txt(msg["channel"]).rsplit(":", 1)[-1]
                    if event not in WATCHED:
                        continue
                    key = msg["data"]
                    if isinstance(key, bytes) and key.startswith(SYNC_PREFIX.encode()):
                        continue
                    try:
                        self.sync_key(side, key, reason="event:" + event)
                    except Exception as e:  # noqa
                        log.warning("[%s] 处理事件失败 %s: %s", side, self._txt(key), e)
            except Exception as e:  # noqa
                log.warning("[%s] 订阅中断，3s 后重连: %s", side, e)
                time.sleep(3)

    # ---------- 周期对账 ----------
    def _reconcile_once(self):
        try:
            keys_a = set(self.a.scan_iter(count=500))
            keys_b = set(self.b.scan_iter(count=500))
        except redis.RedisError as e:
            log.warning("对账 SCAN 失败: %s", e)
            return
        keys = {k for k in (keys_a | keys_b) if not self._is_internal(k)}
        diff = 0
        for k in keys:
            d_a, d_b = self._dump(A_SIDE, k), self._dump(B_SIDE, k)
            if d_a != d_b:
                diff += 1
                self.sync_key(A_SIDE, k, reason="reconcile")
        if diff:
            log.info("对账完成: 差异 key=%d / 总 key=%d", diff, len(keys))

    def _reconcile_loop(self):
        while not self.stopping:
            time.sleep(RECONCILE_INTERVAL)
            try:
                self._reconcile_once()
            except Exception as e:  # noqa
                log.warning("对账异常: %s", e)

    def run(self):
        threads = [threading.Thread(target=self._listen, args=(s,), daemon=True) for s in (A_SIDE, B_SIDE)]
        for t in threads:
            t.start()
        threading.Thread(target=self._reconcile_loop, daemon=True).start()
        log.info("Zone Redis 同步 Worker 启动: %s(6379) <-> %s(6381)", A_SIDE, B_SIDE)
        while True:
            time.sleep(3600)


if __name__ == "__main__":
    SyncWorker().run()
