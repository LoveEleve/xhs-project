package com.myxhs.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myxhs.product.entity.ProductBehavior;
import org.apache.ibatis.annotations.Mapper;

/**
 * 商品浏览事件流水 Mapper（append-only）
 */
@Mapper
public interface ProductBehaviorMapper extends BaseMapper<ProductBehavior> {
}
