package com.myxhs.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
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
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 分类服务
 * <p>
 * 三级分类树缓存策略：Caffeine(1h) → Redis(2h) → MySQL
 * 分类数据极少变更，长 TTL 即可。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryMapper categoryMapper;
    private final RedisOperator redisOperator;
    private final Cache<String, List<CategoryTreeVO>> categoryTreeLocalCache;

    private static final String CATEGORY_TREE_REDIS_KEY = "myxhs:product:category:tree";
    private static final String CATEGORY_TREE_CACHE_KEY = "tree";

    /**
     * 获取三级分类树
     * <p>
     * 查询链路：Caffeine(L1, 1h) → Redis(L2, 2h) → MySQL(L3)
     * </p>
     */
    public List<CategoryTreeVO> getCategoryTree() {
        // 1. L1: Caffeine
        List<CategoryTreeVO> cached = categoryTreeLocalCache.getIfPresent(CATEGORY_TREE_CACHE_KEY);
        if (cached != null) {
            log.debug("[分类] L1 Caffeine 命中");
            return cached;
        }

        // 2. L2: Redis
        List<CategoryTreeVO> redisCached = redisOperator.get(CATEGORY_TREE_REDIS_KEY);
        if (redisCached != null) {
            categoryTreeLocalCache.put(CATEGORY_TREE_CACHE_KEY, redisCached);
            log.debug("[分类] L2 Redis 命中");
            return redisCached;
        }

        // 3. L3: MySQL
        log.info("[分类] 缓存未命中, 查询 DB 构建分类树");
        List<CategoryTreeVO> tree = buildCategoryTree();

        // 回填缓存
        redisOperator.set(CATEGORY_TREE_REDIS_KEY, tree, 2, TimeUnit.HOURS);
        categoryTreeLocalCache.put(CATEGORY_TREE_CACHE_KEY, tree);

        return tree;
    }

    /**
     * 从 DB 构建三级分类树
     * <p>
     * 一次查出所有分类，在内存中按 parentId 分组构建树形结构。
     * 避免递归查 DB（N+1 问题）。
     * </p>
     */
    private List<CategoryTreeVO> buildCategoryTree() {
        // 一次查出所有启用的分类
        List<Category> allCategories = categoryMapper.selectList(
                new LambdaQueryWrapper<Category>()
                        .eq(Category::getStatus, 1)
                        .orderByAsc(Category::getSort)
                        .orderByAsc(Category::getId));

        if (allCategories.isEmpty()) {
            return Collections.emptyList();
        }

        // 按 parentId 分组
        Map<Long, List<Category>> parentMap = allCategories.stream()
                .collect(Collectors.groupingBy(Category::getParentId));

        // 递归构建树（从一级分类开始，parentId=0）
        return buildChildren(parentMap, 0L);
    }

    /**
     * 递归构建子分类列表
     */
    private List<CategoryTreeVO> buildChildren(Map<Long, List<Category>> parentMap, Long parentId) {
        List<Category> children = parentMap.get(parentId);
        if (children == null || children.isEmpty()) {
            return Collections.emptyList();
        }

        List<CategoryTreeVO> result = new ArrayList<>();
        for (Category category : children) {
            CategoryTreeVO vo = new CategoryTreeVO();
            vo.setId(category.getId());
            vo.setName(category.getName());
            vo.setParentId(category.getParentId());
            vo.setLevel(category.getLevel());
            vo.setSort(category.getSort());
            vo.setIcon(category.getIcon());
            vo.setChildren(buildChildren(parentMap, category.getId()));
            result.add(vo);
        }
        return result;
    }
}
