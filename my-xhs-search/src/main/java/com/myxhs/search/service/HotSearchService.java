package com.myxhs.search.service;

import com.myxhs.common.constants.RedisKeyConstants;
import com.myxhs.common.id.IdGeneratorUtil;
import com.myxhs.search.dto.HotSearchVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 热搜排行榜服务
 * <p>
 * 核心职责：
 * 1. 记录搜索词到滑动窗口分钟桶（含反作弊过滤）
 * 2. 定时计算热度（指数衰减算法：Score = Σ(count × e^(-λ×Δt))）
 * 3. 获取热搜榜 Top 50（含人工置顶/屏蔽）
 * 4. 快照持久化到 MySQL（历史查询）
 * </p>
 * <p>
 * 滑动窗口设计：
 * - 每分钟一个 Hash 桶：search:window:{yyyyMMddHHmm} → { keyword: count }
 * - TTL=2h，过期自然淘汰
 * - 每 5 分钟定时任务遍历最近 60 个桶，用指数衰减公式计算热度
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HotSearchService {

    private final StringRedisTemplate stringRedisTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final RedissonClient redissonClient;
    private final IdGeneratorUtil idGeneratorUtil;

    @Value("${search.hot.decay-lambda:0.1}")
    private double decayLambda;

    @Value("${search.hot.window-minutes:60}")
    private int windowMinutes;

    @Value("${search.hot.top-size:50}")
    private int topSize;

    @Value("${search.hot.antispam.user-ttl-seconds:300}")
    private int userAntispamTtlSeconds;

    @Value("${search.hot.antispam.ip-max-per-minute:10}")
    private int ipMaxPerMinute;

    private static final String LOCK_KEY = "myxhs:lock:job:search:hot:calculate";
    private static final DateTimeFormatter BUCKET_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 临时 ZSet Key，用于原子 RENAME */
    private static final String HOT_REALTIME_TMP = RedisKeyConstants.SEARCH_HOT_REALTIME + ":tmp";

    // ==================== Lua 脚本 ====================

    /**
     * 反作弊 + 写入分钟桶的 Lua 脚本（原子操作）
     * <p>
     * 修复点：
     * 1. 先检查 IP 限频，再写入用户 Key（避免 IP 超限时用户 Key 已写入导致误拦截）
     * 2. 将屏蔽词检查也纳入 Lua 脚本，消除 TOCTOU 竞态
     * </p>
     * <p>
     * KEYS[1] = 用户反作弊 Key
     * KEYS[2] = IP 反作弊 Key
     * KEYS[3] = 分钟桶 Key
     * KEYS[4] = 屏蔽词 Set Key
     * ARGV[1] = keyword
     * ARGV[2] = 用户反作弊 TTL（秒）
     * ARGV[3] = IP 最大次数/分钟
     * ARGV[4] = 分钟桶 TTL（秒）
     * 返回值：1=成功记录，0=被反作弊拦截，-1=被屏蔽
     * </p>
     */
    private static final DefaultRedisScript<Long> RECORD_SCRIPT = new DefaultRedisScript<>(
            """
            -- 0. 屏蔽词检查（原子性保证，消除 TOCTOU 竞态）
            if redis.call('SISMEMBER', KEYS[4], ARGV[1]) == 1 then
                return -1
            end
            
            -- 1. IP 维度：每分钟搜索 < N 次（先检查 IP，避免 IP 超限时用户 Key 已写入）
            local ipCount = redis.call('INCR', KEYS[2])
            if ipCount == 1 then
                redis.call('EXPIRE', KEYS[2], 60)
            end
            if ipCount > tonumber(ARGV[3]) then
                return 0
            end
            
            -- 2. 用户维度：同一用户同一词 N 秒内只计 1 次
            if redis.call('EXISTS', KEYS[1]) == 1 then
                return 0
            end
            redis.call('SET', KEYS[1], '1', 'EX', tonumber(ARGV[2]))
            
            -- 3. 写入分钟桶
            redis.call('HINCRBY', KEYS[3], ARGV[1], 1)
            redis.call('EXPIRE', KEYS[3], tonumber(ARGV[4]))
            return 1
            """, Long.class);

    // ==================== 搜索词记录 ====================

    /**
     * 记录搜索词到滑动窗口（含反作弊过滤）
     * <p>
     * 使用 Lua 脚本保证原子性：屏蔽词检查 + IP 限频 + 用户限频 + 写入分钟桶 一步完成。
     * 避免分布式多实例场景下的竞态条件。
     * </p>
     *
     * @param keyword 搜索词
     * @param userId  用户ID（可为 null，未登录用户只做 IP 限频）
     * @param ip      客户端 IP
     */
    public void recordSearchKeyword(String keyword, Long userId, String ip) {
        if (keyword == null || keyword.isBlank()) {
            return;
        }
        keyword = keyword.trim();
        if (keyword.length() > 50) {
            keyword = keyword.substring(0, 50);
        }

        try {
            // 构建 Lua 脚本参数
            // 使用 keyword 原文作为 Key 后缀（URL 编码），避免 hashCode 碰撞导致误拦截
            String safeKeySuffix = userId != null
                    ? userId + ":" + keyword
                    : "anon:" + ip + ":" + keyword;
            String userKey = RedisKeyConstants.SEARCH_ANTISPAM_USER + safeKeySuffix;
            String ipKey = RedisKeyConstants.SEARCH_ANTISPAM_IP + ip;
            String bucketKey = RedisKeyConstants.SEARCH_WINDOW
                    + LocalDateTime.now().format(BUCKET_FORMAT);
            String blockedKey = RedisKeyConstants.SEARCH_HOT_BLOCKED;

            Long result = stringRedisTemplate.execute(
                    RECORD_SCRIPT,
                    List.of(userKey, ipKey, bucketKey, blockedKey),
                    keyword,
                    String.valueOf(userAntispamTtlSeconds),
                    String.valueOf(ipMaxPerMinute),
                    "7200" // 分钟桶 TTL=2h
            );

            if (result != null && result == 1) {
                log.debug("[热搜] 记录搜索词: keyword={}, userId={}", keyword, userId);
            }
        } catch (Exception e) {
            // 热搜记录失败不影响搜索主流程，降级处理
            log.warn("[热搜] 记录搜索词失败: keyword={}", keyword, e);
        }
    }

    // ==================== 热度计算（定时任务） ====================

    /**
     * 每 5 分钟重算热度值
     * <p>
     * 公式：Score = Σ(count × e^(-λ×Δt))
     * λ = 0.1，Δt = 分钟差
     * 效果：1分钟前权重≈0.9，30分钟前权重≈0.05，60分钟前权重≈0.002
     * </p>
     * <p>
     * 分布式安全：Redisson 分布式锁保证多实例只有一个执行。
     * </p>
     */
    @Scheduled(fixedRate = 60_000) // 每 1 分钟（测试环境快速验证）
    public void calculateHotSearch() {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, 600, TimeUnit.SECONDS);
            if (!acquired) {
                return;
            }
            doCalculateHotSearch();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("[热搜] 热度计算异常", e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void doCalculateHotSearch() {
        Map<String, Double> scoreMap = new HashMap<>();
        Map<String, Long> rawCountMap = new HashMap<>();
        LocalDateTime now = LocalDateTime.now();

        // 1. Pipeline 批量获取最近 N 个分钟桶（一次网络往返替代 N 次）
        List<String> bucketKeys = new ArrayList<>(windowMinutes);
        for (int i = 0; i < windowMinutes; i++) {
            bucketKeys.add(RedisKeyConstants.SEARCH_WINDOW
                    + now.minusMinutes(i).format(BUCKET_FORMAT));
        }

        StringRedisSerializer serializer = (StringRedisSerializer) stringRedisTemplate.getKeySerializer();
        List<Map<String, String>> allBuckets = stringRedisTemplate.executePipelined(
                (RedisCallback<Object>) connection -> {
                    for (String key : bucketKeys) {
                        connection.hashCommands().hGetAll(serializer.serialize(key));
                    }
                    return null;
                }
        ).stream().map(obj -> {
            if (obj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, String> map = (Map<String, String>) obj;
                return map;
            }
            return Collections.<String, String>emptyMap();
        }).collect(Collectors.toList());

        // 2. 遍历结果，计算时间衰减热度
        for (int i = 0; i < allBuckets.size(); i++) {
            Map<String, String> entries = allBuckets.get(i);
            if (entries.isEmpty()) {
                continue;
            }

            double decay = Math.exp(-decayLambda * i);

            for (Map.Entry<String, String> entry : entries.entrySet()) {
                String keyword = entry.getKey();
                long count = Long.parseLong(entry.getValue());
                double score = count * decay;
                scoreMap.merge(keyword, score, Double::sum);
                rawCountMap.merge(keyword, count, Long::sum);
            }
        }

        // 3. 过滤屏蔽词
        Set<String> blockedSet = stringRedisTemplate.opsForSet()
                .members(RedisKeyConstants.SEARCH_HOT_BLOCKED);
        if (blockedSet != null) {
            blockedSet.forEach(scoreMap::remove);
        }

        if (scoreMap.isEmpty()) {
            log.info("[热搜] 无搜索数据，跳过计算");
            return;
        }

        // 4. 排序取 Top N
        List<Map.Entry<String, Double>> topEntries = scoreMap.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topSize)
                .collect(Collectors.toList());

        // 5. 原子更新 ZSet 排行榜：先写临时 Key，再 RENAME 替换
        //    避免 DELETE + ADD 之间的空窗期导致读取到空数据
        stringRedisTemplate.delete(HOT_REALTIME_TMP);
        for (Map.Entry<String, Double> entry : topEntries) {
            stringRedisTemplate.opsForZSet().add(HOT_REALTIME_TMP, entry.getKey(), entry.getValue());
        }
        stringRedisTemplate.rename(HOT_REALTIME_TMP, RedisKeyConstants.SEARCH_HOT_REALTIME);
        stringRedisTemplate.expire(RedisKeyConstants.SEARCH_HOT_REALTIME, 1, TimeUnit.HOURS);

        // 6. 快照持久化到 MySQL
        snapshotToDatabase(topEntries, rawCountMap, now);

        log.info("[热搜] 热度计算完成: Top {} 词, 最高分={}", topEntries.size(),
                topEntries.isEmpty() ? 0 : String.format("%.2f", topEntries.get(0).getValue()));
    }

    /**
     * 快照持久化到 MySQL
     */
    private void snapshotToDatabase(List<Map.Entry<String, Double>> topEntries,
                                    Map<String, Long> rawCountMap,
                                    LocalDateTime snapshotTime) {
        // 前置检查：快照表是否存在
        try {
            jdbcTemplate.queryForList("SELECT 1 FROM t_hot_search_snapshot LIMIT 1");
        } catch (Exception e) {
            log.info("[热搜] 快照表不存在，跳过持久化(需建表: t_hot_search_snapshot)");
            return;
        }
        try {
            // T-089：先删除同一分钟快照（calculate 60s 周期内重复触发不再插重复行）
            jdbcTemplate.update(
                    "DELETE FROM t_hot_search_snapshot WHERE snapshot_time = ?",
                    snapshotTime);
            String sql = "INSERT INTO t_hot_search_snapshot (id, keyword, score, rank_no, search_count, snapshot_time) " +
                    "VALUES (?, ?, ?, ?, ?, ?)";
            List<Object[]> batchArgs = new ArrayList<>();
            int rank = 1;
            for (Map.Entry<String, Double> entry : topEntries) {
                batchArgs.add(new Object[]{
                        idGeneratorUtil.nextId(),
                        entry.getKey(),
                        entry.getValue(),
                        rank++,
                        rawCountMap.getOrDefault(entry.getKey(), 0L),
                        snapshotTime
                });
            }
            jdbcTemplate.batchUpdate(sql, batchArgs);
            log.debug("[热搜] 快照持久化成功: {} 条", batchArgs.size());
        } catch (Exception e) {
            log.error("[热搜] 快照持久化失败", e);
        }
    }

    // ==================== 查询接口 ====================

    /**
     * 获取热搜榜 Top 50（置顶词优先展示）
     * <p>
     * 置顶词排在最前面（不占排名），其余按热度降序排列。
     * 屏蔽词已在计算阶段过滤，此处无需再过滤。
     * </p>
     */
    public List<HotSearchVO> getHotSearchList() {
        List<HotSearchVO> result = new ArrayList<>();
        int rank = 1;

        // 1. 获取置顶词
        Set<String> pinnedSet = stringRedisTemplate.opsForSet()
                .members(RedisKeyConstants.SEARCH_HOT_PINNED);
        if (pinnedSet != null && !pinnedSet.isEmpty()) {
            for (String word : pinnedSet) {
                result.add(HotSearchVO.builder()
                        .rank(rank++)
                        .keyword(word)
                        .score(0.0)
                        .pinned(true)
                        .tag("置顶")
                        .build());
            }
        }

        // 2. 获取实时排行
        Set<ZSetOperations.TypedTuple<String>> hotSet = stringRedisTemplate.opsForZSet()
                .reverseRangeWithScores(RedisKeyConstants.SEARCH_HOT_REALTIME, 0, topSize - 1);

        if (hotSet != null) {
            // 找出最高分，用于判断"爆"/"热"标签
            double maxScore = hotSet.stream()
                    .mapToDouble(t -> t.getScore() != null ? t.getScore() : 0)
                    .max().orElse(0);

            for (ZSetOperations.TypedTuple<String> tuple : hotSet) {
                String keyword = tuple.getValue();
                // 排除已置顶的
                if (pinnedSet != null && pinnedSet.contains(keyword)) {
                    continue;
                }
                if (rank > topSize) {
                    break;
                }

                double score = tuple.getScore() != null ? tuple.getScore() : 0;
                String tag = determineTag(score, maxScore, rank);

                result.add(HotSearchVO.builder()
                        .rank(rank++)
                        .keyword(keyword)
                        .score(score)
                        .pinned(false)
                        .tag(tag)
                        .build());
            }
        }

        return result;
    }

    /**
     * 获取历史热搜快照（按日期查询）
     * <p>
     * 使用范围查询替代 DATE() 函数，避免索引失效。
     * 对 date 参数做格式校验，防止异常输入。
     * </p>
     */
    public List<HotSearchVO> getHotSearchSnapshot(String date) {
        // 参数校验：严格校验日期格式
        LocalDate parsedDate;
        try {
            parsedDate = LocalDate.parse(date, DATE_FORMAT);
        } catch (DateTimeParseException e) {
            log.warn("[热搜] 日期格式错误: date={}", date);
            return Collections.emptyList();
        }

        try {
            // 使用范围查询替代 DATE(snapshot_time) = ?，让索引生效
            String startTime = parsedDate.atStartOfDay().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String endTime = parsedDate.plusDays(1).atStartOfDay().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT keyword, score, rank_no, search_count FROM t_hot_search_snapshot " +
                            "WHERE snapshot_time >= ? AND snapshot_time < ? " +
                            "ORDER BY snapshot_time DESC, rank_no ASC LIMIT 50",
                    startTime, endTime);

            return rows.stream().map(row -> HotSearchVO.builder()
                    .rank(((Number) row.get("rank_no")).intValue())
                    .keyword((String) row.get("keyword"))
                    .score(((Number) row.get("score")).doubleValue())
                    .pinned(false)
                    .build()).collect(Collectors.toList());
        } catch (Exception e) {
            log.error("[热搜] 查询历史快照失败: date={}", date, e);
            return Collections.emptyList();
        }
    }

    // ==================== 人工干预 ====================

    /**
     * 人工置顶热搜词
     */
    public void pinKeyword(String keyword) {
        stringRedisTemplate.opsForSet().add(RedisKeyConstants.SEARCH_HOT_PINNED, keyword);
        log.info("[热搜] 置顶: keyword={}", keyword);
    }

    /**
     * 取消置顶
     */
    public void unpinKeyword(String keyword) {
        stringRedisTemplate.opsForSet().remove(RedisKeyConstants.SEARCH_HOT_PINNED, keyword);
        log.info("[热搜] 取消置顶: keyword={}", keyword);
    }

    /**
     * 人工屏蔽热搜词
     */
    public void blockKeyword(String keyword) {
        stringRedisTemplate.opsForSet().add(RedisKeyConstants.SEARCH_HOT_BLOCKED, keyword);
        // 同时从排行榜中移除
        stringRedisTemplate.opsForZSet().remove(RedisKeyConstants.SEARCH_HOT_REALTIME, keyword);
        log.info("[热搜] 屏蔽: keyword={}", keyword);
    }

    /**
     * 取消屏蔽
     */
    public void unblockKeyword(String keyword) {
        stringRedisTemplate.opsForSet().remove(RedisKeyConstants.SEARCH_HOT_BLOCKED, keyword);
        log.info("[热搜] 取消屏蔽: keyword={}", keyword);
    }

    // ==================== 私有方法 ====================

    /**
     * 根据热度分数和排名确定标签
     * - 排名 1-3 且分数 > 最高分 80%：爆
     * - 排名 4-10：热
     * - 其余：新
     */
    private String determineTag(double score, double maxScore, int rank) {
        if (rank <= 3 && maxScore > 0 && score >= maxScore * 0.8) {
            return "爆";
        } else if (rank <= 10) {
            return "热";
        } else {
            return "新";
        }
    }
}
