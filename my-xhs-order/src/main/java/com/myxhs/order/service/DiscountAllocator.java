package com.myxhs.order.service;

import com.myxhs.order.entity.OrderItem;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单优惠分摊器（纯函数，可确定性重算）
 * <p>
 * 解决什么问题：订单级优惠（优惠券/活动）是"整单减多少钱"，但售后/退款是"按商品"发生的。
 * 直接把整单优惠退给某一商品会算错应退金额，因此需要把整单优惠按明细金额比例分摊到每件商品。
 * </p>
 * <p>
 * 算法（最大余数法 / Largest Remainder，分为单位整数运算，无浮点误差）：
 * 1. 明细金额与优惠金额换算为"分"，逐项取下整：base_i = floor(discountCents * amount_i / totalCents)
 * 2. 取整产生的差额（最多 N-1 分）按小数部分从大到小逐项 +1 分补齐
 * 3. 平局按明细 ID 升序，保证同一订单任何时候重算结果一致
 * </p>
 * <p>
 * 不变量（自校验）：Σ 分摊 == min(优惠, 商品总额)，且每项分摊 ≤ 该项金额。
 * 因此"退款 = 明细金额 - 分摊"天然不会超退；分摊结果无需落库，售后时按订单快照重算即可。
 * </p>
 */
@Slf4j
public final class DiscountAllocator {

    private static final int CENTS = 2;

    /** 精确份额的放大倍数（1e6，仅用于取余比较，不参与金额输出） */
    private static final BigDecimal MICRO = BigDecimal.valueOf(1_000_000L);

    private DiscountAllocator() {
    }

    /**
     * 把订单级优惠按明细金额比例分摊
     *
     * @param items         订单明细（需含 id / totalAmount）
     * @param totalDiscount 订单优惠总额
     * @return 明细ID → 分摊金额（保留 2 位小数），无优惠时为 0
     */
    public static Map<Long, BigDecimal> allocateByItems(List<OrderItem> items, BigDecimal totalDiscount) {
        Map<Long, BigDecimal> result = new LinkedHashMap<>();
        if (items == null || items.isEmpty()) {
            return result;
        }

        long[] amounts = new long[items.size()];
        long totalCents = 0L;
        for (int i = 0; i < items.size(); i++) {
            amounts[i] = toCents(items.get(i).getTotalAmount());
            totalCents += amounts[i];
        }

        long discountCents = Math.min(toCents(totalDiscount), totalCents);
        if (totalCents <= 0 || discountCents <= 0) {
            for (OrderItem item : items) {
                result.put(item.getId(), cents(0L));
            }
            return result;
        }

        long[] shares = new long[items.size()];
        List<long[]> remainders = new ArrayList<>();  // [index, 小数余数（放大 1e6）, 明细ID]
        long allocated = 0L;
        BigDecimal discountDecimal = BigDecimal.valueOf(discountCents);
        BigDecimal totalDecimal = BigDecimal.valueOf(totalCents);
        for (int i = 0; i < items.size(); i++) {
            // 精确份额（放大 1e6）与下整份额：全程 BigDecimal，避免 amount × discount 相乘溢出 long
            long exact = discountDecimal.multiply(BigDecimal.valueOf(amounts[i]))
                    .multiply(MICRO)
                    .divide(totalDecimal, 0, RoundingMode.DOWN)
                    .longValueExact();
            long base = Math.min(exact / 1_000_000L, amounts[i]);   // 单项分摊不超过该项金额
            shares[i] = base;
            allocated += base;
            remainders.add(new long[]{i, exact % 1_000_000L, items.get(i).getId() == null ? i : items.get(i).getId()});
        }

        // 取整差额按余数从大到小补分；平局按明细 ID 升序（保证同一订单重算结果一致）
        long gap = discountCents - allocated;
        if (gap > 0) {
            remainders.sort(Comparator
                    .comparingLong((long[] r) -> r[1]).reversed()
                    .thenComparingLong(r -> r[2]));
            for (long[] r : remainders) {
                if (gap == 0) {
                    break;
                }
                int idx = (int) r[0];
                if (shares[idx] < amounts[idx]) {
                    shares[idx]++;
                    gap--;
                }
            }
            if (gap != 0) {
                // 理论上不可达（优惠 ≤ 商品总额时容量必然足够）；保守放弃余分，绝不超额分摊
                log.warn("[优惠分摊] 余分未能完全分配: gap={}分, items={}", gap, items.size());
            }
        }

        for (int i = 0; i < items.size(); i++) {
            result.put(items.get(i).getId(), cents(shares[i]));
        }
        return result;
    }

    /**
     * 单件可退金额（支持部分数量申请）
     * <p>
     * 整件可退 = 明细金额 - 分摊优惠；申请数量小于购买数量时按比例<b>向下取整</b>到分，
     * 保证多次部分退款的累计金额不会超过整件可退金额（永不超退）。
     * </p>
     *
     * @param item          订单明细
     * @param discountShare 该项分摊的优惠
     * @param applyQuantity 本次申请数量
     */
    public static BigDecimal refundableAmount(OrderItem item, BigDecimal discountShare, int applyQuantity) {
        if (item == null) {
            return cents(0L);
        }
        long amountCents = toCents(item.getTotalAmount());
        long shareCents = Math.min(toCents(discountShare), amountCents);
        long refundableCents = amountCents - shareCents;

        int quantity = item.getQuantity() == null ? 0 : item.getQuantity();
        if (quantity <= 0 || applyQuantity <= 0) {
            return cents(0L);
        }
        if (applyQuantity >= quantity) {
            return cents(refundableCents);
        }
        return cents(refundableCents * applyQuantity / quantity);
    }

    private static long toCents(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            return 0L;
        }
        return amount.setScale(CENTS, RoundingMode.DOWN).movePointRight(CENTS).longValueExact();
    }

    private static BigDecimal cents(long value) {
        return BigDecimal.valueOf(value, CENTS);
    }
}
