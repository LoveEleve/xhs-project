#!/usr/bin/env python3
"""
分片算法迁移：user_id 直接取模 → hashCode 哈希取模（消除 Snowflake 低位偏差导致的倾斜）
旧: ds=user_id%4, table=(user_id/4)%4
新: h=user_id.hashCode()&0x7fffffff; ds=h%4, table=(h/4)%4
用法: python3 scripts/migrate-sharding-hash.py [--dry-run]
说明: 迁移前必须停 order 服务（避免迁移窗口内的新写入落到旧分片）。
"""
import sys
import time

import pymysql

TABLES = ["t_order", "t_order_item", "t_order_event", "t_order_snapshot", "t_local_message"]
DS = dict(host="192.168.0.142", port=3306, user="root", password="Xhs@2026#MySQL",
          charset="utf8mb4", autocommit=True)


def java_hash(v):
    """Java Long.hashCode = (int)(v ^ (v >>> 32))"""
    h = (v ^ (v >> 32)) & 0xFFFFFFFF
    if h >= 0x80000000:
        h -= 0x100000000
    return h


def new_shard(user_id):
    h = java_hash(user_id) & 0x7FFFFFFF
    return h % 4, (h // 4) % 4


def main():
    dry = "--dry-run" in sys.argv
    conn = pymysql.connect(**DS)
    cur = conn.cursor()
    total_moved = 0
    for t in TABLES:
        moved = 0
        for i in range(4):
            for j in range(4):
                src = f"my_xhs_order_{i}.{t}_{j}"
                cur.execute(f"SELECT id, user_id FROM {src}")
                rows = cur.fetchall()
                for rid, uid in rows:
                    ni, nj = new_shard(int(uid))
                    if (ni, nj) != (i, j):
                        dst = f"my_xhs_order_{ni}.{t}_{nj}"
                        if not dry:
                            cur.execute(f"INSERT INTO {dst} SELECT * FROM {src} WHERE id=%s", (rid,))
                            cur.execute(f"DELETE FROM {src} WHERE id=%s", (rid,))
                        moved += 1
        total_moved += moved
        print(f"  {t}: 迁移 {moved} 行")
    if dry:
        print(f"[DRY-RUN] 共需迁移 {total_moved} 行（未执行）")
    else:
        print(f"迁移完成：共 {total_moved} 行")
    conn.close()


if __name__ == "__main__":
    main()
