package com.myxhs.content.controller;

import com.myxhs.common.response.R;
import com.myxhs.common.web.AccessTokenGuard;
import com.myxhs.content.filter.DFAFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 敏感词词库管理（内部/管理端点）
 * <p>
 * 背景：DFAFilter 已支持动态增删词与热重建，但此前没有入口，只能改库/重启 → 词库实际只有初始词。
 * 本端点把该能力接出来：管理令牌保护（X-Admin-Call，fail-closed），支持热更新与规模查询。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/content/internal/sensitive-words")
@RequiredArgsConstructor
public class SensitiveWordAdminController {

    private final DFAFilter dfaFilter;
    private final AccessTokenGuard accessTokenGuard;

    /** 查询当前词库规模（含动态词） */
    @GetMapping("/count")
    public R<Integer> count(@RequestHeader(value = "X-Admin-Call", required = false) String adminCall) {
        if (!accessTokenGuard.isAdminCall(adminCall)) {
            return R.fail(403, "仅限管理调用");
        }
        return R.ok(dfaFilter.getWordCount());
    }

    /**
     * 热更新词库：action=add|remove（缺省 add），words 为词列表
     */
    @PostMapping
    public R<Integer> update(@RequestBody Map<String, Object> body,
                             @RequestHeader(value = "X-Admin-Call", required = false) String adminCall) {
        if (!accessTokenGuard.isAdminCall(adminCall)) {
            return R.fail(403, "仅限管理调用");
        }
        Object raw = body == null ? null : body.get("words");
        if (!(raw instanceof List<?> rawList) || rawList.isEmpty()) {
            return R.fail(400, "词列表不能为空");
        }
        List<String> words = rawList.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::valueOf)
                .map(String::trim)
                .filter(w -> !w.isEmpty())
                .toList();
        if (words.isEmpty()) {
            return R.fail(400, "词列表不能为空");
        }
        String action = body.get("action") == null ? "add" : String.valueOf(body.get("action"));
        if ("remove".equalsIgnoreCase(action)) {
            dfaFilter.removeDynamicWords(words);
        } else if ("add".equalsIgnoreCase(action)) {
            dfaFilter.addDynamicWords(words);
        } else {
            return R.fail(400, "action 仅支持 add/remove");
        }
        int size = dfaFilter.getWordCount();
        log.info("[敏感词] 词库热更新: action={}, 本次={} 词, 当前规模={}", action, words.size(), size);
        return R.ok(size);
    }
}
