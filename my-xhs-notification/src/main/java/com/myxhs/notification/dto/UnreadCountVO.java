package com.myxhs.notification.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 未读计数 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnreadCountVO {

    /** 总未读数 */
    private int total;

    /** 按类型未读数 (type → count) */
    private Map<Integer, Integer> details;
}
