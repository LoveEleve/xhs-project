package com.myxhs.payment.simulator;

import com.myxhs.payment.config.AlipayMockProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * 模拟支付宝服务器的通知投递器（C-003）
 * <p>
 * 职责：把"支付宝侧签名后的通知参数"以 <b>form 表单 HTTP POST</b> 投递到商户 notify_url，
 * 读取应答体判断是否被 ACK（明文 {@code "success"}）。
 * 投递失败（非 success / 网络异常）→ 返回 false，由 {@link SimulatedAlipayServer} 按节奏重试。
 * </p>
 * <p>
 * 本组件是真实网络出口——单测中由 mock 替代（不真发 HTTP）。
 * </p>
 */
@Slf4j
@Component
public class AlipayNotifySender {

    private final AlipayMockProperties properties;
    private final RestTemplate restTemplate;

    public AlipayNotifySender(AlipayMockProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(5000);
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 投递通知。
     *
     * @param params 已签名的通知参数（form 表单）
     * @return 是否被 ACK（应答体为明文 "success"）
     */
    public boolean send(Map<String, String> params) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        params.forEach(form::add);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        String body = restTemplate.postForObject(
                properties.getNotifyUrl(), new HttpEntity<>(form, headers), String.class);
        boolean acked = "success".equals(body);
        log.info("[模拟支付宝] 通知投递: outTradeNo={}, notifyId={}, ack={}",
                params.get("out_trade_no"), params.get("notify_id"), acked);
        return acked;
    }
}
