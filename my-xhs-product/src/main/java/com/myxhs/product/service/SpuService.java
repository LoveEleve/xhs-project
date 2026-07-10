package com.myxhs.product.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.myxhs.common.cache.RedisOperator;
import com.myxhs.common.constants.RedisKeyConstants;
import com.myxhs.common.exception.BizException;
import com.myxhs.common.id.IdGeneratorUtil;
import com.myxhs.common.response.PageResult;
import com.myxhs.common.response.ResultCode;
import com.myxhs.product.cache.RedisCacheData;
import com.myxhs.product.dto.request.SpuCreateRequest;
import com.myxhs.product.dto.request.SpuUpdateRequest;
import com.myxhs.product.dto.response.SkuVO;
import com.myxhs.product.dto.response.SpuDetailVO;
import com.myxhs.product.dto.response.SpuItemVO;
import com.myxhs.product.entity.Category;
import com.myxhs.product.entity.Sku;
import com.myxhs.product.entity.Spu;
import com.myxhs.product.enums.ProductStatus;
import com.myxhs.product.mapper.CategoryMapper;
import com.myxhs.product.mapper.SkuMapper;
import com.myxhs.product.mapper.SpuMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * SPU 服务（商品核心服务）
 * <p>
 * 【多级缓存架构】
 * L2: Redis 分布式缓存（30min 逻辑过期，防缓存击穿）
 * L3: MySQL 持久化存储
 * <p>
 * 【防护策略】
 * - 缓存穿透：三层防御（布隆过滤器前置拦截 + 缓存空值兜底 + ID 格式校验）
 * - 缓存击穿：逻辑过期（热点 Key 永不物理过期，发现逻辑过期异步刷新）
 * - 缓存雪崩：TTL 随机偏移（CacheHelper 已内置）
 * <p>
 * 【一致性策略】
 * 写操作：先更新 DB → 删 Redis
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpuService {

    private final SpuMapper spuMapper;
    private final SkuMapper skuMapper;
    private final CategoryMapper categoryMapper;
    private final RedisOperator redisOperator;
    private final RedissonClient redissonClient;
    private final IdGeneratorUtil idGeneratorUtil;

    /** 【修复m12】自定义有界线程池，替代 ForkJoinPool.commonPool()，避免阻塞公共线程池 */
    private static final java.util.concurrent.ExecutorService SPU_ASYNC_EXECUTOR =
            new java.util.concurrent.ThreadPoolExecutor(
                    2, 8, 60, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>(100),
                    r -> { Thread t = new Thread(r, "spu-async"); t.setDaemon(true); return t; },
                    new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy()
            );

    /**
     * JVM 退出时关闭异步线程池，防止队列中未完成任务丢失
     */
    @PreDestroy
    public void shutdownAsyncExecutor() {
        log.info("[SPU] 关闭异步线程池...");
        SPU_ASYNC_EXECUTOR.shutdown();
        try {
            if (!SPU_ASYNC_EXECUTOR.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                log.warn("[SPU] 线程池未能在10秒内终止，强制关闭");
                SPU_ASYNC_EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.warn("[SPU] 线程池关闭被中断，强制关闭");
            SPU_ASYNC_EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("[SPU] 异步线程池已关闭");
    }

    /** SPU 布隆过滤器（防穿透） */
    private RBloomFilter<Long> spuBloomFilter;

    /**
     * 布隆过滤器是否就绪
     * <p>
     * 首次部署异步加载期间为 false，此时跳过布隆过滤器判断（降级为直接查缓存/DB）。
     * 加载完成后设为 true。已有布隆过滤器直接复用时，启动即为 true。
     * 使用 AtomicBoolean 保证多线程可见性。
     * </p>
     */
    private final AtomicBoolean bloomFilterReady = new AtomicBoolean(false);

    /** 逻辑过期时间（分钟） */
    private static final long LOGIC_EXPIRE_MINUTES = 30;

    /** 缓存刷新分布式锁前缀 */
    private static final String CACHE_REFRESH_LOCK = "myxhs:product:lock:spu:";

    /**
     * 初始化布隆过滤器
     * <p>
     * 【生产级设计】
     * 布隆过滤器存储在 Redis 中，是持久化的。正确的做法是：
     * 1. tryInit 是幂等的 —— 如果 Redis 中已存在该布隆过滤器，直接复用，不会重置
     * 2. 只有首次部署（Redis 中不存在）时，才需要全量加载历史数据
     * 3. 全量加载走异步 + 分批 + Pipeline，不阻塞启动
     * 4. 后续新增 SPU 时实时 add，无需每次启动重建
     * <p>
     * 【为什么不能每次启动都全量加载？】
     * - 100 万 SPU 逐条 add → 100 万次 Redis 网络调用 → 启动卡 16 分钟
     * - K8s 健康检查超时 → Pod 被杀 → 永远启动不了（死循环）
     * - 多实例同时启动 → 重复写入 → 浪费资源
     * </p>
     */
    @PostConstruct
    public void initBloomFilter() {
        spuBloomFilter = redissonClient.getBloomFilter("myxhs:product:bloom:spu");

        // tryInit 是幂等的：Redis 中已存在则返回 false（不会重置），不存在则创建并返回 true
        boolean isNewFilter = spuBloomFilter.tryInit(1_000_000L, 0.01);

        if (isNewFilter) {
            // 首次部署：异步分批加载历史数据，不阻塞启动
            // bloomFilterReady 保持 false，加载期间跳过布隆过滤器（降级为直接查缓存/DB）
            log.info("[布隆过滤器] 检测到首次初始化, 异步加载历史 SPU ID...");
            asyncLoadBloomFilter();
        } else {
            // 已有布隆过滤器，直接标记就绪
            bloomFilterReady.set(true);
            log.info("[布隆过滤器] Redis 中已存在布隆过滤器(count={}), 直接复用, 跳过全量加载",
                    spuBloomFilter.count());
        }
    }

    /**
     * 异步分批加载历史 SPU ID 到布隆过滤器
     * <p>
     * 【生产级优化】
     * 1. 异步执行：不阻塞应用启动，K8s 健康检查不会超时
     * 2. 分批查询：每批 5000 条，避免一次性加载 100 万条撑爆 JVM 堆内存
     * 3. 分布式锁：多实例同时启动时，只有一个实例执行加载，避免重复写入
     * 4. 容错降级：加载失败不影响服务可用性（布隆过滤器为空 → 所有请求穿透到 DB → 功能正确，只是没有防穿透优化）
     * </p>
     */
    private void asyncLoadBloomFilter() {
        CompletableFuture.runAsync(() -> {
            String lockKey = "myxhs:product:bloom:spu:init-lock";
            RLock lock = redissonClient.getLock(lockKey);
            boolean locked = false;
            try {
                // 分布式锁：多实例只有一个执行加载
                locked = lock.tryLock(0, 300, TimeUnit.SECONDS);
                if (!locked) {
                    log.info("[布隆过滤器] 其他实例正在加载, 跳过");
                    return;
                }

                // 二次检查：拿到锁后再确认是否需要加载（可能其他实例已完成）
                if (spuBloomFilter.count() > 0) {
                    log.info("[布隆过滤器] 其他实例已完成加载(count={}), 跳过", spuBloomFilter.count());
                    return;
                }

                // 分批加载（每批 5000 条，游标分页避免深分页性能问题）
                long lastId = 0;
                int batchSize = 5000;
                int totalLoaded = 0;

                while (true) {
                    List<Spu> batch = spuMapper.selectList(
                            new LambdaQueryWrapper<Spu>()
                                    .select(Spu::getId)
                                    .gt(Spu::getId, lastId)
                                    .orderByAsc(Spu::getId)
                                    .last("LIMIT " + batchSize));

                    if (batch.isEmpty()) {
                        break;
                    }

                    for (Spu spu : batch) {
                        spuBloomFilter.add(spu.getId());
                    }

                    totalLoaded += batch.size();
                    lastId = batch.get(batch.size() - 1).getId();
                    log.info("[布隆过滤器] 已加载 {} 条, 最新ID={}", totalLoaded, lastId);

                    // 每批之间短暂休眠，降低对 DB 和 Redis 的压力
                    if (batch.size() == batchSize) {
                        Thread.sleep(100);
                    }
                }

                bloomFilterReady.set(true);
                log.info("[布隆过滤器] 异步加载完成, 共加载 {} 个 SPU ID, 防穿透保护已激活", totalLoaded);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[布隆过滤器] 异步加载被中断");
            } catch (Exception e) {
                // 加载失败不影响服务可用性，只是暂时没有防穿透优化
                log.error("[布隆过滤器] 异步加载失败(服务仍可用, 但缺少防穿透保护)", e);
            } finally {
                if (locked && lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }, SPU_ASYNC_EXECUTOR);
    }

    // ==================== 写操作 ====================

    /**
     * 创建 SPU
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createSpu(SpuCreateRequest request) {
        // 1. 校验分类是否存在
        Category category = categoryMapper.selectById(request.getCategoryId());
        if (category == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "分类不存在");
        }

        // 2. 构建实体
        Spu spu = new Spu();
        spu.setId(idGeneratorUtil.nextId());
        spu.setName(request.getName());
        spu.setCategoryId(request.getCategoryId());
        spu.setBrandId(request.getBrandId());
        spu.setDescription(request.getDescription());
        spu.setImages(request.getImages() != null ? JSON.toJSONString(request.getImages()) : null);
        spu.setStatus(ProductStatus.ON_SHELF.getCode());

        // 3. 入库
        spuMapper.insert(spu);

        // 4. 加入布隆过滤器
        spuBloomFilter.add(spu.getId());

        log.info("[商品] 创建 SPU 成功, spuId={}, name={}", spu.getId(), spu.getName());
        return spu.getId();
    }

    /**
     * 更新 SPU
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateSpu(Long spuId, SpuUpdateRequest request) {
        // 1. 查询原 SPU
        Spu spu = spuMapper.selectById(spuId);
        if (spu == null) {
            throw new BizException(ResultCode.PRODUCT_NOT_FOUND);
        }

        // 2. 更新字段（非空才更新）
        if (StringUtils.hasText(request.getName())) {
            spu.setName(request.getName());
        }
        if (request.getCategoryId() != null) {
            Category category = categoryMapper.selectById(request.getCategoryId());
            if (category == null) {
                throw new BizException(ResultCode.PARAM_INVALID, "分类不存在");
            }
            spu.setCategoryId(request.getCategoryId());
        }
        if (request.getBrandId() != null) {
            spu.setBrandId(request.getBrandId());
        }
        if (request.getDescription() != null) {
            spu.setDescription(request.getDescription());
        }
        if (request.getImages() != null) {
            spu.setImages(JSON.toJSONString(request.getImages()));
        }

        // 3. 更新 DB
        spuMapper.updateById(spu);

        // 4. 清除缓存（先删 Redis → MQ 广播删 Caffeine）
        evictSpuCache(spuId);

        log.info("[商品] 更新 SPU 成功, spuId={}", spuId);
    }

    /**
     * 上架/下架 SPU
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateSpuStatus(Long spuId, Integer status) {
        Spu spu = spuMapper.selectById(spuId);
        if (spu == null) {
            throw new BizException(ResultCode.PRODUCT_NOT_FOUND);
        }

        // 校验状态值合法性
        ProductStatus.of(status);

        spu.setStatus(status);
        spuMapper.updateById(spu);

        // 清除缓存
        evictSpuCache(spuId);

        log.info("[商品] SPU 状态变更, spuId={}, status={}", spuId, status);
    }

    // ==================== 读操作（多级缓存） ====================

    /** 空值缓存逻辑过期时间（分钟），比正常数据短 */
    private static final long NULL_CACHE_EXPIRE_MINUTES = 2;

    /**
     * 获取 SPU 详情（多级缓存 + 逻辑过期 + 统一空值防穿透）
     * <p>
     * 查询链路：布隆过滤器(前置) → Redis(L2, 逻辑过期) → MySQL(L3)
     * <p>
     * 【三层防穿透策略 — 大厂生产级方案】
     * <p>
     * 第一层：布隆过滤器（前置拦截）
     *   - 拦截"一定不存在"的 ID，连 Redis 都不用查
     *   - 适合商品场景：ID 只增不删（下架 ≠ 删除），误判率 1% 可接受
     *   - 不可用时自动降级跳过，不影响后续层
     * <p>
     * 第二层：缓存空值（核心防线）
     *   - DB 查不到 → 缓存 RedisCacheData(data=null, logicExpire=2min)
     *   - 统一用 RedisCacheData 包装，避免同一 Key 存两种类型的坑
     *   - 空值也走逻辑过期，与正常数据处理逻辑完全一致
     * <p>
     * 第三层：ID 格式校验（Controller 层 / 网关层）
     *   - 雪花 ID 有固定格式，非法格式直接拒绝，不进入缓存链路
     * <p>
     * 【为什么不单独用缓存空值？】
     * 攻击者用海量不同的不存在 ID 发请求 → 每个 ID 缓存一个空值 Key → Redis 内存被打爆。
     * 布隆过滤器在前面拦截，只有布隆过滤器误判（1%）的请求才会产生空值缓存，
     * 大幅减少 Redis 中的空值 Key 数量。
     * <p>
     * 【为什么不单独用布隆过滤器？】
     * 布隆过滤器有 1% 误判率，误判的请求仍会穿透到 DB。
     * 缓存空值兜底，确保同一个误判 ID 只穿透一次 DB。
     * <p>
     * 【防击穿策略】逻辑过期 + 分布式锁异步刷新
     * </p>
     */
    public SpuDetailVO getSpuDetail(Long spuId) {
        // 1. 布隆过滤器前置拦截（第一层防穿透）
        //    拦截"一定不存在"的 ID，连 Redis 都不用查，减少无效 Key 进入缓存
        if (bloomFilterReady.get() && !spuBloomFilter.contains(spuId)) {
            log.info("[多级缓存] 布隆过滤器拦截, spuId={} 不存在", spuId);
            return null;
        }

        // 2. L2: Redis 分布式缓存（统一用 RedisCacheData 包装，包括空值）
        String redisKey = RedisKeyConstants.PRODUCT_SPU + spuId;
        RedisCacheData<SpuDetailVO> cacheData = redisOperator.get(redisKey);

        if (cacheData != null) {
            if (!cacheData.isExpired()) {
                // 未逻辑过期
                if (cacheData.getData() == null) {
                    // 空值缓存命中（第二层防穿透核心）
                    log.info("[多级缓存] 命中空值缓存(防穿透), spuId={}", spuId);
                    return null;
                }
                log.debug("[多级缓存] L2 Redis 命中(未过期), spuId={}", spuId);
                return cacheData.getData();
            }

            // 已逻辑过期
            if (cacheData.getData() == null) {
                // 空值缓存过期 → 不异步刷新，直接穿透到 DB 重新查（可能数据已新增）
                log.info("[多级缓存] 空值缓存已过期, 重新查 DB, spuId={}", spuId);
                // 继续往下走到 L3
            } else {
                // 正常数据过期 → 返回旧值 + 异步刷新（只有一个线程刷新）
                log.info("[多级缓存] L2 Redis 逻辑过期, 异步刷新, spuId={}", spuId);
                asyncRefreshCache(spuId, redisKey);
                return cacheData.getData();
            }
        }

        // 3. L3: MySQL 兜底
        log.info("[多级缓存] L2 Redis 未命中, 查询 DB, spuId={}", spuId);
        SpuDetailVO detail = loadSpuDetailFromDb(spuId);
        if (detail != null) {
            // 回填 L2（逻辑过期）
            RedisCacheData<SpuDetailVO> newCacheData = RedisCacheData.of(detail, LOGIC_EXPIRE_MINUTES);
            redisOperator.set(redisKey, newCacheData);
        } else {
            // 【第二层防穿透】DB 也查不到 → 缓存空值（统一用 RedisCacheData 包装）
            // 逻辑过期 2 分钟（2 分钟后重新查 DB，数据可能已新增）
            // 物理 TTL 5 分钟（兜底清理，防止攻击者用大量不存在 ID 打爆 Redis 内存）
            RedisCacheData<SpuDetailVO> nullCacheData = RedisCacheData.of(null, NULL_CACHE_EXPIRE_MINUTES);
            redisOperator.set(redisKey, nullCacheData, 5, TimeUnit.MINUTES);
            log.info("[多级缓存] DB 未查到, 缓存空值(防穿透, 逻辑过期={}min, 物理TTL=5min), spuId={}",
                    NULL_CACHE_EXPIRE_MINUTES, spuId);
        }
        return detail;
    }

    /**
     * SPU 列表（分页，仅上架商品）
     */
    public PageResult<SpuItemVO> listSpus(int pageNum, int pageSize, Long categoryId) {
        LambdaQueryWrapper<Spu> wrapper = new LambdaQueryWrapper<Spu>()
                .eq(Spu::getStatus, ProductStatus.ON_SHELF.getCode())
                .eq(categoryId != null, Spu::getCategoryId, categoryId)
                .orderByDesc(Spu::getCreatedAt);

        Page<Spu> page = spuMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);

        List<SpuItemVO> records = page.getRecords().stream()
                .map(this::toSpuItemVO)
                .collect(Collectors.toList());

        return PageResult.of(pageNum, pageSize, page.getTotal(), records);
    }

    // ==================== 缓存管理 ====================

    /**
     * 清除 SPU 缓存（写操作后调用）
     * <p>
     * 删除 Redis 缓存
     * </p>
     */
    public void evictSpuCache(Long spuId) {
        // 删 Redis
        String redisKey = RedisKeyConstants.PRODUCT_SPU + spuId;
        redisOperator.delete(redisKey);
    }

    // ==================== 私有方法 ====================

    /**
     * 异步刷新缓存（逻辑过期后触发）
     * <p>
     * 使用分布式锁保证只有一个线程刷新，避免缓存击穿。
     * 获取锁失败的线程直接返回旧值（可用性优先）。
     * </p>
     */
    private void asyncRefreshCache(Long spuId, String redisKey) {
        String lockKey = CACHE_REFRESH_LOCK + spuId;
        RLock lock = redissonClient.getLock(lockKey);

        CompletableFuture.runAsync(() -> {
            boolean locked = false;
            try {
                // tryLock 非阻塞，获取不到立即返回
                locked = lock.tryLock(0, 10, TimeUnit.SECONDS);
                if (!locked) {
                    log.debug("[缓存刷新] 未获取到锁, 跳过刷新, spuId={}", spuId);
                    return;
                }

                SpuDetailVO detail = loadSpuDetailFromDb(spuId);
                if (detail != null) {
                    RedisCacheData<SpuDetailVO> newCacheData = RedisCacheData.of(detail, LOGIC_EXPIRE_MINUTES);
                    redisOperator.set(redisKey, newCacheData);
                    log.info("[缓存刷新] 异步刷新完成, spuId={}", spuId);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[缓存刷新] 被中断, spuId={}", spuId);
            } catch (Exception e) {
                log.error("[缓存刷新] 异步刷新失败, spuId={}", spuId, e);
            } finally {
                if (locked && lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }, SPU_ASYNC_EXECUTOR);
    }

    /**
     * 从 DB 加载 SPU 详情（含 SKU 列表）
     */
    private SpuDetailVO loadSpuDetailFromDb(Long spuId) {
        Spu spu = spuMapper.selectById(spuId);
        if (spu == null) {
            return null;
        }

        // 查询关联的 SKU 列表
        List<Sku> skuList = skuMapper.selectList(
                new LambdaQueryWrapper<Sku>()
                        .eq(Sku::getSpuId, spuId)
                        .eq(Sku::getStatus, ProductStatus.ON_SHELF.getCode())
                        .orderByAsc(Sku::getId));

        // 查询分类名称（直接查 DB）
        String categoryName = getCategoryName(spu.getCategoryId());

        return toSpuDetailVO(spu, skuList, categoryName);
    }

    /**
     * 获取分类名称（直接查询 DB）
     */
    private String getCategoryName(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        Category category = categoryMapper.selectById(categoryId);
        return category != null ? category.getName() : null;
    }

    // ==================== 对象转换 ====================

    private SpuDetailVO toSpuDetailVO(Spu spu, List<Sku> skuList, String categoryName) {
        SpuDetailVO vo = new SpuDetailVO();
        vo.setId(spu.getId());
        vo.setName(spu.getName());
        vo.setCategoryId(spu.getCategoryId());
        vo.setCategoryName(categoryName);
        vo.setBrandId(spu.getBrandId());
        vo.setDescription(spu.getDescription());
        vo.setImages(parseJsonList(spu.getImages()));
        vo.setStatus(spu.getStatus());
        vo.setCreatedAt(spu.getCreatedAt());
        vo.setUpdatedAt(spu.getUpdatedAt());

        List<SkuVO> skuVOList = skuList != null ? skuList.stream()
                .map(this::toSkuVO)
                .collect(Collectors.toList()) : Collections.emptyList();
        vo.setSkuList(skuVOList);

        return vo;
    }

    private SpuItemVO toSpuItemVO(Spu spu) {
        SpuItemVO vo = new SpuItemVO();
        vo.setId(spu.getId());
        vo.setName(spu.getName());
        vo.setCategoryId(spu.getCategoryId());
        vo.setImages(parseJsonList(spu.getImages()));
        vo.setStatus(spu.getStatus());
        return vo;
    }

    private SkuVO toSkuVO(Sku sku) {
        SkuVO vo = new SkuVO();
        vo.setId(sku.getId());
        vo.setSpuId(sku.getSpuId());
        vo.setName(sku.getName());
        vo.setPrice(sku.getPrice());
        vo.setOriginalPrice(sku.getOriginalPrice());
        vo.setStock(sku.getStock());
        vo.setSpecs(sku.getSpecs());
        vo.setStatus(sku.getStatus());
        return vo;
    }

    @SuppressWarnings("unchecked")
    private List<String> parseJsonList(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyList();
        }
        try {
            return JSON.parseArray(json, String.class);
        } catch (Exception e) {
            log.warn("[JSON] 解析图片列表失败: {}", json);
            return Collections.emptyList();
        }
    }
}
