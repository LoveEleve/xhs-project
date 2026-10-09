package com.myxhs.payment.service;

import com.myxhs.common.exception.BizException;
import com.myxhs.common.metrics.BusinessMetrics;
import com.myxhs.common.response.ResultCode;
import com.myxhs.payment.entity.ChannelFlow;
import com.myxhs.payment.entity.SettlementBill;
import com.myxhs.payment.entity.SettlementDiff;
import com.myxhs.payment.repository.SettlementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 结算域：日终账单（日切）+ 三方对账（渠道对账文件）+ 差异挂账
 * <p>
 * 关键设计：
 * 1. <b>日切</b>：按自然日聚合"成功收款（含已退款单的原始收款）"与"成功退款"，净额 = 收款 − 退款，
 *    手续费 = 净额 × 费率，应结算 = 净额 − 手续费；
 * 2. <b>幂等重跑</b>：账单唯一键 (bill_date, channel)，重跑走条件更新（status 必须仍是"已生成"，
 *    已对账账单必须 force=true 才允许重算并清空旧差异），run_no 记录重跑次数；
 * 3. <b>三方对账</b>：渠道对账文件落 t_channel_flow 后，与我方支付/退款流水逐笔比对，
 *    产出三类差异（本地有渠道无 / 渠道有本地无 / 金额不一致），唯一键保证重复对账幂等，
 *    人工已处理的差异不会被覆盖；本轮已消失的差异自动收敛为"已忽略"；
 * 4. <b>并发</b>：生成/对账用 Redis SETNX 锁按 (日期, 渠道) 串行，状态流转用条件更新，
 *    与人工操作并发时只有一个赢家；
 * 5. <b>比对口径</b>：匹配键 = (业务类型, 渠道回传的商户单号)——支付对 payment_no、退对对 refund_no，
 *    因此要求渠道对账单回传我们下发的 out 单号（真实渠道的 out_trade_no / out_refund_no 语义）；
 * 6. <b>差异与结算的关系</b>：差异单独记挂账台账，<b>不混入本期应结算金额</b>——
 *    账单金额始终是我方记账口径，差异由人工核销并在差异台账体现。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementService {

    private final SettlementRepository settlementRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final BusinessMetrics businessMetrics;

    /** 手续费率（默认 0.6%） */
    @Value("${myxhs.settlement.fee-rate:0.0060}")
    private BigDecimal feeRate;

    /** 参与结算的渠道（默认 1-支付宝 2-微信 99-Mock） */
    @Value("${myxhs.settlement.channels:1,2,99}")
    private String channelsConfig;

    private static final String LOCK_PREFIX = "myxhs:lock:settlement:";
    /** 渠道对账单到账标记：文件未到（或延迟）时跳过对账，避免把"渠道还没出账"误判为全量差异 */
    private static final String STATEMENT_MARKER_PREFIX = "myxhs:settlement:statement:";

    /** 演练扰动类型 */
    public enum Perturb {
        /** 完全一致（用于验证对账链路通畅） */
        NONE,
        /** 渠道少一笔（本地有渠道无） */
        MISSING,
        /** 渠道多一笔（渠道有本地无，长款） */
        EXTRA,
        /** 金额不一致（渠道金额 +0.01） */
        AMOUNT
    }

    // ==================== 账单生成（日切） ====================

    /**
     * 生成/重跑某渠道某日的账单
     *
     * @param force 已对账账单是否允许强制重算（会清空该账单已有差异）
     */
    public SettlementBill generateDailyBill(LocalDate billDate, Integer channel, boolean force) {
        return withChannelLock(billDate, channel, () -> doGenerate(billDate, channel, force));
    }

    private SettlementBill doGenerate(LocalDate billDate, Integer channel, boolean force) {
        LocalDateTime from = billDate.atStartOfDay();
        LocalDateTime to = billDate.plusDays(1).atStartOfDay();

        // SQL 聚合（走 (pay_type, paid_at) / (status, success_at) 索引），不把当日明细拉进 JVM
        SettlementRepository.AmountSummary pays = settlementRepository.aggregatePays(channel, from, to);
        SettlementRepository.AmountSummary refunds = settlementRepository.aggregateRefunds(channel, from, to);

        BigDecimal payAmount = pays.amount();
        BigDecimal refundAmount = refunds.amount();
        BigDecimal netAmount = payAmount.subtract(refundAmount);
        // 手续费按"收款金额（毛额）"计提：退款不退手续费（与渠道实际计费口径一致；
        // 若按净额计提，纯退款日会出现"负手续费"，与真实账单不符）
        BigDecimal feeAmount = payAmount.multiply(feeRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal settleAmount = netAmount.subtract(feeAmount);

        SettlementBill bill = new SettlementBill();
        bill.setBillDate(billDate);
        bill.setChannel(channel);
        bill.setPayCount(pays.count());
        bill.setPayAmount(payAmount);
        bill.setRefundCount(refunds.count());
        bill.setRefundAmount(refundAmount);
        bill.setNetAmount(netAmount);
        bill.setFeeRate(feeRate);
        bill.setFeeAmount(feeAmount);
        bill.setSettleAmount(settleAmount);
        bill.setStatus(SettlementBill.STATUS_GENERATED);
        bill.setRunNo(0);

        SettlementBill existing = settlementRepository.findBill(billDate, channel);
        if (existing == null) {
            settlementRepository.insertBill(bill);
            log.info("[结算] 账单已生成: date={}, channel={}, pay={}笔/{}, refund={}笔/{}, settle={}",
                    billDate, channel, bill.getPayCount(), payAmount, bill.getRefundCount(), refundAmount, settleAmount);
            return settlementRepository.findBill(billDate, channel);
        }

        boolean settled = existing.getStatus() == SettlementBill.STATUS_RECONCILED
                || existing.getStatus() == SettlementBill.STATUS_HAS_DIFF;
        if (settled && !force) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR, "账单已对账，重算需 force=true");
        }
        int affected = settlementRepository.regenerateBill(existing.getId(), existing.getStatus(), bill);
        if (affected == 0) {
            throw new BizException(ResultCode.IDEMPOTENT_REJECT, "账单状态已变化，请刷新后重试");
        }
        if (force) {
            // 重算成功后再清差异（顺序不能反：条件更新失败时不应破坏已有差异记录）
            settlementRepository.deleteDiffs(billDate, channel);
        }
        log.warn("[结算] 账单已重算: date={}, channel={}, runNo={}, force={}",
                billDate, channel, existing.getRunNo() + 1, force);
        return settlementRepository.findBill(billDate, channel);
    }

    /** 批量生成（XXL-Job：T-1 全渠道） */
    public List<SettlementBill> generateAll(LocalDate billDate, boolean force) {
        List<SettlementBill> result = new ArrayList<>();
        for (Integer channel : channels()) {
            try {
                result.add(generateDailyBill(billDate, channel, force));
            } catch (Exception e) {
                log.error("[结算] 账单生成失败: date={}, channel={}, cause={}", billDate, channel, e.getMessage());
            }
        }
        return result;
    }

    // ==================== 三方对账 ====================

    /**
     * 与渠道对账文件逐笔比对，产出/刷新差异并落定账单状态
     */
    public SettlementBill reconcile(LocalDate billDate, Integer channel) {
        return withChannelLock(billDate, channel, () -> doReconcile(billDate, channel));
    }

    /** 对账分页大小与页数上限（安全阀：单日超 50 万笔本地流水时停止并告警） */
    private static final int RECONCILE_PAGE_SIZE = 1000;
    private static final int RECONCILE_MAX_PAGES = 500;

    private SettlementBill doReconcile(LocalDate billDate, Integer channel) {
        // 账单缺失时先在锁内补齐（在同一把 (日期,渠道) 锁下串行）
        SettlementBill bill = settlementRepository.findBill(billDate, channel);
        if (bill == null) {
            bill = doGenerate(billDate, channel, false);
        }
        if (bill.getStatus() != null && bill.getStatus() == SettlementBill.STATUS_VOID) {
            log.warn("[结算] 账单已作废, 跳过对账(需重开请先重新生成): date={}, channel={}", billDate, channel);
            return bill;
        }
        // 渠道对账单未到 → 不对账（账单保持"已生成"），等文件到达后再跑；否则会产出"全量本地有渠道无"的假差异
        if (!statementArrived(billDate, channel)) {
            businessMetrics.recordSettlementReconcile("skipped");
            log.warn("[结算] 渠道对账单未到, 跳过对账(账单保持已生成): date={}, channel={}", billDate, channel);
            return bill;
        }
        LocalDateTime from = billDate.atStartOfDay();
        LocalDateTime to = billDate.plusDays(1).atStartOfDay();
        List<ChannelFlow> channelFlows = settlementRepository.listChannelFlows(billDate, channel);

        List<SettlementDiff> diffs = new ArrayList<>();
        BigDecimal diffAmount = BigDecimal.ZERO;
        // 渠道行声明的商户单号集合（内存有界：等于对账单行数），用于本地侧的"渠道是否声明过"判断
        java.util.Set<String> channelKeys = new java.util.HashSet<>();

        // 1) 渠道行驱动：逐行核对我方（按单号精确查，走 payment_no/refund_no 唯一索引，1 行 1 次查询）
        for (ChannelFlow flow : channelFlows) {
            if (flow.getLocalNo() == null || flow.getLocalNo().isEmpty()) {
                diffs.add(buildDiff(billDate, channel, flow.getBizType(), SettlementDiff.TYPE_CHANNEL_ONLY,
                        "", flow.getChannelNo(), BigDecimal.ZERO, flow.getAmount()));
                diffAmount = diffAmount.subtract(flow.getAmount());
                continue;
            }
            String flowKey = key(flow.getBizType(), flow.getLocalNo());
            if (!channelKeys.add(flowKey)) {
                // 同一商户单号多行：渠道多记，单独报差异而不是被覆盖
                diffs.add(buildDiff(billDate, channel, flow.getBizType(), SettlementDiff.TYPE_CHANNEL_ONLY,
                        flow.getLocalNo(), flow.getChannelNo(), BigDecimal.ZERO, flow.getAmount()));
                diffAmount = diffAmount.subtract(flow.getAmount());
                continue;
            }
            ChannelFlow local = flow.getBizType() == ChannelFlow.BIZ_PAY
                    ? settlementRepository.findLocalPay(channel, flow.getLocalNo(), from, to)
                    : settlementRepository.findLocalRefund(channel, flow.getLocalNo(), from, to);
            if (local == null) {
                diffs.add(buildDiff(billDate, channel, flow.getBizType(), SettlementDiff.TYPE_CHANNEL_ONLY,
                        flow.getLocalNo(), flow.getChannelNo(), BigDecimal.ZERO, flow.getAmount()));
                diffAmount = diffAmount.subtract(flow.getAmount());
            } else if (local.getAmount().compareTo(flow.getAmount()) != 0) {
                BigDecimal gap = local.getAmount().subtract(flow.getAmount());
                diffs.add(buildDiff(billDate, channel, flow.getBizType(), SettlementDiff.TYPE_AMOUNT_MISMATCH,
                        flow.getLocalNo(), flow.getChannelNo(), local.getAmount(), flow.getAmount()));
                diffAmount = diffAmount.add(gap);
            }
        }

        // 2) 本地侧分页扫描（游标 = (时间, id)，走索引有序扫描）：渠道未声明的 → 本地有渠道无
        int localCount = 0;
        localCount += scanLocalPays(billDate, channel, from, to, channelKeys, diffs);
        localCount += scanLocalRefunds(billDate, channel, from, to, channelKeys, diffs);
        for (SettlementDiff diff : diffs) {
            if (diff.getDiffType() == SettlementDiff.TYPE_LOCAL_ONLY) {
                diffAmount = diffAmount.add(diff.getDiffAmount());
            }
        }

        for (SettlementDiff diff : diffs) {
            settlementRepository.upsertDiff(diff);
            businessMetrics.recordSettlementDiff(channel, diff.getDiffType());
        }
        // 3) 自动收敛：本轮已无差异的挂账置为"已忽略"（差异过多时跳过，避免超长 IN 列表）
        if (diffs.size() <= 500) {
            settlementRepository.autoConverge(billDate, channel, diffs);
        } else {
            log.warn("[结算] 差异 {} 笔超过自动收敛上限, 本轮跳过收敛: date={}, channel={}",
                    diffs.size(), billDate, channel);
        }

        List<SettlementDiff> pending = settlementRepository.listDiffs(
                billDate, channel, SettlementDiff.STATUS_PENDING);
        BigDecimal pendingAmount = pending.stream()
                .map(SettlementDiff::getDiffAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        businessMetrics.recordSettlementReconcile(pending.isEmpty() ? "reconciled" : "hasDiff");
        // 账单上的差异口径 = "待处理挂账余额"（影响结算的未核销差异），
        // 而非本轮发现数：人工把差异都处理完后账单自然回到"已对账"，无需再等下一轮
        int targetStatus = pending.isEmpty() ? SettlementBill.STATUS_RECONCILED : SettlementBill.STATUS_HAS_DIFF;
        int settled = settlementRepository.markReconciled(
                bill.getId(), bill.getStatus(), targetStatus, pending.size(), pendingAmount);
        if (settled == 0) {
            log.warn("[结算] 账单状态已被并发修改, 差异已入账待下轮确认: date={}, channel={}", billDate, channel);
        }

        log.info("[结算] 对账完成: date={}, channel={}, 本地={}笔, 渠道={}笔, 本轮差异={}笔/{}, 待处理挂账={}笔/{}",
                billDate, channel, localCount, channelFlows.size(), diffs.size(), diffAmount,
                pending.size(), pendingAmount);
        return settlementRepository.findBill(billDate, channel);
    }

    /** 分页扫描本地收款：渠道未声明的记为"本地有渠道无" */
    private int scanLocalPays(LocalDate billDate, Integer channel, LocalDateTime from, LocalDateTime to,
                              java.util.Set<String> channelKeys, List<SettlementDiff> diffs) {
        int count = 0;
        LocalDateTime cursorTime = LocalDateTime.of(1970, 1, 1, 0, 0);
        long cursorId = 0L;
        for (int page = 0; page < RECONCILE_MAX_PAGES; page++) {
            List<ChannelFlow> rows = settlementRepository.pageLocalPays(
                    channel, from, to, cursorTime, cursorId, RECONCILE_PAGE_SIZE);
            if (rows.isEmpty()) {
                break;
            }
            for (ChannelFlow local : rows) {
                count++;
                if (!channelKeys.contains(key(local.getBizType(), local.getLocalNo()))) {
                    diffs.add(buildDiff(billDate, channel, local.getBizType(), SettlementDiff.TYPE_LOCAL_ONLY,
                            local.getLocalNo(), "", local.getAmount(), BigDecimal.ZERO));
                }
                cursorTime = local.getTradeTime();
                cursorId = local.getId();
            }
            if (rows.size() < RECONCILE_PAGE_SIZE) {
                break;
            }
            if (page == RECONCILE_MAX_PAGES - 1) {
                log.error("[结算] 本地收款扫描超过页数上限({}), 结果可能不完整: date={}, channel={}",
                        RECONCILE_MAX_PAGES, billDate, channel);
            }
        }
        return count;
    }

    /** 分页扫描本地退款：渠道未声明的记为"本地有渠道无" */
    private int scanLocalRefunds(LocalDate billDate, Integer channel, LocalDateTime from, LocalDateTime to,
                                 java.util.Set<String> channelKeys, List<SettlementDiff> diffs) {
        int count = 0;
        LocalDateTime cursorTime = LocalDateTime.of(1970, 1, 1, 0, 0);
        long cursorId = 0L;
        for (int page = 0; page < RECONCILE_MAX_PAGES; page++) {
            List<ChannelFlow> rows = settlementRepository.pageLocalRefunds(
                    channel, from, to, cursorTime, cursorId, RECONCILE_PAGE_SIZE);
            if (rows.isEmpty()) {
                break;
            }
            for (ChannelFlow local : rows) {
                count++;
                if (!channelKeys.contains(key(local.getBizType(), local.getLocalNo()))) {
                    diffs.add(buildDiff(billDate, channel, local.getBizType(), SettlementDiff.TYPE_LOCAL_ONLY,
                            local.getLocalNo(), "", local.getAmount(), BigDecimal.ZERO));
                }
                cursorTime = local.getTradeTime();
                cursorId = local.getId();
            }
            if (rows.size() < RECONCILE_PAGE_SIZE) {
                break;
            }
        }
        return count;
    }

    public List<SettlementBill> reconcileAll(LocalDate billDate) {
        List<SettlementBill> result = new ArrayList<>();
        for (Integer channel : channels()) {
            try {
                result.add(reconcile(billDate, channel));
            } catch (Exception e) {
                log.error("[结算] 对账失败: date={}, channel={}, cause={}", billDate, channel, e.getMessage());
            }
        }
        return result;
    }

    // ==================== 渠道对账文件（导入 / 演练） ====================

    /**
     * 导入渠道对账文件（真实入口：文件解析后逐行落表）
     * <p>
     * 幂等：唯一键 (channel, channel_no) 重复导入只刷新金额/时间；
     * 原子：整批导入在一个事务内，解析中途失败不会留下"半份对账单"参与对账产生假差异。
     * 后续可加"文件批次 + 校验和"以支持多文件/断点续传（当前单文件导入已够用）。
     * </p>
     */
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public int importChannelFlows(List<ChannelFlow> flows, LocalDate fileDate, Integer fileChannel) {
        if (fileDate != null && fileChannel != null) {
            markStatementArrived(fileDate, fileChannel);
        }
        if (flows == null || flows.isEmpty()) {
            return 0;
        }
        int imported = 0;
        for (ChannelFlow flow : flows) {
            if (flow.getChannelNo() == null || flow.getChannelNo().isEmpty()
                    || flow.getChannel() == null || flow.getBillDate() == null
                    || flow.getBizType() == null || flow.getAmount() == null) {
                continue;
            }
            if (flow.getTradeTime() == null) {
                flow.setTradeTime(flow.getBillDate().atStartOfDay());
            }
            flow.setSource("import");
            imported += settlementRepository.upsertChannelFlow(flow);
            markStatementArrived(flow.getBillDate(), flow.getChannel());
        }
        log.info("[结算] 渠道流水导入: {} 行", imported);
        return imported;
    }

    /**
     * 生成演练用渠道对账单（从本地流水派生，可按扰动类型制造差异）
     * <p>
     * 真实场景渠道文件来自外部；演练入口用于验证"对账链路 + 差异挂账"闭环。
     * </p>
     */
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public int simulateChannelStatement(LocalDate billDate, Integer channel, Perturb perturb) {
        LocalDateTime from = billDate.atStartOfDay();
        LocalDateTime to = billDate.plusDays(1).atStartOfDay();
        List<ChannelFlow> flows = new ArrayList<>();
        int cap = 10000;
        List<ChannelFlow> pays = settlementRepository.listLocalPays(billDate, channel, from, to, cap);
        List<ChannelFlow> refunds = settlementRepository.listLocalRefunds(billDate, channel, from, to, cap);
        if (pays.size() >= cap || refunds.size() >= cap) {
            log.warn("[结算] 演练对账单达到条数上限({}), 仅取前 N 条: date={}, channel={}", cap, billDate, channel);
        }
        flows.addAll(pays);
        flows.addAll(refunds);

        // 演练重跑前清掉上一轮的演练流水（渠道文件本身是外部事实，重生成要整体替换）
        settlementRepository.deleteSimulatedFlows(billDate, channel);

        int seq = 0;
        for (ChannelFlow flow : flows) {
            flow.setChannelNo("SIM" + billDate.toString().replace("-", "") + channel + String.format("%04d", ++seq));
            flow.setSource("simulate");
        }
        if (perturb == Perturb.MISSING && !flows.isEmpty()) {
            flows.remove(flows.size() - 1);
        }
        if (perturb == Perturb.AMOUNT && !flows.isEmpty()) {
            ChannelFlow first = flows.get(0);
            first.setAmount(first.getAmount().add(new BigDecimal("0.01")));
        }
        if (perturb == Perturb.EXTRA) {
            ChannelFlow extra = new ChannelFlow();
            extra.setBillDate(billDate);
            extra.setChannel(channel);
            extra.setChannelNo("SIM" + billDate.toString().replace("-", "") + channel + "9999");
            extra.setBizType(ChannelFlow.BIZ_PAY);
            extra.setLocalNo("");
            extra.setAmount(new BigDecimal("100.00"));
            extra.setTradeTime(billDate.atTime(12, 0));
            extra.setSource("simulate");
            flows.add(extra);
        }
        int n = 0;
        for (ChannelFlow flow : flows) {
            n += settlementRepository.upsertChannelFlow(flow);
        }
        markStatementArrived(billDate, channel);
        log.info("[结算] 演练对账单已生成: date={}, channel={}, perturb={}, rows={}", billDate, channel, perturb, n);
        return n;
    }

    // ==================== 查询与人工处理 ====================

    /**
     * 作废账单（人工）：已生成/有差异可作废；作废后重新生成即"重开"，run_no 继续累计
     */
    public SettlementBill voidBill(LocalDate billDate, Integer channel, String remark) {
        SettlementBill bill = settlementRepository.findBill(billDate, channel);
        if (bill == null) {
            throw new BizException(ResultCode.NOT_FOUND, "账单不存在");
        }
        if (bill.getStatus() != SettlementBill.STATUS_GENERATED
                && bill.getStatus() != SettlementBill.STATUS_HAS_DIFF
                && bill.getStatus() != SettlementBill.STATUS_RECONCILED) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR, "当前状态不可作废");
        }
        int affected = settlementRepository.voidBill(bill.getId(), bill.getStatus(), remark);
        if (affected == 0) {
            throw new BizException(ResultCode.IDEMPOTENT_REJECT, "账单状态已变化，请刷新后重试");
        }
        log.warn("[结算] 账单已作废: date={}, channel={}, remark={}", billDate, channel, remark);
        return settlementRepository.findBill(billDate, channel);
    }

    public List<SettlementBill> listBills(LocalDate billDate) {
        return settlementRepository.listBills(billDate);
    }

    public List<SettlementDiff> listDiffs(LocalDate billDate, Integer channel, Integer status) {
        return settlementRepository.listDiffs(billDate, channel, status);
    }

    public List<ChannelFlow> listChannelFlows(LocalDate billDate, Integer channel) {
        return settlementRepository.listChannelFlows(billDate, channel);
    }

    /** 挂账处理（人工）：待处理 → 已处理；金额影响在下一日账单体现（不自动补单，避免动钱） */
    public SettlementDiff handleDiff(Long id, String remark) {
        SettlementDiff diff = settlementRepository.findDiff(id);
        if (diff == null) {
            throw new BizException(ResultCode.NOT_FOUND, "差异记录不存在");
        }
        int affected = settlementRepository.handleDiff(id, remark);
        if (affected == 0) {
            throw new BizException(ResultCode.IDEMPOTENT_REJECT, "差异已处理，请刷新后重试");
        }
        return settlementRepository.findDiff(id);
    }

    // ==================== 内部工具 ====================

    /**
     * 以 (日期, 渠道) 为粒度串行：生成与对账共用一把锁，避免重跑与对账互相覆盖
     */
    private <T> T withChannelLock(LocalDate billDate, Integer channel, java.util.function.Supplier<T> action) {
        String lockKey = LOCK_PREFIX + billDate + ":" + channel;
        String token = UUID.randomUUID().toString();
        if (!Boolean.TRUE.equals(stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, token, java.time.Duration.ofMinutes(5)))) {
            throw new BizException(ResultCode.RATE_LIMIT_REJECT, "该账单正在处理中，请稍后再试");
        }
        try {
            return action.get();
        } finally {
            stringRedisTemplate.execute(new DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
                    Long.class), List.of(lockKey), token);
        }
    }

    /**
     * 标记对账单已到：写失败必须让调用方（导入/演练）整体失败并回滚——
     * 否则会出现"流水已入库但对账因缺标记永久跳过"的静默不一致。
     */
    private void markStatementArrived(LocalDate billDate, Integer channel) {
        try {
            stringRedisTemplate.opsForValue().set(
                    STATEMENT_MARKER_PREFIX + billDate + ":" + channel, "1", java.time.Duration.ofDays(30));
        } catch (Exception e) {
            throw new BizException(ResultCode.SERVICE_UNAVAILABLE, "对账单到账标记写入失败，请重试导入");
        }
    }

    private boolean statementArrived(LocalDate billDate, Integer channel) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(STATEMENT_MARKER_PREFIX + billDate + ":" + channel));
    }

    private List<Integer> channels() {
        List<Integer> list = new ArrayList<>();
        for (String part : channelsConfig.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                list.add(Integer.valueOf(trimmed));
            }
        }
        return list;
    }

    private String key(Integer bizType, String localNo) {
        return bizType + ":" + (localNo == null ? "" : localNo);
    }

    private SettlementDiff buildDiff(LocalDate billDate, Integer channel, Integer bizType, int diffType,
                                     String localNo, String channelNo, BigDecimal localAmount, BigDecimal channelAmount) {
        SettlementDiff diff = new SettlementDiff();
        diff.setBillDate(billDate);
        diff.setChannel(channel);
        diff.setBizType(bizType);
        diff.setDiffType(diffType);
        diff.setLocalNo(localNo == null ? "" : localNo);
        diff.setChannelNo(channelNo == null ? "" : channelNo);
        diff.setLocalAmount(localAmount);
        diff.setChannelAmount(channelAmount);
        diff.setDiffAmount(localAmount.subtract(channelAmount));
        diff.setStatus(SettlementDiff.STATUS_PENDING);
        return diff;
    }
}
