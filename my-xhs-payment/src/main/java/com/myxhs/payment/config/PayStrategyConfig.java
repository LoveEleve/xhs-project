package com.myxhs.payment.config;

import com.myxhs.payment.strategy.PayChannelStrategy;
import com.myxhs.payment.strategy.impl.AlipayPayStrategy;
import com.myxhs.payment.strategy.impl.MockPayStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * 支付策略配置
 * <p>
 * 策略模式：按 payType 注册渠道实现（C-003：单渠道——支付宝对齐真实契约，微信已移除）。
 * <ul>
 *   <li>1 - 支付宝（真实契约形态：HTTP 通知端点 + RSA2 验签 + 同步退款 + 先查后决）</li>
 *   <li>99 - Mock（测试/开发同步渠道）</li>
 * </ul>
 * 扩展新渠道：新建 XxxPayStrategy 实现 {@link PayChannelStrategy}，在此注册。
 * </p>
 */
@Configuration
public class PayStrategyConfig {

    @Bean
    public Map<Integer, PayChannelStrategy> payChannelStrategyMap(
            MockPayStrategy mockPayStrategy,
            AlipayPayStrategy alipayPayStrategy) {
        Map<Integer, PayChannelStrategy> map = new HashMap<>();
        map.put(1, alipayPayStrategy);   // 1-支付宝
        map.put(99, mockPayStrategy);    // 99-Mock
        return map;
    }
}
