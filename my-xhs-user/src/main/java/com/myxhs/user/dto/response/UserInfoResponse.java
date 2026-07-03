package com.myxhs.user.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用户信息响应
 * <p>
 * 【修复m16】手机号和邮箱在构建时脱敏：138****1234 / u***@example.com
 * </p>
 */
@Data
@Builder
public class UserInfoResponse {

    private Long id;
    private String username;
    private String nickname;
    private String avatar;
    private Integer gender;
    private LocalDate birthday;
    /** 脱敏后的手机号（如 138****1234） */
    private String phone;
    /** 脱敏后的邮箱（如 u***@example.com） */
    private String email;
    private String signature;
    private Integer status;
    private LocalDateTime createdAt;

    /** 手机号脱敏：保留前3后4，中间替换为**** */
    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return phone;
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /** 邮箱脱敏：用户名保留首字符，其余替换为*** */
    public static String maskEmail(String email) {
        if (email == null || !email.contains("@")) return email;
        int atIdx = email.indexOf("@");
        if (atIdx <= 1) return email;
        return email.charAt(0) + "***" + email.substring(atIdx);
    }
}
