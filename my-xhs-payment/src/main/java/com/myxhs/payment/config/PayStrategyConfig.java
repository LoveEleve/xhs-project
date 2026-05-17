package com.myxhs.payment.config;

import com.myxhs.payment.strategy.PayChannelStrategy;
import com.myxhs.payment.strategy.impl.AlipayPayStrategy;
import com.myxhs.payment.strategy.impl.MockPayStrategy;
import com.myxhs.payment.strategy.impl.WechatPayStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * 支付策略配置
 * <p>
 * 策略模式：根据 pay.type 配置加载对应的支付渠道实现。
 * 每种支付渠道都是一个 {@link PayChannelStrategy} 实现。
 * </p>
 * <p>
 * 支持的渠道：
 * - mock：Mock支付（默认，直接成功）
 * - alipay：支付宝（Mock实现，模拟异步回调）
 * - wechat：微信支付（Mock实现，模拟异步回调）
 * </p>
 * <p>
 * 扩展新渠道只需：
 * 1. 新建 XxxPayStrategy 实现 PayChannelStrategy
 * 2. 在此注册到策略 Map
 * 3. 修改 pay.type 配置切换
 * </p>
 */
@Configuration
public class PayStrategyConfig {

    @Bean
    public Map<Integer, PayChannelStrategy> payChannelStrategyMap(
            MockPayStrategy mockPayStrategy,
            AlipayPayStrategy alipayPayStrategy,
            WechatPayStrategy wechatPayStrategy) {
        Map<Integer, PayChannelStrategy> map = new HashMap<>();
        map.put(1, alipayPayStrategy);   // 1-支付宝
        map.put(2, wechatPayStrategy);   // 2-微信
        map.put(99, mockPayStrategy);     // 99-Mock
        return map;
    }
}
