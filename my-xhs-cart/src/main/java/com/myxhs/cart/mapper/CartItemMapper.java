package com.myxhs.cart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myxhs.cart.entity.CartItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 购物车 Mapper
 */
@Mapper
public interface CartItemMapper extends BaseMapper<CartItem> {

    /**
     * 游标分页查询用户 ID（C-16: 替代全量加载，避免内存溢出）
     * @param lastUserId 上一批最大的 user_id，首次传 0
     * @param limit 每批数量
     * @return 下一批用户 ID 列表
     */
    @Select("SELECT DISTINCT user_id FROM t_cart_item WHERE user_id > #{lastUserId} ORDER BY user_id ASC LIMIT #{limit}")
    List<Long> selectDistinctUserIdsByCursor(@Param("lastUserId") long lastUserId, @Param("limit") int limit);
}
