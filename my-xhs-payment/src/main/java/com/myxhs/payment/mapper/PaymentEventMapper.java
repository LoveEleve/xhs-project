package com.myxhs.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myxhs.payment.entity.PaymentEvent;
import org.apache.ibatis.annotations.Mapper;

/**
 * 支付事件流水 Mapper（append-only）
 */
@Mapper
public interface PaymentEventMapper extends BaseMapper<PaymentEvent> {
}
