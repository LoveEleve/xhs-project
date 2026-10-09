package com.myxhs.cart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myxhs.cart.entity.CartEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 购物车事件流水 Mapper（append-only，按保留期清理）
 */
@Mapper
public interface CartEventMapper extends BaseMapper<CartEvent> {

    /**
     * 清理保留期前的事件流水（append-only 表若无保留策略会无限增长；
     * 流水仅用于审计/排查，30 天足够）。LIMIT 分批，避免长事务锁表。
     */
    @org.apache.ibatis.annotations.Delete(
            "DELETE FROM t_cart_event WHERE created_at < #{before} LIMIT #{limit}")
    int deleteStaleEvents(@Param("before") java.time.LocalDateTime before,
                          @Param("limit") int limit);
}
