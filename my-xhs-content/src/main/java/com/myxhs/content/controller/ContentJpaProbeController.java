package com.myxhs.content.controller;

import com.myxhs.common.response.R;
import com.myxhs.common.web.AccessTokenGuard;
import com.myxhs.content.jpa.NoteJpaRepository;
import com.myxhs.content.mapper.NoteMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 多 ORM 并存探针（内部令牌保护）：
 * JPA 计数 vs MyBatis-Plus 计数 —— 两套 ORM 读同一张表 t_note。
 */
@RestController
@RequestMapping("/api/content/internal/jpa-probe")
@ConditionalOnProperty(name = "content.jpa.enabled", havingValue = "true")
public class ContentJpaProbeController {

    private final NoteJpaRepository noteJpaRepository;
    private final NoteMapper noteMapper;
    private final AccessTokenGuard accessTokenGuard;

    public ContentJpaProbeController(NoteJpaRepository noteJpaRepository,
                                     NoteMapper noteMapper,
                                     AccessTokenGuard accessTokenGuard) {
        this.noteJpaRepository = noteJpaRepository;
        this.noteMapper = noteMapper;
        this.accessTokenGuard = accessTokenGuard;
    }

    @GetMapping
    public R<Map<String, Object>> probe(@RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!accessTokenGuard.isInternalCall(internalCall)) {
            return R.fail(403, "仅允许内部服务调用");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jpaCount", noteJpaRepository.count());
        data.put("mybatisCount", noteMapper.selectCount(null));
        data.put("jpaFirstTitle", noteJpaRepository.findAll(PageRequest.of(0, 1)).stream()
                .findFirst().map(com.myxhs.content.jpa.NoteJpaEntity::getTitle).orElse(null));
        data.put("orms", "MyBatis-Plus + Spring Data JPA");
        return R.ok(data);
    }
}
