package com.myxhs.content.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.cache.CacheHelper;
import com.myxhs.common.constants.RedisKeyConstants;
import com.myxhs.common.entity.NotePublishEvent;
import com.myxhs.common.exception.BizException;
import com.myxhs.common.id.IdGeneratorUtil;
import com.myxhs.common.metrics.BusinessMetrics;
import com.myxhs.common.response.PageResult;
import com.myxhs.common.response.ResultCode;
import com.myxhs.content.dto.request.NotePublishRequest;
import com.myxhs.content.dto.request.NoteUpdateRequest;
import com.myxhs.content.dto.response.NoteDetailVO;
import com.myxhs.content.dto.response.NoteItemVO;
import com.myxhs.content.entity.Note;
import com.myxhs.content.entity.LocalMessage;
import com.myxhs.content.enums.AuditStatus;
import com.myxhs.content.enums.NoteStatus;
import com.myxhs.content.filter.DFAFilter;
import com.myxhs.content.mapper.LocalMessageMapper;
import com.myxhs.content.mapper.NoteMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 笔记服务
 * <p>
 * 核心业务逻辑：笔记发布（含DFA敏感词检测）、草稿保存、编辑、删除、详情查询、列表查询。
 * 缓存策略：Cache Aside + 延迟双删。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoteService {

    private final NoteMapper noteMapper;
    private final LocalMessageMapper localMessageMapper;
    private final DFAFilter dfaFilter;
    private final IdGeneratorUtil idGeneratorUtil;
    private final CacheHelper cacheHelper;
    private final ObjectMapper objectMapper;
    private final RocketMQTemplate rocketMQTemplate;
    private final BusinessMetrics businessMetrics;

    /** 每页最大条数限制 */
    private static final int MAX_PAGE_SIZE = 50;

    // ==================== 发布笔记 ====================

    /**
     * 发布笔记
     * <p>
     * 流程：参数校验 → DFA 敏感词检测 → 入库(status=2) → 清除缓存
     * 当前版本 DFA 通过即自动发布，不经过人工审核队列。
     * </p>
     *
     * @param userId  作者ID
     * @param request 发布请求
     * @return 笔记ID
     */
    @Transactional(rollbackFor = Exception.class)
    public Long publishNote(Long userId, NotePublishRequest request) {
        // 1. DFA 敏感词检测（标题 + 正文）
        checkSensitiveWords(request.getTitle(), request.getContent());

        // 2. 构建笔记实体
        Note note = buildNote(userId, request);
        note.setStatus(NoteStatus.PUBLISHED.getCode());
        note.setAuditStatus(AuditStatus.APPROVED.getCode());

        // 3. 入库
        noteMapper.insert(note);
        log.info("[笔记] 发布成功: noteId={}, userId={}", note.getId(), userId);

        businessMetrics.recordFeedPush("publish");

        // 4.【M2】写入本地消息表（与笔记入库同一事务，保证不丢消息）
        NotePublishEvent event = new NotePublishEvent();
        event.setNoteId(note.getId());
        event.setAuthorId(userId);
        event.setPublishTime(System.currentTimeMillis());
        event.setNoteType(note.getNoteType() != null ? note.getNoteType().toString() : "0");

        LocalMessage localMsg = new LocalMessage();
        localMsg.setTopic("FEED_TOPIC");
        localMsg.setBody(toJson(event));
        localMsg.setStatus(0); // 待发送
        localMsg.setRetryCount(0);
        localMsg.setCreatedAt(java.time.LocalDateTime.now());
        localMessageMapper.insert(localMsg);

        final Long localMsgId = localMsg.getId();

        // 5. 事务提交后：清除缓存 + 异步推送 Feed
        final Long finalUserId = userId;
        final Long finalNoteId = note.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // 清除用户笔记列表缓存
                cacheHelper.delayDoubleDelete(RedisKeyConstants.NOTE_LIST_USER + finalUserId);

                // 异步通知 Feed 服务
                try {
                    rocketMQTemplate.asyncSend("FEED_TOPIC", event, new org.apache.rocketmq.client.producer.SendCallback() {
                        @Override
                        public void onSuccess(org.apache.rocketmq.client.producer.SendResult sendResult) {
                            log.info("[NoteService] Feed推送成功, noteId={}", finalNoteId);
                            // 【M2】发送成功 → 标记本地消息已发送
                            localMessageMapper.markSent(localMsgId);
                        }

                        @Override
                        public void onException(Throwable e) {
                            log.error("[NoteService] Feed推送失败(补偿任务重试), noteId={}", finalNoteId, e);
                        }
                    });
                } catch (Exception e) {
                    log.error("[NoteService] Feed推送发送异常, noteId={}", finalNoteId, e);
                }
            }
        });

        return note.getId();
    }

    // ==================== 保存草稿 ====================

    /**
     * 保存草稿
     * <p>
     * 草稿不触发敏感词检测，不触发 Feed 推送。
     * </p>
     */
    @Transactional(rollbackFor = Exception.class)
    public Long saveDraft(Long userId, NotePublishRequest request) {
        Note note = buildNote(userId, request);
        note.setStatus(NoteStatus.DRAFT.getCode());
        note.setAuditStatus(AuditStatus.PENDING.getCode());

        noteMapper.insert(note);
        log.info("[笔记] 草稿保存成功: noteId={}, userId={}", note.getId(), userId);

        // 事务提交后清除用户笔记列表缓存
        final Long finalUserId = userId;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cacheHelper.delayDoubleDelete(RedisKeyConstants.NOTE_LIST_USER + finalUserId);
            }
        });

        return note.getId();
    }

    // ==================== 编辑笔记 ====================

    /**
     * 编辑笔记
     * <p>
     * 仅允许编辑草稿和已发布的笔记。
     * 已发布的笔记编辑后需重新进行敏感词检测。
     * </p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateNote(Long userId, Long noteId, NoteUpdateRequest request) {
        // 1. 查询笔记并校验权限
        Note note = getAndCheckOwner(noteId, userId);

        // 2. 校验状态（仅草稿和已发布可编辑）
        NoteStatus currentStatus = NoteStatus.of(note.getStatus());
        if (currentStatus != NoteStatus.DRAFT && currentStatus != NoteStatus.PUBLISHED) {
            throw new BizException(ResultCode.NOTE_STATUS_ERROR, "当前状态不允许编辑");
        }

        // 3. 如果是已发布状态，编辑内容需重新检测敏感词
        String newTitle = StringUtils.hasText(request.getTitle()) ? request.getTitle() : note.getTitle();
        String newContent = request.getContent() != null ? request.getContent() : note.getContent();
        if (currentStatus == NoteStatus.PUBLISHED) {
            checkSensitiveWords(newTitle, newContent);
        }

        // 4. 更新字段
        if (StringUtils.hasText(request.getTitle())) {
            note.setTitle(request.getTitle());
        }
        if (request.getContent() != null) {
            note.setContent(request.getContent());
        }
        if (request.getImages() != null) {
            note.setImages(toJson(request.getImages()));
        }
        if (request.getVideoUrl() != null) {
            note.setVideoUrl(request.getVideoUrl());
        }
        if (request.getCoverUrl() != null) {
            note.setCoverUrl(request.getCoverUrl());
        }
        if (request.getTopicIds() != null) {
            note.setTopicIds(toJson(request.getTopicIds()));
        }
        if (request.getTags() != null) {
            note.setTags(toJson(request.getTags()));
        }

        noteMapper.updateById(note);
        log.info("[笔记] 编辑成功: noteId={}, userId={}", noteId, userId);

        // 5. 事务提交后清除缓存
        final Long finalNoteId = noteId;
        final Long finalUserId = userId;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cacheHelper.delayDoubleDelete(RedisKeyConstants.NOTE_DETAIL + finalNoteId);
                cacheHelper.delayDoubleDelete(RedisKeyConstants.NOTE_LIST_USER + finalUserId);
            }
        });
    }

    // ==================== 删除笔记 ====================

    /**
     * 删除笔记（逻辑删除）
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteNote(Long userId, Long noteId) {
        // 1. 查询笔记并校验权限
        Note note = getAndCheckOwner(noteId, userId);

        // 2. 逻辑删除
        noteMapper.deleteById(noteId);
        log.info("[笔记] 删除成功: noteId={}, userId={}", noteId, userId);

        // 3. 事务提交后清除缓存（统一使用延迟双删）
        final Long finalNoteId = noteId;
        final Long finalUserId = userId;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cacheHelper.delayDoubleDelete(RedisKeyConstants.NOTE_DETAIL + finalNoteId);
                cacheHelper.delayDoubleDelete(RedisKeyConstants.NOTE_LIST_USER + finalUserId);
            }
        });
    }

    // ==================== 查询笔记详情 ====================

    /**
     * 获取笔记详情（公开接口）
     * <p>
     * 使用 Cache Aside 模式：先查缓存 → 未命中查 DB → 回填缓存。
     * 内置防穿透（缓存空值）和防雪崩（TTL 随机偏移）。
     * </p>
     */
    public NoteDetailVO getNoteDetail(Long noteId) {
        String cacheKey = RedisKeyConstants.NOTE_DETAIL + noteId;

        // 缓存命中标记：AtomicBoolean 用于在 lambda 中记录是否为 miss
        java.util.concurrent.atomic.AtomicBoolean cacheMiss = new java.util.concurrent.atomic.AtomicBoolean(false);

        Note note = cacheHelper.getWithCacheAside(cacheKey, () -> {
            cacheMiss.set(true);
            Note dbNote = noteMapper.selectById(noteId);
            // 只返回已发布的笔记
            if (dbNote != null && dbNote.getStatus() == NoteStatus.PUBLISHED.getCode()) {
                businessMetrics.recordFeedPush("cache_miss");
                return dbNote;
            }
            return null;
        }, 30, TimeUnit.MINUTES);

        if (note != null && !cacheMiss.get()) {
            businessMetrics.recordFeedPush("cache_hit");
        }

        if (note == null) {
            throw new BizException(ResultCode.NOTE_NOT_FOUND);
        }

        return toDetailVO(note);
    }

    // ==================== 查询用户笔记列表 ====================

    /**
     * 获取用户笔记列表（公开接口，仅已发布）
     */
    public PageResult<NoteItemVO> getUserNotes(Long userId, int pageNum, int pageSize) {
        pageSize = Math.min(pageSize, MAX_PAGE_SIZE);
        Page<Note> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Note> wrapper = new LambdaQueryWrapper<Note>()
                .eq(Note::getUserId, userId)
                .eq(Note::getStatus, NoteStatus.PUBLISHED.getCode())
                .orderByDesc(Note::getCreatedAt);

        IPage<Note> result = noteMapper.selectPage(page, wrapper);

        List<NoteItemVO> items = result.getRecords().stream()
                .map(this::toItemVO)
                .collect(Collectors.toList());

        return PageResult.of(pageNum, pageSize, result.getTotal(), items);
    }

    /**
     * 获取当前用户的所有笔记（包含草稿、已下架等，需登录）
     */
    public PageResult<NoteItemVO> getMyNotes(Long userId, Integer status, int pageNum, int pageSize) {
        pageSize = Math.min(pageSize, MAX_PAGE_SIZE);
        Page<Note> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Note> wrapper = new LambdaQueryWrapper<Note>()
                .eq(Note::getUserId, userId)
                .eq(status != null, Note::getStatus, status)
                .orderByDesc(Note::getCreatedAt);

        IPage<Note> result = noteMapper.selectPage(page, wrapper);

        List<NoteItemVO> items = result.getRecords().stream()
                .map(this::toItemVO)
                .collect(Collectors.toList());

        return PageResult.of(pageNum, pageSize, result.getTotal(), items);
    }

    // ==================== 草稿发布 ====================

    /**
     * 将草稿发布
     */
    @Transactional(rollbackFor = Exception.class)
    public void publishDraft(Long userId, Long noteId) {
        Note note = getAndCheckOwner(noteId, userId);

        NoteStatus currentStatus = NoteStatus.of(note.getStatus());
        if (!currentStatus.canTransitTo(NoteStatus.PUBLISHED)) {
            throw new BizException(ResultCode.NOTE_STATUS_ERROR,
                    "当前状态[" + currentStatus.getDesc() + "]不允许发布");
        }

        // 敏感词检测
        checkSensitiveWords(note.getTitle(), note.getContent());

        // 更新状态
        note.setStatus(NoteStatus.PUBLISHED.getCode());
        note.setAuditStatus(AuditStatus.APPROVED.getCode());
        noteMapper.updateById(note);

        log.info("[笔记] 草稿发布成功: noteId={}, userId={}", noteId, userId);

        // 【M2修复】草稿发布后通知 Feed 服务
        NotePublishEvent draftEvent = new NotePublishEvent();
        draftEvent.setNoteId(noteId);
        draftEvent.setAuthorId(userId);
        draftEvent.setPublishTime(System.currentTimeMillis());
        draftEvent.setNoteType(note.getNoteType() != null ? note.getNoteType().toString() : "0");

        LocalMessage localMsg = new LocalMessage();
        localMsg.setTopic("FEED_TOPIC");
        localMsg.setBody(toJson(draftEvent));
        localMsg.setStatus(0);
        localMsg.setRetryCount(0);
        localMsg.setCreatedAt(java.time.LocalDateTime.now());
        localMessageMapper.insert(localMsg);

        final Long draftLocalMsgId = localMsg.getId();

        // 事务提交后：清除缓存 + 推送 Feed
        final Long finalUserId = userId;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cacheHelper.delayDoubleDelete(RedisKeyConstants.NOTE_LIST_USER + finalUserId);

                try {
                    rocketMQTemplate.asyncSend("FEED_TOPIC", draftEvent, new org.apache.rocketmq.client.producer.SendCallback() {
                        @Override
                        public void onSuccess(org.apache.rocketmq.client.producer.SendResult sendResult) {
                            log.info("[NoteService] 草稿发布Feed推送成功, noteId={}", noteId);
                            localMessageMapper.markSent(draftLocalMsgId);
                        }

                        @Override
                        public void onException(Throwable e) {
                            log.error("[NoteService] 草稿发布Feed推送失败(补偿任务重试), noteId={}", noteId, e);
                        }
                    });
                } catch (Exception e) {
                    log.error("[NoteService] 草稿发布Feed推送异常, noteId={}", noteId, e);
                }
            }
        });
    }

    // ==================== 私有方法 ====================

    /**
     * DFA 敏感词检测
     */
    private void checkSensitiveWords(String title, String content) {
        String fullText = (title != null ? title : "") + " " + (content != null ? content : "");
        Set<String> sensitiveWords = dfaFilter.detect(fullText);
        if (!sensitiveWords.isEmpty()) {
            log.warn("[笔记] 检测到敏感词: {}", sensitiveWords);
            throw new BizException(ResultCode.NOTE_CONTENT_ILLEGAL,
                    "内容包含违规词汇：" + String.join("、", sensitiveWords));
        }
    }

    /**
     * 构建笔记实体
     */
    private Note buildNote(Long userId, NotePublishRequest request) {
        Note note = new Note();
        note.setId(idGeneratorUtil.nextId());
        note.setUserId(userId);
        note.setTitle(request.getTitle());
        note.setContent(request.getContent());
        note.setImages(toJson(request.getImages()));
        note.setVideoUrl(request.getVideoUrl());
        note.setCoverUrl(request.getCoverUrl());
        note.setTopicIds(toJson(request.getTopicIds()));
        note.setTags(toJson(request.getTags()));
        note.setNoteType(request.getNoteType() != null ? request.getNoteType() : 0);
        return note;
    }

    /**
     * 查询笔记并校验所有者
     */
    private Note getAndCheckOwner(Long noteId, Long userId) {
        Note note = noteMapper.selectById(noteId);
        if (note == null) {
            throw new BizException(ResultCode.NOTE_NOT_FOUND);
        }
        if (!note.getUserId().equals(userId)) {
            throw new BizException(ResultCode.FORBIDDEN, "无权操作他人笔记");
        }
        return note;
    }

    /**
     * Note → NoteDetailVO
     */
    private NoteDetailVO toDetailVO(Note note) {
        NoteDetailVO vo = new NoteDetailVO();
        vo.setId(note.getId());
        vo.setUserId(note.getUserId());
        vo.setTitle(note.getTitle());
        vo.setContent(note.getContent());
        vo.setImages(fromJsonList(note.getImages(), new TypeReference<List<String>>() {}));
        vo.setVideoUrl(note.getVideoUrl());
        vo.setCoverUrl(note.getCoverUrl());
        vo.setTopicIds(fromJsonList(note.getTopicIds(), new TypeReference<List<Long>>() {}));
        vo.setTags(fromJsonList(note.getTags(), new TypeReference<List<String>>() {}));
        vo.setStatus(note.getStatus());
        vo.setStatusDesc(NoteStatus.of(note.getStatus()).getDesc());
        vo.setNoteType(note.getNoteType());
        vo.setCreatedAt(note.getCreatedAt());
        vo.setUpdatedAt(note.getUpdatedAt());
        return vo;
    }

    /**
     * Note → NoteItemVO
     */
    private NoteItemVO toItemVO(Note note) {
        NoteItemVO vo = new NoteItemVO();
        vo.setId(note.getId());
        vo.setUserId(note.getUserId());
        vo.setTitle(note.getTitle());
        vo.setCoverUrl(note.getCoverUrl());
        vo.setNoteType(note.getNoteType());
        vo.setStatus(note.getStatus());
        vo.setCreatedAt(note.getCreatedAt());

        // 取第一张图片作为列表缩略图
        List<String> images = fromJsonList(note.getImages(), new TypeReference<List<String>>() {});
        if (images != null && !images.isEmpty()) {
            vo.setFirstImage(images.get(0));
        }
        return vo;
    }

    /**
     * 对象转 JSON 字符串
     */
    private String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("[JSON] 序列化失败", e);
            throw new BizException(ResultCode.INTERNAL_ERROR, "数据序列化失败");
        }
    }

    /**
     * JSON 字符串转 List
     */
    private <T> T fromJsonList(String json, TypeReference<T> typeRef) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (JsonProcessingException e) {
            log.error("[JSON] 反序列化失败: {}", json, e);
            return null;
        }
    }
}
