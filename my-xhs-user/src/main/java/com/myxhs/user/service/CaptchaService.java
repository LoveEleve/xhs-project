package com.myxhs.user.service;

import com.myxhs.common.cache.RedisOperator;
import com.myxhs.common.constants.RedisKeyConstants;
import com.myxhs.common.exception.BizException;
import com.myxhs.common.response.ResultCode;
import com.myxhs.user.dto.response.CaptchaResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 图形验证码服务
 * <p>
 * 自行绘制验证码图片（不依赖 Kaptcha），生成 4 位数字+字母验证码。
 * 验证码存入 Redis，有效期 5 分钟，校验后立即删除（一次性消费）。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaptchaService {

    private final RedisOperator redisOperator;

    /** 验证码字符集（去掉容易混淆的 0O1lI） */
    private static final String CHAR_SET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int CODE_LENGTH = 4;
    private static final int WIDTH = 120;
    private static final int HEIGHT = 40;
    private static final long EXPIRE_MINUTES = 5;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * 生成图形验证码
     *
     * @return 验证码响应（Key + Base64 图片）
     */
    public CaptchaResponse generateCaptcha() {
        // 1. 生成随机验证码
        String code = generateCode();
        String key = UUID.randomUUID().toString().replace("-", "");

        // 2. 存入 Redis（5 分钟过期）
        redisOperator.set(RedisKeyConstants.USER_CAPTCHA + key, code.toUpperCase(),
                EXPIRE_MINUTES, TimeUnit.MINUTES);

        // 3. 绘制验证码图片
        String base64Image = drawCaptchaImage(code);

        log.debug("[验证码] 生成成功, key={}", key);
        return CaptchaResponse.builder()
                .captchaKey(key)
                .captchaImage("data:image/png;base64," + base64Image)
                .build();
    }

    /**
     * 校验验证码（一次性消费）
     *
     * @param key  验证码 Key
     * @param code 用户输入的验证码
     */
    public void verifyCaptcha(String key, String code) {
        String redisKey = RedisKeyConstants.USER_CAPTCHA + key;
        Object cached = redisOperator.get(redisKey);

        if (cached == null) {
            throw new BizException(ResultCode.CAPTCHA_EXPIRED);
        }

        // 先校验，再删除（校验失败也删除，防止暴力枚举）
        boolean matched = cached.toString().equalsIgnoreCase(code);
        redisOperator.delete(redisKey);

        if (!matched) {
            throw new BizException(ResultCode.CAPTCHA_ERROR);
        }
    }

    /**
     * 生成随机验证码字符串
     */
    private String generateCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CHAR_SET.charAt(SECURE_RANDOM.nextInt(CHAR_SET.length())));
        }
        return sb.toString();
    }

    /**
     * 绘制验证码图片并返回 Base64
     */
    private String drawCaptchaImage(String code) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();

        // 背景
        g.setColor(new Color(245, 245, 245));
        g.fillRect(0, 0, WIDTH, HEIGHT);

        // 绘制干扰线
        for (int i = 0; i < 6; i++) {
            g.setColor(new Color(SECURE_RANDOM.nextInt(200), SECURE_RANDOM.nextInt(200), SECURE_RANDOM.nextInt(200)));
            g.drawLine(SECURE_RANDOM.nextInt(WIDTH), SECURE_RANDOM.nextInt(HEIGHT),
                    SECURE_RANDOM.nextInt(WIDTH), SECURE_RANDOM.nextInt(HEIGHT));
        }

        // 绘制验证码字符
        g.setFont(new Font("Arial", Font.BOLD, 28));
        for (int i = 0; i < code.length(); i++) {
            g.setColor(new Color(20 + SECURE_RANDOM.nextInt(110), 20 + SECURE_RANDOM.nextInt(110), 20 + SECURE_RANDOM.nextInt(110)));
            g.drawString(String.valueOf(code.charAt(i)), 10 + i * 26, 30);
        }

        // 绘制噪点
        for (int i = 0; i < 30; i++) {
            g.setColor(new Color(SECURE_RANDOM.nextInt(255), SECURE_RANDOM.nextInt(255), SECURE_RANDOM.nextInt(255)));
            g.fillRect(SECURE_RANDOM.nextInt(WIDTH), SECURE_RANDOM.nextInt(HEIGHT), 1, 1);
        }

        g.dispose();

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            log.error("[验证码] 图片生成失败", e);
            throw new RuntimeException("验证码图片生成失败", e);
        }
    }
}
