package com.myxhs.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myxhs.common.cache.RedisOperator;
import com.myxhs.product.dto.response.CategoryTreeVO;
import com.myxhs.product.entity.Category;
import com.myxhs.product.mapper.CategoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 分类服务
 * <p>
 * 三级分类树缓存策略：Redis(2h) → MySQL
 * 分类数据极少变更，长 TTL 即可。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryMapper categoryMapper;
    private final RedisOperator redisOperator;

    private static final String CATEGORY_TREE_REDIS_KEY = "myxhs:product:category:tree";

    /** 最大递归深度（防止循环 parentId 引用导致 StackOverflow） */
    private static final int MAX_DEPTH = 10;

    /**
     * 获取三级分类树
     * <p>
     * 查询链路：Redis(L2, 2h) → MySQL(L3)。空树不缓存。
     * 当前版本只读——无分类写 API 和缓存失效机制，DB 修改分类后最长 2h 生效。
     * </p>
     */
    public List<CategoryTreeVO> getCategoryTree() {
        // 1. L2: Redis（不可用时降级直查 DB）
        List<CategoryTreeVO> redisCached = null;
        try {
            redisCached = redisOperator.get(CATEGORY_TREE_REDIS_KEY);
        } catch (Exception e) {
            log.warn("[分类] Redis 不可用，降级直查 DB", e);
        }
        if (redisCached != null) {
            log.debug("[分类] L2 Redis 命中");
            return redisCached;
        }

        // 2. L3: MySQL
        log.info("[分类] 缓存未命中, 查询 DB 构建分类树");
        List<CategoryTreeVO> tree = buildCategoryTree();

        // 空树不缓存，防止数据清空后缓存空结果阻塞恢复
        if (!tree.isEmpty()) {
            try {
                redisOperator.set(CATEGORY_TREE_REDIS_KEY, tree, 2, TimeUnit.HOURS);
            } catch (Exception e) {
                log.warn("[分类] Redis 回填失败(降级, DB数据仍正常返回)", e);
            }
        } else {
            log.warn("[分类] 构建分类树为空，跳过缓存（数据可能被清空或初始化未完成）");
        }

        return tree;
    }

    /**
     * 启动预热：构建分类树并写入 Redis（已存在则跳过）
     * <p>
     * 供 CacheWarmer 在应用启动完成后调用（见 myxhs.cache-warmup.targets=product-category-tree），
     * 把"首个请求回源 DB"提前到流量到来之前；预热失败降级为按需加载，不影响启动。
     * </p>
     *
     * @return 预热的分类节点数（0 = 缓存已存在或分类树为空）
     */
    public int warmUpCategoryTree() {
        try {
            List<CategoryTreeVO> cached = redisOperator.get(CATEGORY_TREE_REDIS_KEY);
            if (cached != null && !cached.isEmpty()) {
                log.debug("[分类] 预热跳过: 缓存已存在");
                return 0;
            }
        } catch (Exception e) {
            log.warn("[分类] 预热跳过: Redis 不可用，降级按需加载", e);
            return 0;
        }

        List<CategoryTreeVO> tree = buildCategoryTree();
        if (tree.isEmpty()) {
            log.warn("[分类] 预热跳过: 分类树为空");
            return 0;
        }
        try {
            redisOperator.set(CATEGORY_TREE_REDIS_KEY, tree, 2, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("[分类] 预热回填失败(降级, DB 数据仍正常返回)", e);
            return 0;
        }
        int nodes = countNodes(tree);
        log.info("[分类] 预热完成: {} 个节点", nodes);
        return nodes;
    }

    /**
     * 统计分类树节点总数（含各级子节点）
     */
    private int countNodes(List<CategoryTreeVO> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return 0;
        }
        int count = nodes.size();
        for (CategoryTreeVO node : nodes) {
            count += countNodes(node.getChildren());
        }
        return count;
    }

    /**
     * 从 DB 构建三级分类树
     * <p>
     * 一次查出所有分类，在内存中按 parentId 分组构建树形结构。
     * 避免递归查 DB（N+1 问题）。
     * </p>
     */
    private List<CategoryTreeVO> buildCategoryTree() {
        // 一次查出所有启用的分类（使用枚举常量替代硬编码数字）
        List<Category> allCategories = categoryMapper.selectList(
                new LambdaQueryWrapper<Category>()
                        .eq(Category::getStatus, 1)  // 1=启用（分类状态，非商品上下架语义）
                        .orderByAsc(Category::getSort)
                        .orderByAsc(Category::getId));

        if (allCategories.isEmpty()) {
            return Collections.emptyList();
        }

        // 按 parentId 分组（parentId 为 null 的数据视为一级分类，防御 DB 脏数据 NPE）
        Map<Long, List<Category>> parentMap = allCategories.stream()
                .collect(Collectors.groupingBy(c -> c.getParentId() != null ? c.getParentId() : 0L));

        // 递归构建树（从一级分类开始，parentId=0）
        return buildChildren(parentMap, 0L);
    }

    /**
     * 递归构建子分类列表（带深度保护防循环引用 StackOverflow）
     */
    private List<CategoryTreeVO> buildChildren(Map<Long, List<Category>> parentMap, Long parentId) {
        return buildChildren(parentMap, parentId, 0, new HashSet<>());
    }

    private List<CategoryTreeVO> buildChildren(Map<Long, List<Category>> parentMap, Long parentId,
                                               int depth, Set<Long> path) {
        if (depth >= MAX_DEPTH) {
            log.warn("[分类] 递归深度达到上限 {}, 停止构建, parentId={}", MAX_DEPTH, parentId);
            return Collections.emptyList();
        }
        List<Category> children = parentMap.get(parentId);
        if (children == null || children.isEmpty()) {
            return Collections.emptyList();
        }

        List<CategoryTreeVO> result = new ArrayList<>();
        for (Category category : children) {
            if (!path.add(category.getId())) {
                log.warn("[分类] 检测到 parentId 环路，停止当前分支, categoryId={}", category.getId());
                continue;
            }
            CategoryTreeVO vo = new CategoryTreeVO();
            vo.setId(category.getId());
            vo.setName(category.getName());
            vo.setParentId(category.getParentId());
            vo.setLevel(category.getLevel());
            vo.setSort(category.getSort());
            vo.setIcon(category.getIcon());
            vo.setChildren(buildChildren(parentMap, category.getId(), depth + 1, path));
            result.add(vo);
            path.remove(category.getId());
        }
        return result;
    }
}
