-- ============================================
-- my-xhs curl 测试前置数据初始化脚本
-- 生成时间: 2026-07-10
-- 数据库主机: 192.168.0.142
-- 密码: Xhs@2026#MySQL
-- 
-- 执行方式:
--   mysql -h 192.168.0.142 -u root -p'Xhs@2026#MySQL' < sql/test-data-init.sql
-- 
-- 或分实例执行:
--   mysql -h 192.168.0.142 -P 13306 -u root -p'Xhs@2026#MySQL' < sql/test-data-init.sql
-- ============================================

-- ============================================
-- Part 1: 用户数据 (端口 13306, 库 my_xhs_user)
-- ============================================
USE my_xhs_user;

-- 插入测试用户 (密码: Test@123456, BCrypt 加密)
-- 用户1: testuser / Test@123456
INSERT INTO t_user (id, username, password, nickname, avatar, gender, birthday, phone, email, signature, status, deleted, created_at, updated_at) VALUES
(10001, 'testuser',  '$2b$10$q5lXltJ4n4ElNpUP66tm0OllDK5yICNyzUXOXs1NC5ozclR7f3WbG', '测试用户A', 'https://example.com/avatar1.jpg', 1, '1998-06-15', '13800138001', 'testuser@example.com', '这是一个测试账号', 1, 0, NOW(), NOW()),
(10002, 'testuser2', '$2b$10$q5lXltJ4n4ElNpUP66tm0OllDK5yICNyzUXOXs1NC5ozclR7f3WbG', '测试用户B', 'https://example.com/avatar2.jpg', 2, '2000-01-01', '13800138002', 'testuser2@example.com', '第二个测试账号', 1, 0, NOW(), NOW()),
(10003, 'testuser3', '$2b$10$5XECOjbNc38JHWZnuLBlSuVC9wOUzwYQswlaTZKXvgiDewkXh0j9C', '测试用户C', 'https://example.com/avatar3.jpg', 1, NULL, NULL, NULL, NULL, 1, 0, NOW(), NOW());

-- 插入测试地址
INSERT INTO t_user_address (id, user_id, receiver_name, receiver_phone, province, city, district, detail_address, is_default, deleted, created_at, updated_at) VALUES
(1, 10001, '张三', '13800138001', '广东省', '深圳市', '南山区', '科技园路1号创新大厦1201', 1, 0, NOW(), NOW()),
(2, 10001, '李四', '13800138002', '北京市', '北京市', '朝阳区', '望京SOHO T1-2001', 0, 0, NOW(), NOW()),
(3, 10002, '王五', '13800138003', '上海市', '上海市', '浦东新区', '张江高科技园区500号', 0, 0, NOW(), NOW()),
(4, 10003, '赵六', '13800138004', '浙江省', '杭州市', '西湖区', '文三路100号', 1, 0, NOW(), NOW());


-- ============================================
-- Part 2: 商品数据 (端口 13307, 库 my_xhs_product)
-- ============================================
USE my_xhs_product;

-- 插入三级商品分类
INSERT INTO t_category (id, name, parent_id, level, sort, icon, status, deleted, created_at, updated_at) VALUES
(1, '服饰',       0, 1, 1, 'clothes',   1, 0, NOW(), NOW()),
(2, '电子产品',   0, 1, 2, 'electronic', 1, 0, NOW(), NOW()),
(3, '家居生活',   0, 1, 3, 'home',       1, 0, NOW(), NOW()),
(4, '美妆护肤',   0, 1, 4, 'beauty',     1, 0, NOW(), NOW()),
-- 二级分类
(11, '女装',      1, 2, 1, '', 1, 0, NOW(), NOW()),
(12, '男装',      1, 2, 2, '', 1, 0, NOW(), NOW()),
(21, '手机',      2, 2, 1, '', 1, 0, NOW(), NOW()),
(22, '电脑',      2, 2, 2, '', 1, 0, NOW(), NOW()),
(31, '家具',      3, 2, 1, '', 1, 0, NOW(), NOW()),
(41, '面部护肤',  4, 2, 1, '', 1, 0, NOW(), NOW()),
-- 三级分类
(111, '连衣裙',   11, 3, 1, '', 1, 0, NOW(), NOW()),
(112, 'T恤',      11, 3, 2, '', 1, 0, NOW(), NOW()),
(121, '衬衫',     12, 3, 1, '', 1, 0, NOW(), NOW()),
(211, '智能手机', 21, 3, 1, '', 1, 0, NOW(), NOW()),
(311, '沙发',     31, 3, 1, '', 1, 0, NOW(), NOW()),
(411, '面霜',     41, 3, 1, '', 1, 0, NOW(), NOW());

-- 插入 SPU 商品
INSERT INTO t_spu (id, name, category_id, brand_id, description, images, status, deleted, created_at, updated_at) VALUES
(1, '2026夏季新款连衣裙',     111, 1, '轻盈飘逸的夏季连衣裙，多种颜色可选',               '["https://example.com/spu1_1.jpg","https://example.com/spu1_2.jpg"]', 1, 0, NOW(), NOW()),
(2, '简约纯棉T恤',            112, 1, '100%纯棉面料，亲肤透气，日常百搭',                   '["https://example.com/spu2_1.jpg"]', 1, 0, NOW(), NOW()),
(3, '商务休闲衬衫',           121, 2, '免烫抗皱面料，商务休闲两不误',                         '["https://example.com/spu3_1.jpg"]', 1, 0, NOW(), NOW()),
(4, '旗舰智能手机 X1',        211, 3, '高性能旗舰手机，120Hz屏幕，5000mAh大电池',           '["https://example.com/spu4_1.jpg"]', 1, 0, NOW(), NOW()),
(5, '北欧简约布艺沙发',       311, 4, '三人位布艺沙发，可拆洗，高密度海绵填充',              '["https://example.com/spu5_1.jpg"]', 1, 0, NOW(), NOW()),
(6, '玻尿酸保湿面霜',         411, 5, '三重玻尿酸深层补水，锁水保湿一整天',                   '["https://example.com/spu6_1.jpg"]', 1, 0, NOW(), NOW());

-- 插入 SKU 商品（每个 SPU 1-2 个 SKU）
INSERT INTO t_sku (id, spu_id, name, price, original_price, stock, specs, status, deleted, created_at, updated_at) VALUES
-- SPU 1: 连衣裙
(1,  1, '连衣裙-红色-S',  199.00, 399.00, 100, '{"颜色":"红色","尺码":"S"}',   1, 0, NOW(), NOW()),
(2,  1, '连衣裙-红色-M',  199.00, 399.00, 150, '{"颜色":"红色","尺码":"M"}',   1, 0, NOW(), NOW()),
(3,  1, '连衣裙-蓝色-M',  199.00, 399.00, 120, '{"颜色":"蓝色","尺码":"M"}',   1, 0, NOW(), NOW()),
-- SPU 2: T恤
(4,  2, 'T恤-白色-M',      79.00, 129.00, 200, '{"颜色":"白色","尺码":"M"}',   1, 0, NOW(), NOW()),
(5,  2, 'T恤-黑色-L',      79.00, 129.00, 180, '{"颜色":"黑色","尺码":"L"}',   1, 0, NOW(), NOW()),
-- SPU 3: 衬衫
(6,  3, '衬衫-蓝色-42',   299.00, 499.00, 80,  '{"颜色":"蓝色","尺码":"42"}',  1, 0, NOW(), NOW()),
-- SPU 4: 手机
(7,  4, 'X1-8+128GB-黑色', 2999.00, 3499.00, 300, '{"颜色":"黑色","存储":"8+128GB"}', 1, 0, NOW(), NOW()),
(8,  4, 'X1-12+256GB-白色', 3499.00, 3999.00, 200, '{"颜色":"白色","存储":"12+256GB"}', 1, 0, NOW(), NOW()),
-- SPU 5: 沙发
(9,  5, '沙发-三人位-灰色', 2999.00, 4599.00, 30, '{"颜色":"灰色","规格":"三人位"}', 1, 0, NOW(), NOW()),
-- SPU 6: 面霜
(10, 6, '面霜-50g',         159.00, 259.00, 500, '{"规格":"50g"}', 1, 0, NOW(), NOW());


-- ============================================
-- Part 3: 库存数据 (端口 13309, 库 my_xhs_inventory)
-- ============================================
USE my_xhs_inventory;

INSERT INTO t_inventory (id, sku_id, available_stock, locked_stock, freezing_stock, deleted, created_at, updated_at) VALUES
(1,  1,  100,  0, 0, 0, NOW(), NOW()),
(2,  2,  150,  0, 0, 0, NOW(), NOW()),
(3,  3,  120,  0, 0, 0, NOW(), NOW()),
(4,  4,  200,  0, 0, 0, NOW(), NOW()),
(5,  5,  180,  0, 0, 0, NOW(), NOW()),
(6,  6,  80,   0, 0, 0, NOW(), NOW()),
(7,  7,  300,  0, 0, 0, NOW(), NOW()),
(8,  8,  200,  0, 0, 0, NOW(), NOW()),
(9,  9,  30,   0, 0, 0, NOW(), NOW()),
(10, 10, 500,  0, 0, 0, NOW(), NOW());


-- ============================================
-- Part 4: 推送模板 (端口 13306, 库 my_xhs_notification)
-- ============================================
USE my_xhs_notification;

INSERT INTO t_push_template (id, type, title_template, content_template, aggregate_title_template, status, created_at, updated_at) VALUES
(1, 'like',     '点赞通知',   '{sender} 赞了你的笔记',            NULL,                        1, NOW(), NOW()),
(2, 'comment',  '评论通知',   '{sender} 评论了你的笔记',          NULL,                        1, NOW(), NOW()),
(3, 'follow',   '关注通知',   '{sender} 关注了你',                NULL,                        1, NOW(), NOW()),
(4, 'system',   '系统通知',   '{content}',                        NULL,                        1, NOW(), NOW()),
(5, 'order',    '订单通知',   '订单 {orderNo} 状态更新为 {status}', NULL,                       1, NOW(), NOW());


-- ============================================
-- 验证插入结果
-- ============================================
SELECT '=== 用户表 ===' AS info;
SELECT id, username, nickname, status FROM my_xhs_user.t_user;

SELECT '=== 地址表 ===' AS info;
SELECT id, user_id, receiver_name, receiver_phone, province, city, district, detail_address FROM my_xhs_user.t_user_address;

SELECT '=== 分类表 ===' AS info;
SELECT id, name, parent_id, level FROM my_xhs_product.t_category ORDER BY level, id;

SELECT '=== 商品SPU ===' AS info;
SELECT id, name, category_id, status FROM my_xhs_product.t_spu;

SELECT '=== 商品SKU ===' AS info;
SELECT id, spu_id, name, price, stock FROM my_xhs_product.t_sku;

SELECT '=== 库存 ===' AS info;
SELECT id, sku_id, available_stock, locked_stock FROM my_xhs_inventory.t_inventory;

SELECT '=== 推送模板 ===' AS info;
SELECT id, type, title_template FROM my_xhs_notification.t_push_template;
