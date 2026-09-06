package com.myxhs.product.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
 * 【两级缓存架构】
 * L2: Redis 分布式缓存（30min 逻辑过期 + 2h 物理 TTL 兜底，防缓存击穿）
 * L3: MySQL 持久化存储
 * <p>
 * 【防护策略】
 * - 缓存穿透：两层防御（布隆过滤器前置拦截 + 缓存空值兜底）
 * - 缓存击穿：逻辑过期（热点 Key 在逻辑过期后异步刷新，旧值仍可用）
 * - 缓存雪崩：逻辑过期本身避免 Key 集中失效，不需额外随机 TTL
 * <p>
 * 【一致性策略】
 * 写操作：先更新 DB → TransactionSynchronization.afterCommit 删 Redis
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
    private final com.myxhs.product.mapper.ProductBehaviorMapper productBehaviorMapper;

    /** 【修复m12】自定义有界线程池，替代 ForkJoinPool.commonPool()，避免阻塞公共线程池 */
    /** 【O2修复】MdcAwareExecutorService 包装，异步任务（缓存刷新/延迟双删/布隆加载）日志携带 traceId */
    private static final java.util.concurrent.ExecutorService SPU_ASYNC_EXECUTOR =
            new com.myxhs.common.trace.MdcAwareExecutorService(
                    new java.util.concurrent.ThreadPoolExecutor(
                            2, 8, 60, java.util.concurrent.TimeUnit.SECONDS,
                            new java.util.concurrent.LinkedBlockingQueue<>(100),
                            r -> { Thread t = new Thread(r, "spu-async"); t.setDaemon(true); return t; },
                            new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy()
                    ));

    /** 商品浏览事件落库线程池（可观测性）：DiscardPolicy——事件可容忍丢失，绝不影响详情响应 */
    private static final java.util.concurrent.ExecutorService SPU_VIEW_EXECUTOR =
            new com.myxhs.common.trace.MdcAwareExecutorService(
                    new java.util.concurrent.ThreadPoolExecutor(
                            1, 2, 60, java.util.concurrent.TimeUnit.SECONDS,
                            new java.util.concurrent.LinkedBlockingQueue<>(500),
                            r -> { Thread t = new Thread(r, "spu-view"); t.setDaemon(true); return t; },
                            new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy()
                    ));

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
        try {
            spuBloomFilter = redissonClient.getBloomFilter("myxhs:product:bloom:spu");
            boolean isNewFilter = spuBloomFilter.tryInit(1_000_000L, 0.01);

        if (isNewFilter) {
            // 首次部署：异步分批加载历史数据，不阻塞启动
            // bloomFilterReady 保持 false，加载期间跳过布隆过滤器（降级为直接查缓存/DB）
            log.info("[布隆过滤器] 检测到首次初始化, 异步加载历史 SPU ID...");
            asyncLoadBloomFilter();
        } else {
            // 已有布隆过滤器，但检查是否为空（count=0 说明之前加载失败或数据被清空）
            if (spuBloomFilter.count() == 0) {
                log.info("[布隆过滤器] Redis 中布隆过滤器为空(count=0), 重新异步加载...");
                asyncLoadBloomFilter();
            } else {
                bloomFilterReady.set(true);
                log.info("[布隆过滤器] Redis 中已存在布隆过滤器(count={}), 直接复用, 跳过全量加载",
                        spuBloomFilter.count());
            }
        }
        } catch (Exception e) {
            log.error("[布隆过滤器] 初始化失败(Redis 不可用?), 服务降级启动: bloomFilterReady=false", e);
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

        // 4. 事务提交后注册布隆过滤器（回滚时不执行，避免假阳性）
        final Long newSpuId = spu.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (spuBloomFilter == null) {
                    log.warn("[布隆过滤器] 未初始化，跳过新增 SPU ID: {}", newSpuId);
                    return;
                }
                spuBloomFilter.add(newSpuId);
                log.debug("[布隆过滤器] 新增 SPU ID: {}", newSpuId);
            }
        });

        log.info("[商品] 创建 SPU 成功, spuId={}, name={}", spu.getId(), spu.getName());
        return spu.getId();
    }

    /**
     * 更新 SPU
     * <p>
     * 使用 LambdaUpdateWrapper 按字段更新，避免 read-then-write 的丢失更新：
     * 两个并发请求分别修改 name 和 description → 各自只更新自己的字段。
     * </p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateSpu(Long spuId, SpuUpdateRequest request) {
        // 1. 校验 SPU 存在
        Spu spu = spuMapper.selectById(spuId);
        if (spu == null) {
            throw new BizException(ResultCode.PRODUCT_NOT_FOUND);
        }

        // 2. 构建部分更新（LambdaUpdateWrapper 只 UPDATE 修改的字段）
        LambdaUpdateWrapper<Spu> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Spu::getId, spuId);

        boolean hasUpdate = false;
        if (StringUtils.hasText(request.getName())) {
            updateWrapper.set(Spu::getName, request.getName());
            hasUpdate = true;
        }
        if (request.getCategoryId() != null) {
            Category category = categoryMapper.selectById(request.getCategoryId());
            if (category == null) {
                throw new BizException(ResultCode.PARAM_INVALID, "分类不存在");
            }
            updateWrapper.set(Spu::getCategoryId, request.getCategoryId());
            hasUpdate = true;
        }
        if (request.getBrandId() != null) {
            updateWrapper.set(Spu::getBrandId, request.getBrandId());
            hasUpdate = true;
        }
        if (request.getDescription() != null) {
            updateWrapper.set(Spu::getDescription, request.getDescription());
            hasUpdate = true;
        }
        if (request.getImages() != null) {
            updateWrapper.set(Spu::getImages, JSON.toJSONString(request.getImages()));
            hasUpdate = true;
        }

        // 3. 执行更新（至少有一个字段变更时才执行，避免空 UPDATE）
        if (hasUpdate) {
            // update(null, wrapper) 不触发 BaseEntity 的 MetaObjectHandler 自动填充，
            // 需显式设置 updatedAt（INSERT_UPDATE 策略）
            updateWrapper.set(Spu::getUpdatedAt, java.time.LocalDateTime.now());
            int affected = spuMapper.update(null, updateWrapper);
            if (affected == 0) {
                throw new BizException(ResultCode.PRODUCT_NOT_FOUND);
            }

            // 4. 事务提交后删除缓存(延迟双删: 立即删 + 1s后二次删, 防并发异步重建回填旧值)
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictSpuCache(spuId);
                    // 延迟二次删除: 覆盖并发读异步重建窗口(异步刷新DB查询+Redis写回, 正常<1s)
                    CompletableFuture.runAsync(() -> {
                        try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        evictSpuCache(spuId);
                    }, SPU_ASYNC_EXECUTOR);
                }
            });
        }

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

        // 校验状态值合法性（ProductStatus.of 对非法值抛 IllegalArgumentException，转为业务异常返回 400）
        try {
            ProductStatus.of(status);
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultCode.PARAM_INVALID, "商品状态无效");
        }

        // LambdaUpdateWrapper 按字段更新，避免 updateById 全量写覆盖并发修改的其他字段
        LambdaUpdateWrapper<Spu> updateWrapper = new LambdaUpdateWrapper<Spu>()
                .eq(Spu::getId, spuId)
                .set(Spu::getStatus, status)
                .set(Spu::getUpdatedAt, java.time.LocalDateTime.now());
        int affected = spuMapper.update(null, updateWrapper);
        if (affected == 0) {
            throw new BizException(ResultCode.PRODUCT_NOT_FOUND);
        }

        // 事务提交后删除缓存
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evictSpuCache(spuId);
            }
        });

        log.info("[商品] SPU 状态变更, spuId={}, status={}", spuId, status);
    }

    // ==================== 读操作（多级缓存） ====================

    /** 空值缓存逻辑过期时间（分钟），比正常数据短 */
    private static final long NULL_CACHE_EXPIRE_MINUTES = 2;

    /** 物理 TTL（分钟）— 逻辑过期 30min 的兜底，Key 最多存活 2h */
    private static final long PHYSICAL_TTL_MINUTES = 120;

    /**
     * 获取 SPU 详情（多级缓存 + 逻辑过期 + 统一空值防穿透）
     * <p>
     * 查询链路：布隆过滤器(前置) → Redis(L2, 逻辑过期) → MySQL(L3)
     * <p>
     * 【两层防穿透策略】
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
        //    Redis 故障时降级跳过（视为"可能存在"，放行到缓存/DB 层）
        if (bloomFilterReady.get()) {
            try {
                if (!spuBloomFilter.contains(spuId)) {
                    log.info("[多级缓存] 布隆过滤器拦截, spuId={} 不存在", spuId);
                    return null;
                }
            } catch (Exception e) {
                log.warn("[多级缓存] 布隆过滤器不可用, 降级放行, spuId={}", spuId, e);
            }
        }

        // 2. L2: Redis 分布式缓存（统一用 RedisCacheData 包装，包括空值）
        String redisKey = RedisKeyConstants.PRODUCT_SPU + spuId;
        RedisCacheData<SpuDetailVO> cacheData = null;
        try {
            cacheData = redisOperator.get(redisKey);
        } catch (Exception e) {
            log.warn("[多级缓存] Redis 不可用，降级直查 DB, spuId={}", spuId, e);
            // cacheData 保持 null，直接落到 L3 MySQL
        }

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

        // 3. L3: MySQL 兜底（冷 Key 并发 miss 用分布式锁防惊群穿透到 DB）
        String loadLockKey = CACHE_REFRESH_LOCK + "load:" + spuId;
        RLock loadLock = redissonClient.getLock(loadLockKey);
        boolean loadLocked = false;
        try {
            // 只有拿到锁的线程查 DB 并回填；其他线程短暂等待后重查缓存，避免冷 key 并发 miss 打爆 DB。
            loadLocked = loadLock.tryLock(1, 10, TimeUnit.SECONDS);
            if (loadLocked) {
                // 双重检查：持锁期间其他线程可能已回填
                try {
                    RedisCacheData<SpuDetailVO> recheck = redisOperator.get(redisKey);
                    if (recheck != null && !recheck.isExpired()) {
                        return recheck.getData();
                    }
                } catch (Exception e) {
                    log.warn("[多级缓存] 双重检查读缓存失败(降级), spuId={}", spuId, e);
                }

                log.info("[多级缓存] L2 Redis 未命中, 查询 DB, spuId={}", spuId);
                SpuDetailVO detail = loadSpuDetailFromDb(spuId);
                if (detail != null) {
                    // 回填 L2（逻辑过期 30min + 物理 TTL 2h 兜底）；Redis 故障降级仅记日志
                    RedisCacheData<SpuDetailVO> newCacheData = RedisCacheData.of(detail, LOGIC_EXPIRE_MINUTES);
                    try {
                        redisOperator.set(redisKey, newCacheData, PHYSICAL_TTL_MINUTES, TimeUnit.MINUTES);
                    } catch (Exception e) {
                        log.warn("[多级缓存] Redis 回填失败(降级, DB数据仍正常返回), spuId={}", spuId, e);
                    }
                } else {
                    // 【第二层防穿透】DB 也查不到 → 缓存空值（统一用 RedisCacheData 包装）
                    // 逻辑过期 2 分钟（2 分钟后重新查 DB，数据可能已新增）
                    // 物理 TTL 5 分钟（兜底清理，防止攻击者用大量不存在 ID 打爆 Redis 内存）
                    RedisCacheData<SpuDetailVO> nullCacheData = RedisCacheData.of(null, NULL_CACHE_EXPIRE_MINUTES);
                    try {
                        redisOperator.set(redisKey, nullCacheData, 5, TimeUnit.MINUTES);
                    } catch (Exception e) {
                        log.warn("[多级缓存] 空值缓存写入失败(降级), spuId={}", spuId, e);
                    }
                    log.info("[多级缓存] DB 未查到, 缓存空值(防穿透, 逻辑过期={}min, 物理TTL=5min), spuId={}",
                            NULL_CACHE_EXPIRE_MINUTES, spuId);
                }
                return detail;
            }

            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[多级缓存] 等待持锁线程回填缓存时被中断, spuId={}", spuId);
                return loadSpuDetailFromDb(spuId);
            }

            try {
                RedisCacheData<SpuDetailVO> recheck = redisOperator.get(redisKey);
                if (recheck != null && !recheck.isExpired()) {
                    return recheck.getData();
                }
            } catch (Exception e) {
                log.warn("[多级缓存] 等待后重查缓存失败(降级), spuId={}", spuId, e);
            }

            log.info("[多级缓存] 未获取到加载锁且缓存仍未命中, 降级查 DB, spuId={}", spuId);
            return loadSpuDetailFromDb(spuId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[多级缓存] 加载锁被中断, spuId={}", spuId);
            return loadSpuDetailFromDb(spuId);
        } finally {
            if (loadLocked && loadLock.isHeldByCurrentThread()) {
                loadLock.unlock();
            }
        }
    }

    /**
     * 记录商品浏览事件（append-only 可观测性，异步落库）
     * <p>
     * 仅在单条详情入口调用（批量路径不埋点）；独立线程池 + DiscardPolicy——
     * 事件可容忍丢失，绝不影响详情响应延迟。
     * </p>
     *
     * @param spuId  被浏览 SPU
     * @param userId 浏览用户（未登录=0）
     * @param skuId  详情页首 SKU（可为空）
     */
    public void recordSpuViewAsync(Long spuId, Long userId, Long skuId) {
        try {
            SPU_VIEW_EXECUTOR.submit(() -> {
                try {
                    com.myxhs.product.entity.ProductBehavior b = new com.myxhs.product.entity.ProductBehavior();
                    b.setId(idGeneratorUtil.nextId());
                    b.setUserId(userId != null ? userId : 0L);
                    b.setSpuId(spuId);
                    b.setSkuId(skuId);
                    b.setBehaviorType(1);
                    b.setEventTime(java.time.LocalDateTime.now());
                    productBehaviorMapper.insert(b);
                } catch (Exception e) {
                    log.warn("[商品浏览] 事件落库失败(可容忍): spuId={}", spuId, e);
                }
            });
        } catch (Exception e) {
            // 队列满/提交异常：丢弃（DiscardPolicy 已兜底，此处仅防御）
            log.warn("[商品浏览] 事件提交失败(可容忍): spuId={}", spuId);
        }
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
        // 删 Redis；Redis 故障时降级仅记日志（DB 已提交成功，客户端不应收到 500）
        String redisKey = RedisKeyConstants.PRODUCT_SPU + spuId;
        try {
            redisOperator.delete(redisKey);
        } catch (Exception e) {
            log.warn("[多级缓存] 删除缓存失败(降级, 依赖物理TTL兜底), spuId={}", spuId, e);
        }
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
                    redisOperator.set(redisKey, newCacheData, PHYSICAL_TTL_MINUTES, TimeUnit.MINUTES);
                    log.info("[缓存刷新] 异步刷新完成, spuId={}", spuId);
                } else {
                    // 查不到（已删除/下架）：写空值缓存，防止已删除商品脏数据永远返回
                    RedisCacheData<SpuDetailVO> nullCacheData = RedisCacheData.of(null, NULL_CACHE_EXPIRE_MINUTES);
                    redisOperator.set(redisKey, nullCacheData, 5, TimeUnit.MINUTES);
                    log.info("[缓存刷新] 商品已删除, 写入空值缓存, spuId={}", spuId);
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
     * <p>
     * P2-2：仅返回上架商品——下架/删除的 SPU 返回 null（详情 404），
     * 与 listSpus（仅 ON_SHELF）保持一致。布隆过滤器只增不减，但下架时
     * updateSpuStatus 已 afterCommit evictSpuCache，旧布隆条目仅多一次缓存查询，
     * 命中后按空值缓存（防穿透）返回 null，不会返回错误数据。
     * </p>
     */
    private SpuDetailVO loadSpuDetailFromDb(Long spuId) {
        Spu spu = spuMapper.selectById(spuId);
        if (spu == null) {
            return null;
        }
        // P2-2：下架/删除商品详情不可见
        if (spu.getStatus() == null || spu.getStatus() != ProductStatus.ON_SHELF.getCode()) {
            log.info("[多级缓存] SPU 非上架状态, 详情不可见, spuId={}, status={}", spuId, spu.getStatus());
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
                .map(sku -> toSkuVO(sku, firstImageOf(spu.getImages())))
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

    private SkuVO toSkuVO(Sku sku, String image) {
        SkuVO vo = new SkuVO();
        vo.setId(sku.getId());
        vo.setSpuId(sku.getSpuId());
        vo.setName(sku.getName());
        vo.setPrice(sku.getPrice());
        vo.setOriginalPrice(sku.getOriginalPrice());
        // stock 字段已从 SkuVO 剔除：SKU 表 stock 是冗余占位值，真实库存以 inventory 服务为准
        vo.setSpecs(sku.getSpecs());
        vo.setImage(image);
        vo.setStatus(sku.getStatus());
        return vo;
    }

    /** 取 SPU.images(JSON数组) 的第一张作 SKU 主图 */
    private String firstImageOf(String imagesJson) {
        List<String> list = parseJsonList(imagesJson);
        return list.isEmpty() ? null : list.get(0);
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
