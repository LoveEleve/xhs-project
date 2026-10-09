package com.myxhs.product.cache;

import com.myxhs.common.cache.CacheWarmer;
import com.myxhs.product.service.CategoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 商品域缓存预热任务
 * <p>
 * 预热分类树（分类数据极少变更、读取频繁，是最典型的启动预热对象）。
 * 通过 myxhs.cache-warmup.targets=product-category-tree 控制启停。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductCacheWarmer implements CacheWarmer {

    private final CategoryService categoryService;

    @Override
    public String name() {
        return "product-category-tree";
    }

    @Override
    public int warm() {
        return categoryService.warmUpCategoryTree();
    }
}
