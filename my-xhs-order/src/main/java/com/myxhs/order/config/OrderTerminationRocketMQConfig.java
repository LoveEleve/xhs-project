package com.myxhs.order.config;

import org.apache.rocketmq.client.AccessChannel;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.apache.rocketmq.spring.autoconfigure.RocketMQProperties;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQMessageConverter;
import org.apache.rocketmq.spring.support.RocketMQUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 订单终结事务消息配置（04B）
 * <p>
 * 为什么需要独立模板：rocketmq-spring 的绑定机制是"一个 RocketMQTemplate 只能挂一个
 * {@link org.apache.rocketmq.spring.annotation.RocketMQTransactionListener}"——
 * 重复绑定在启动时直接抛 IllegalStateException（RocketMQTransactionConfiguration.afterSingletonsInstantiated）。
 * 下单链路已占用默认模板（OrderTransactionListener），终结链路使用**独立生产者组 + 独立模板**，
 * 避免两个业务流塞进同一个监听器做事务分支（事务回查按生产者组路由，混用会导致回查落到错误的监听器）。
 * </p>
 * <p>
 * 除 producer group 外，其余参数与默认模板逐项对齐（name-server / accessChannel / 超时 / 重试 /
 * 压缩 / namespace / instanceName），保证两个生产者的客户端行为一致。
 * </p>
 */
@Configuration
public class OrderTerminationRocketMQConfig {

    /** 独立事务生产者组（事务回查按此组路由到本应用实例） */
    public static final String TERMINATION_PRODUCER_GROUP = "order-termination-producer-group";

    /** 终结事务消息模板 Bean 名（监听器经 rocketMQTemplateBeanName 绑定） */
    public static final String TERMINATION_TEMPLATE_BEAN_NAME = "orderTerminationRocketMQTemplate";

    /**
     * 终结事务消息模板：与 RocketMQAutoConfiguration.defaultMQProducer 同源构造
     * （RocketMQUtil.createDefaultMQProducer 返回 TransactionMQProducer，事务消息要求 producer 为该类型）。
     */
    @Bean(name = TERMINATION_TEMPLATE_BEAN_NAME)
    public RocketMQTemplate orderTerminationRocketMQTemplate(RocketMQProperties properties,
                                                             RocketMQMessageConverter rocketMQMessageConverter) {
        RocketMQProperties.Producer p = properties.getProducer();
        TransactionMQProducer producer = (TransactionMQProducer) RocketMQUtil.createDefaultMQProducer(
                TERMINATION_PRODUCER_GROUP,
                p.getAccessKey(), p.getSecretKey(),
                p.isEnableMsgTrace(), p.getCustomizedTraceTopic());
        producer.setNamesrvAddr(properties.getNameServer());
        if (StringUtils.hasText(properties.getAccessChannel())) {
            producer.setAccessChannel(AccessChannel.valueOf(properties.getAccessChannel()));
        }
        producer.setSendMsgTimeout(p.getSendMessageTimeout());
        producer.setRetryTimesWhenSendFailed(p.getRetryTimesWhenSendFailed());
        producer.setRetryTimesWhenSendAsyncFailed(p.getRetryTimesWhenSendAsyncFailed());
        producer.setMaxMessageSize(p.getMaxMessageSize());
        producer.setCompressMsgBodyOverHowmuch(p.getCompressMessageBodyThreshold());
        producer.setRetryAnotherBrokerWhenNotStoreOK(p.isRetryNextServer());
        producer.setUseTLS(p.isTlsEnable());
        if (StringUtils.hasText(p.getNamespace())) {
            producer.setNamespace(p.getNamespace());
        }
        producer.setInstanceName(p.getInstanceName());

        RocketMQTemplate template = new RocketMQTemplate();
        template.setProducer(producer);
        template.setMessageConverter(rocketMQMessageConverter.getMessageConverter());
        return template;
    }
}
