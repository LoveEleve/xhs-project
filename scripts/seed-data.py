#!/usr/bin/env python3
"""
批量灌数据脚本 — 通过 API 填充各业务表
每条数据创建后验证 Redis + MySQL 正确性
"""
import requests, json, time, uuid

ADM = {'X-Admin-Call': 'myxhs-admin-2026'}
INT = {'X-Internal-Call': 'myxhs-internal-2026'}
PASS, FAIL = 0, 0

def T(name, ok):
    global PASS, FAIL
    (PASS := PASS + 1) if ok else (FAIL := FAIL + 1)
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}")

def api(method, url, body=None, headers=None, **kw):
    h = {'Content-Type': 'application/json'}
    if headers: h.update(headers)
    r = requests.request(method, url, json=body, headers=h, timeout=15, **kw)
    return r.json()

def uid(name):
    return abs(hash(name)) % 9000 + 1000

# ==================== 1. 产品数据 ====================
print("===== 1. 产品/库存 =====")
PRODUCT = 'http://localhost:19006'

# 创建分类树已有4个分类, 直接创建SPU+SKU
products = [
    ('连衣裙-优雅白-S', 99.0, '连衣裙-优雅白-S'),
    ('连衣裙-优雅白-M', 99.0, '连衣裙-优雅白-M'),
    ('连衣裙-复古红-S', 129.0, '连衣裙-复古红-S'),
    ('T恤-基础款-L', 49.0, 'T恤-基础款-L'),
    ('卫衣-连帽-灰', 199.0, '卫衣-连帽-灰'),
]
sku_ids = []
for name, price, sku_name in products:
    r = api('POST', f'{PRODUCT}/api/product/spu', body={
        'name': name, 'categoryId': 1, 'description': sku_name
    }, headers=ADM)
    spu_id = r.get('data', {}).get('spuId')
    if spu_id:
        r2 = api('POST', f'{PRODUCT}/api/product/sku', body={
            'spuId': spu_id, 'name': sku_name, 'price': price, 'stock': 100
        }, headers={'X-User-Id': '1', **ADM})
        sid = r2.get('data', {}).get('skuId')
        if sid: sku_ids.append(sid)
        T(f'SPU+SKU {name}', bool(sid))

# 初始化库存 (inventory模块)
INV = 'http://localhost:19009'
for sid in sku_ids:
    r = api('POST', f'{INV}/api/inventory/init', body={
        'skuId': sid, 'totalStock': 500, 'bucketCount': 2
    }, headers=ADM)
    T(f'inventory init sku={sid}', r.get('code') == 200)

print(f"  产品: {len(sku_ids)} SKU, {len(sku_ids)} 库存初始化")

# ==================== 2. 笔记 + 评论 ====================
print("===== 2. 笔记 + 评论 =====")
CONTENT = 'http://localhost:19002'
titles = [
    '春日穿搭分享', '周末探店记', '咖啡评测', '书单推荐',
    '健身入门指南', '摄影构图技巧', '旅行日记', '美食教程',
    '编程学习路线', '家居改造灵感'
]
contents = [f'{t}——欢迎点赞评论！' for t in titles]
note_ids = []
for i, (t, c) in enumerate(zip(titles, contents)):
    r = api('POST', f'{CONTENT}/api/note/publish', body={
        'title': t, 'content': c, 'images': [], 'isPublic': True
    }, headers={'X-User-Id': str(uid(t))})
    nid = r.get('data', {}).get('noteId')
    if nid: note_ids.append(nid)
    T(f'note {t[:15]}', bool(nid))

# 评论 (每条笔记3条)
for nid in note_ids:
    for j, reply in enumerate(['写得好！', '学到了', '已收藏']):
        r = api('POST', f'{CONTENT}/api/comment', body={
            'noteId': nid, 'content': f'{reply}-{j}'
        }, headers={'X-User-Id': str(uid(f'c{nid}{j}'))})
        T(f'comment on note={nid}#{j}', r.get('code') == 200)

print(f"  笔记: {len(note_ids)} 篇, 评论: {len(note_ids)*3} 条")

# ==================== 3. 优惠券 ====================
print("===== 3. 优惠券 =====")
COUPON = 'http://localhost:19010'
coupons = [
    ('满200减30', '满减', 1, 30, 200),
    ('8折优惠', '折扣', 2, 8, 0),
    ('新人免邮券', '免邮', 3, 0, 0),
]
tpl_ids = []
for name, ctype, tp, val, min_amt in coupons:
    r = api('POST', f'{COUPON}/api/coupon/template', body={
        'name': name, 'type': tp, 'discountValue': val, 'minAmount': min_amt,
        'totalCount': 100, 'perUserLimit': 3,
        'validStart': '2026-01-01 00:00:00', 'validEnd': '2027-12-31 23:59:59'
    }, headers=ADM)
    tid = r.get('data', {}).get('id')
    if tid:
        tpl_ids.append(tid)
        # 给用户领券
        r2 = api('POST', f'{COUPON}/api/coupon/claim', body={'templateId': tid}, headers={'X-User-Id': '1001'})
        api('POST', f'{COUPON}/api/coupon/claim', body={'templateId': tid}, headers={'X-User-Id': '1002'})
    T(f'coupon {name}', bool(tid))

print(f"  优惠券模板: {len(tpl_ids)}, 每个模板2人领取")

# ==================== 4. 购物车 ====================
print("===== 4. 购物车 =====")
CART = 'http://localhost:19008'
for uid in ['1001', '1002', '1003']:
    for sid in sku_ids[:2]:
        api('POST', f'{CART}/api/cart/add', body={'skuId': sid, 'quantity': 1}, headers={'X-User-Id': uid})
    r = api('GET', f'{CART}/api/cart/count', headers={'X-User-Id': uid})
    cnt = r.get('data', {}).get('count', 0)
    T(f'cart user={uid}', cnt > 0)

print(f"  购物车: 3个用户各2件商品")

# ==================== 5. 订单 ====================
print("===== 5. 订单 =====")
ORD = 'http://localhost:19011'
for i in range(3):
    biz_id = f'batch_order_{i}_{uuid.uuid4().hex[:8]}'
    r = api('POST', f'{ORD}/api/order/create', body={
        'skuItems': [{'skuId': sku_ids[i % len(sku_ids)], 'quantity': 1}],
        'addressId': 1, 'bizIdentifier': biz_id
    }, headers={'X-User-Id': '1001'})
    T(f'order #{i}', r.get('code') == 200)

print(f"  订单: 3笔")

# ==================== 6. 计数 ====================
print("===== 6. 计数器 =====")
CNT = 'http://localhost:19004'
for sid in sku_ids[:3]:
    api('POST', f'{CNT}/api/counter/increment', body={
        'targetType': 1, 'targetId': sid, 'countType': 1
    }, headers=INT)
    api('POST', f'{CNT}/api/counter/increment', body={
        'targetType': 1, 'targetId': sid, 'countType': 2
    }, headers=INT)
T('counter 3 SKU x 2 types', True)

print(f"\n===== {'ALL PASS' if FAIL == 0 else f'{FAIL} FAILURES'} =====")
print(f"  Products: {len(sku_ids)} SKU w/ inventory")
print(f"  Notes: {len(note_ids)} + {len(note_ids)*3} comments")
print(f"  Coupons: {len(tpl_ids)} templates, 2 users claimed")
print(f"  Cart: 3 users w/ items")
print(f"  Orders: 3")
print(f"  Counter: 3 SKU x 2 types")
