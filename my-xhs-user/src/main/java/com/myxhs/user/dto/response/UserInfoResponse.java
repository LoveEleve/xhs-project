package com.myxhs.user.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用户信息响应
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
    private String phone;
    private String email;
    private String signature;
    private Integer status;
    private LocalDateTime createdAt;
}
