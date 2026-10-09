package com.myxhs.payment.controller;

import com.myxhs.common.response.R;
import com.myxhs.common.web.AccessTokenGuard;
import com.myxhs.payment.entity.ChannelFlow;
import com.myxhs.payment.entity.SettlementBill;
import com.myxhs.payment.entity.SettlementDiff;
import com.myxhs.payment.service.SettlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 结算接口（内部）：日终账单生成/重跑、三方对账、差异挂账处理、渠道对账单导入与演练
 * <p>
 * 全部要求 X-Internal-Call（fail-closed）：结算是资金域操作，不对用户面开放。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/settlement")
@RequiredArgsConstructor
public class SettlementController {

    private final SettlementService settlementService;
    private final AccessTokenGuard accessTokenGuard;

    /** 生成/重跑某渠道某日账单（force=true 才允许重算已对账账单） */
    @PostMapping("/bills/generate")
    public R<SettlementBill> generate(
            @RequestParam("billDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate billDate,
            @RequestParam("channel") Integer channel,
            @RequestParam(value = "force", defaultValue = "false") boolean force,
            @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!accessTokenGuard.isInternalCall(internalCall)) {
            return R.fail(403, "仅限内部服务调用");
        }
        return R.ok(settlementService.generateDailyBill(billDate, channel, force));
    }

    /** 批量生成 T-1 全渠道账单 */
    @PostMapping("/bills/generate-all")
    public R<List<SettlementBill>> generateAll(
            @RequestParam("billDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate billDate,
            @RequestParam(value = "force", defaultValue = "false") boolean force,
            @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!accessTokenGuard.isInternalCall(internalCall)) {
            return R.fail(403, "仅限内部服务调用");
        }
        return R.ok(settlementService.generateAll(billDate, force));
    }

    /** 与渠道对账文件比对（单渠道） */
    @PostMapping("/bills/reconcile")
    public R<SettlementBill> reconcile(
            @RequestParam("billDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate billDate,
            @RequestParam("channel") Integer channel,
            @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!accessTokenGuard.isInternalCall(internalCall)) {
            return R.fail(403, "仅限内部服务调用");
        }
        return R.ok(settlementService.reconcile(billDate, channel));
    }

    /** 全渠道对账 */
    @PostMapping("/bills/reconcile-all")
    public R<List<SettlementBill>> reconcileAll(
            @RequestParam("billDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate billDate,
            @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!accessTokenGuard.isInternalCall(internalCall)) {
            return R.fail(403, "仅限内部服务调用");
        }
        return R.ok(settlementService.reconcileAll(billDate));
    }

    /** 作废账单（人工） */
    @PostMapping("/bills/void")
    public R<SettlementBill> voidBill(
            @RequestParam("billDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate billDate,
            @RequestParam("channel") Integer channel,
            @RequestBody(required = false) Map<String, String> body,
            @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!accessTokenGuard.isInternalCall(internalCall)) {
            return R.fail(403, "仅限内部服务调用");
        }
        return R.ok(settlementService.voidBill(billDate, channel, body == null ? null : body.get("remark")));
    }

    /** 账单查询 */
    @GetMapping("/bills")
    public R<List<SettlementBill>> bills(
            @RequestParam("billDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate billDate,
            @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!accessTokenGuard.isInternalCall(internalCall)) {
            return R.fail(403, "仅限内部服务调用");
        }
        return R.ok(settlementService.listBills(billDate));
    }

    /** 差异查询（status 缺省查全部） */
    @GetMapping("/diffs")
    public R<List<SettlementDiff>> diffs(
            @RequestParam("billDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate billDate,
            @RequestParam("channel") Integer channel,
            @RequestParam(value = "status", required = false) Integer status,
            @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!accessTokenGuard.isInternalCall(internalCall)) {
            return R.fail(403, "仅限内部服务调用");
        }
        return R.ok(settlementService.listDiffs(billDate, channel, status));
    }

    /** 挂账人工处理 */
    @PostMapping("/diffs/{id}/handle")
    public R<SettlementDiff> handleDiff(@PathVariable("id") Long id,
                                        @RequestBody(required = false) Map<String, String> body,
                                        @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!accessTokenGuard.isInternalCall(internalCall)) {
            return R.fail(403, "仅限内部服务调用");
        }
        String remark = body == null ? null : body.get("remark");
        return R.ok(settlementService.handleDiff(id, remark));
    }

    /** 导入渠道对账文件（逐行幂等） */
    @PostMapping("/channel-flows/import")
    public R<Integer> importFlows(@RequestBody(required = false) List<ChannelFlow> flows,
                                  @RequestParam(value = "billDate", required = false)
                                  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate billDate,
                                  @RequestParam(value = "channel", required = false) Integer channel,
                                  @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!accessTokenGuard.isInternalCall(internalCall)) {
            return R.fail(403, "仅限内部服务调用");
        }
        // 空对账单（当天无交易）也要标记"文件已到"，否则该渠道会一直跳过对账
        return R.ok(settlementService.importChannelFlows(flows, billDate, channel));
    }

    /** 渠道流水查询 */
    @GetMapping("/channel-flows")
    public R<List<ChannelFlow>> channelFlows(
            @RequestParam("billDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate billDate,
            @RequestParam("channel") Integer channel,
            @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!accessTokenGuard.isInternalCall(internalCall)) {
            return R.fail(403, "仅限内部服务调用");
        }
        return R.ok(settlementService.listChannelFlows(billDate, channel));
    }

    /** 生成演练用对账单（perturb: NONE/MISSING/EXTRA/AMOUNT） */
    @PostMapping("/simulate")
    public R<Integer> simulate(
            @RequestParam("billDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate billDate,
            @RequestParam("channel") Integer channel,
            @RequestParam(value = "perturb", defaultValue = "NONE") SettlementService.Perturb perturb,
            @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!accessTokenGuard.isInternalCall(internalCall)) {
            return R.fail(403, "仅限内部服务调用");
        }
        return R.ok(settlementService.simulateChannelStatement(billDate, channel, perturb));
    }
}
