package com.myxhs.content.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
import com.myxhs.content.mapper.CommentMapper;
import com.myxhs.common.trace.MqTraceHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
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
    private final CommentMapper commentMapper;
    private final LocalMessageMapper localMessageMapper;
    private final DFAFilter dfaFilter;
    private final IdGeneratorUtil idGeneratorUtil;
    private final CacheHelper cacheHelper;
    private final ObjectMapper objectMapper;
    private final RocketMQTemplate rocketMQTemplate;
    private final BusinessMetrics businessMetrics;
    private final com.myxhs.content.mapper.NoteEventMapper noteEventMapper;

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

        // 3.1 可观测性：发布时点事件（同事务；失败不阻塞发布主流程）
        recordNoteEvent(note.getId(), userId, "PUBLISH",
                NoteStatus.PUBLISHED.getCode(), AuditStatus.APPROVED.getCode());

        businessMetrics.recordFeedPush("publish");

        // 4.【M2】写入本地消息表（与笔记入库同一事务，保证不丢消息）
        NotePublishEvent event = new NotePublishEvent();
        event.setNoteId(note.getId());
        event.setAuthorId(userId);
        event.setPublishTime(System.currentTimeMillis());
        event.setNoteType(note.getNoteType() != null ? note.getNoteType().toString() : "0");

        LocalMessage localMsg = new LocalMessage();
        localMsg.setTopic("FEED_TOPIC");
        localMsg.setBody("{}");
        localMsg.setStatus(0); // 待发送
        localMsg.setRetryCount(0);
        localMsg.setCreatedAt(java.time.LocalDateTime.now());
        localMessageMapper.insert(localMsg);

        event.setLocalMsgId(localMsg.getId());
        localMsg.setBody(toJson(event));
        localMessageMapper.updateById(localMsg);

        final Long localMsgId = localMsg.getId();

        // 5. 事务提交后：清除缓存 + 异步推送 Feed
        final Long finalNoteId = note.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // 异步通知 Feed 服务
                try {
                    rocketMQTemplate.asyncSend("FEED_TOPIC", 
                        MqTraceHelper.wrapWithTraceContext(
                            org.springframework.messaging.support.MessageBuilder.withPayload(event).build()
                        ), 
                        new org.apache.rocketmq.client.producer.SendCallback() {
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

        // P2-13：NOTE_LIST_USER 缓存键只删不填（读路径无回填）→ 移除无效失效调用
        return note.getId();
    }

    // ==================== 编辑笔记 ====================

    /**
     * 编辑笔记
     * <p>使用 LambdaUpdateWrapper 按字段更新，避免 read-then-write 丢失更新</p>
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

        // 4. LambdaUpdateWrapper 按字段更新，避免并发丢失更新
        LambdaUpdateWrapper<Note> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Note::getId, noteId);
        boolean hasUpdate = false;
        if (StringUtils.hasText(request.getTitle())) {
            wrapper.set(Note::getTitle, request.getTitle());
            hasUpdate = true;
        }
        if (request.getContent() != null) {
            wrapper.set(Note::getContent, request.getContent());
            hasUpdate = true;
        }
        if (request.getImages() != null) {
            wrapper.set(Note::getImages, toJson(request.getImages()));
            hasUpdate = true;
        }
        if (request.getVideoUrl() != null) {
            wrapper.set(Note::getVideoUrl, request.getVideoUrl());
            hasUpdate = true;
        }
        if (request.getCoverUrl() != null) {
            wrapper.set(Note::getCoverUrl, request.getCoverUrl());
            hasUpdate = true;
        }
        if (request.getTopicIds() != null) {
            wrapper.set(Note::getTopicIds, toJson(request.getTopicIds()));
            hasUpdate = true;
        }
        if (request.getTags() != null) {
            wrapper.set(Note::getTags, toJson(request.getTags()));
            hasUpdate = true;
        }
        if (hasUpdate) {
            // update(null,wrapper) 不触发 MetaObjectHandler auto-fill，需显式设置 updatedAt
            wrapper.set(Note::getUpdatedAt, java.time.LocalDateTime.now());
            noteMapper.update(null, wrapper);
        }

        log.info("[笔记] 编辑成功: noteId={}, userId={}", noteId, userId);

        // 5. 事务提交后清除缓存
        final Long finalNoteId = noteId;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cacheHelper.delayDoubleDelete(RedisKeyConstants.NOTE_DETAIL + finalNoteId);
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

        // 2. 级联逻辑删除评论（避免孤儿评论可公开查询）——用 delete 返回值替代 selectCount 避免 TOCTOU
        int actualDeleted = commentMapper.delete(new LambdaQueryWrapper<com.myxhs.content.entity.Comment>()
                .eq(com.myxhs.content.entity.Comment::getNoteId, noteId));
        final long commentCount = actualDeleted;

        // 4. 逻辑删除笔记
        noteMapper.deleteById(noteId);
        log.info("[笔记] 删除成功: noteId={}, userId={}, 级联删除{}条评论", noteId, userId, commentCount);

        // 5. 事务提交后清除缓存 + 补偿 counter 服务 + 通知 Feed 清理
        final Long finalNoteId = noteId;
        final Long finalUserId = userId;
        final long finalCommentCount = commentCount;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cacheHelper.delayDoubleDelete(RedisKeyConstants.NOTE_DETAIL + finalNoteId);
                // 补偿 counter：发送单条 UNCOMMENT 事件（带计数），避免循环发送 N 条独立 MQ
                sendCounterEvent(finalNoteId, finalUserId, "UNCOMMENT", finalCommentCount);
                // 通知 Feed 流清理已删除的笔记
                sendCounterEvent(finalNoteId, finalUserId, "NOTE_DELETE", 0);
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

    /**
     * P2-3: 批量获取笔记详情（Feed 场景 20 条/页 → 1 次 HTTP 调用替代 20 次）
     * 逐条复用 readNoteDetail（缓存/防穿透语义一致），单条失败降级跳过
     * O1 修复（2026-08-13）：批量读不发送 VIEW 事件——列表/Feed 浏览不计单篇浏览，
     * 避免 feed 每次刷新虚增 N 条 VIEW（getNoteDetail 单条入口才计 VIEW）
     */
    public Map<Long, NoteDetailVO> batchGetNoteDetail(java.util.List<Long> noteIds) {
        Map<Long, NoteDetailVO> result = new java.util.LinkedHashMap<>();
        if (noteIds == null || noteIds.isEmpty()) {
            return result;
        }
        for (Long noteId : noteIds) {
            try {
                result.put(noteId, readNoteDetail(noteId));
            } catch (Exception e) {
                log.warn("[笔记] 批量详情单条失败跳过: noteId={}", noteId, e);
            }
        }
        return result;
    }

    public NoteDetailVO getNoteDetail(Long noteId) {
        NoteDetailVO vo = readNoteDetail(noteId);
        // 浏览计数：单条详情每次请求都发送 VIEW 事件到 counter 服务（缓存命中时也计数）
        sendCounterEvent(noteId, null, "VIEW", 0);
        return vo;
    }

    /**
     * 读取笔记详情（不含浏览计数副作用）
     * <p>
     * 使用 Cache Aside 模式：先查缓存 → 未命中查 DB → 回填缓存。
     * 内置防穿透（缓存空值）和防雪崩（TTL 随机偏移）。
     * </p>
     */
    private NoteDetailVO readNoteDetail(Long noteId) {
        String cacheKey = RedisKeyConstants.NOTE_DETAIL + noteId;

        // 缓存命中标记：AtomicBoolean 用于在 lambda 中记录是否为 miss
        java.util.concurrent.atomic.AtomicBoolean cacheMiss = new java.util.concurrent.atomic.AtomicBoolean(false);

        Note note = cacheHelper.getWithCacheAside(cacheKey, () -> {
            cacheMiss.set(true);
            Note dbNote = noteMapper.selectById(noteId);
            // RV30：只返回"已发布且审核通过"的笔记（原实现只看 status，审核态异常数据会外泄）
            if (dbNote != null && dbNote.getStatus() == NoteStatus.PUBLISHED.getCode()
                    && dbNote.getAuditStatus() != null
                    && dbNote.getAuditStatus() == com.myxhs.content.enums.AuditStatus.APPROVED.getCode()) {
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
        pageSize = Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
        Page<Note> page = new Page<>(Math.max(1, pageNum), pageSize);
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
        pageSize = Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
        Page<Note> page = new Page<>(Math.max(1, pageNum), pageSize);
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

        // 仅 DRAFT 状态可由作者发布；AUDITING 需经审核后自动发布
        NoteStatus currentStatus = NoteStatus.of(note.getStatus());
        if (!currentStatus.canTransitTo(NoteStatus.PUBLISHED) || NoteStatus.AUDITING.equals(currentStatus)) {
            throw new BizException(ResultCode.NOTE_STATUS_ERROR,
                    "仅草稿状态可发布，当前状态[" + currentStatus.getDesc() + "]不允许");
        }

        // 敏感词检测
        checkSensitiveWords(note.getTitle(), note.getContent());

        // LambdaUpdateWrapper 按字段更新状态，避免全量写覆盖并发修改
        LambdaUpdateWrapper<Note> wrapper = new LambdaUpdateWrapper<Note>()
                .eq(Note::getId, noteId)
                .set(Note::getStatus, NoteStatus.PUBLISHED.getCode())
                .set(Note::getAuditStatus, AuditStatus.APPROVED.getCode())
                .set(Note::getUpdatedAt, java.time.LocalDateTime.now());
        noteMapper.update(null, wrapper);

        log.info("[笔记] 草稿发布成功: noteId={}, userId={}", noteId, userId);

        // 可观测性：发布时点事件（同事务；失败不阻塞发布主流程）
        recordNoteEvent(noteId, userId, "PUBLISH",
                NoteStatus.PUBLISHED.getCode(), AuditStatus.APPROVED.getCode());

        // 【M2修复】草稿发布后通知 Feed 服务
        NotePublishEvent draftEvent = new NotePublishEvent();
        draftEvent.setNoteId(noteId);
        draftEvent.setAuthorId(userId);
        draftEvent.setPublishTime(System.currentTimeMillis());
        draftEvent.setNoteType(note.getNoteType() != null ? note.getNoteType().toString() : "0");

        LocalMessage localMsg = new LocalMessage();
        localMsg.setTopic("FEED_TOPIC");
        localMsg.setBody("{}");
        localMsg.setStatus(0);
        localMsg.setRetryCount(0);
        localMsg.setCreatedAt(java.time.LocalDateTime.now());
        localMessageMapper.insert(localMsg);

        draftEvent.setLocalMsgId(localMsg.getId());
        localMsg.setBody(toJson(draftEvent));
        localMessageMapper.updateById(localMsg);

        final Long draftLocalMsgId = localMsg.getId();

        // 事务提交后：清除缓存 + 推送 Feed
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // 清除笔记详情缓存（避免草稿时的空值占位符导致发布后 404）
                cacheHelper.delayDoubleDelete(RedisKeyConstants.NOTE_DETAIL + noteId);
                try {
                    rocketMQTemplate.asyncSend("FEED_TOPIC", 
                        MqTraceHelper.wrapWithTraceContext(
                            org.springframework.messaging.support.MessageBuilder.withPayload(draftEvent).build()
                        ), 
                        new org.apache.rocketmq.client.producer.SendCallback() {
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

    // ==================== 分享笔记 ====================

    /**
     * 分享笔记
     * <p>
     * 通过 MQ 事件通知 counter 服务更新分享计数（countType=SHARE），
     * 由 counter 服务统一管理计数（对齐 COMMENT/VIEW 的 MQ 模式），
     * 避免直接 Redis hIncrement 与 MQ 双写导致计数翻倍。
     * </p>
     */
    public void shareNote(Long noteId, Long userId) {
        // 校验笔记是否存在且已发布
        Note note = noteMapper.selectById(noteId);
        if (note == null || note.getStatus() != NoteStatus.PUBLISHED.getCode()) {
            throw new BizException(ResultCode.NOTE_NOT_FOUND);
        }

        // 通过 MQ 通知 counter 服务更新 share 计数（对齐 COMMENT/VIEW 模式）
        sendCounterEvent(noteId, userId, "SHARE", 0);

        log.info("[笔记] 分享成功: noteId={}, userId={}", noteId, userId);
    }

    /**
     * 发送计数事件到 MQ
     * <p>用于分享(SHARE)/取消评论(UNCOMMENT)/浏览(VIEW)/删除笔记(NOTE_DELETE)等事件的计数更新。</p>
     */
    private void sendCounterEvent(Long noteId, Long userId, String action, long count) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("noteId", noteId);
            event.put("userId", userId);
            if (count > 0) {
                event.put("count", count);
            }
            rocketMQTemplate.asyncSend(
                    "SOCIAL_TOPIC:" + action,
                    MqTraceHelper.wrapWithTraceContext(MessageBuilder.withPayload(event).build()),
                    new org.apache.rocketmq.client.producer.SendCallback() {
                        @Override
                        public void onSuccess(org.apache.rocketmq.client.producer.SendResult sendResult) {
                            log.debug("[计数] {}事件发送成功: noteId={}", action, noteId);
                        }
                        @Override
                        public void onException(Throwable e) {
                            log.warn("[计数] {}事件发送失败: noteId={}", action, noteId, e);
                        }
                    });
        } catch (Exception e) {
            log.error("[计数] {}事件序列化失败: noteId={}", action, noteId, e);
        }
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
     * 记录笔记状态事件（append-only 可观测性）
     * <p>
     * 与发布业务同一事务；写入失败仅告警不阻塞发布主流程（可观测性增强不破坏核心链路）。
     * </p>
     */
    private void recordNoteEvent(Long noteId, Long userId, String eventType,
                                 Integer status, Integer auditStatus) {
        try {
            com.myxhs.content.entity.NoteEvent ev = new com.myxhs.content.entity.NoteEvent();
            ev.setId(idGeneratorUtil.nextId());
            ev.setNoteId(noteId);
            ev.setUserId(userId);
            ev.setEventType(eventType);
            ev.setStatus(status);
            ev.setAuditStatus(auditStatus);
            ev.setEventTime(java.time.LocalDateTime.now());
            noteEventMapper.insert(ev);
            log.info("[笔记事件] 落库: noteId={}, eventType={}", noteId, eventType);
        } catch (Exception e) {
            log.error("[笔记事件] 写入失败(不阻塞发布): noteId={}, eventType={}", noteId, eventType, e);
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
            businessMetrics.recordFeedPush("json_deser_fail");
            return null;
        }
    }
}
