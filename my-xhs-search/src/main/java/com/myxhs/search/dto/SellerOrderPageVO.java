package com.myxhs.search.dto;

import lombok.Data;

import java.util.List;

/**
 * 商家维度子单分页结果
 */
@Data
public class SellerOrderPageVO {

    /** 命中总数（ES total） */
    private long total;

    /** 页码（从 1 开始） */
    private int page;

    /** 每页大小 */
    private int size;

    /** 当前页记录 */
    private List<SellerOrderVO> records;
}
