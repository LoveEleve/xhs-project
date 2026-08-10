package com.myxhs.content.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.myxhs.common.cache.CacheHelper;
import com.myxhs.common.constants.RedisKeyConstants;
import com.myxhs.common.exception.BizException;
import com.myxhs.common.id.IdGeneratorUtil;
import com.myxhs.common.response.PageResult;
import com.myxhs.common.response.ResultCode;
import com.myxhs.common.trace.MqTraceHelper;
import com.myxhs.content.dto.request.CommentCreateRequest;
import com.myxhs.content.dto.response.CommentVO;
import com.myxhs.content.entity.Comment;
import com.myxhs.content.entity.Note;
import com.myxhs.content.enums.NoteStatus;
import com.myxhs.content.filter.DFAFilter;
import com.myxhs.content.mapper.CommentMapper;
import com.myxhs.content.mapper.NoteMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 评论服务
 * <p>
 * 核心业务逻辑：发表评论（含DFA敏感词检测）、删除评论、评论列表（楼中楼）、游标分页。
 * 楼中楼设计：一级评论 parent_id=0，二级评论 parent_id=一级评论ID。
 * 游标分页：使用 WHERE id < lastId 避免深分页性能问题。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentService {

    private final CommentMapper commentMapper;
    private final NoteMapper noteMapper;
    private final DFAFilter dfaFilter;
    private final IdGeneratorUtil idGeneratorUtil;
    private final CacheHelper cacheHelper;
    private final RocketMQTemplate rocketMQTemplate;

    /** 一级评论每页最大条数 */
    private static final int MAX_PAGE_SIZE = 20;

    /** 楼中楼默认预加载条数 */
    private static final int DEFAULT_CHILD_PREVIEW_SIZE = 3;

    // ==================== 发表评论 ====================

    /**
     * 发表评论
     * <p>
     * 流程：校验笔记存在且已发布 → DFA 敏感词检测 → 校验父评论（如果是回复） → 入库 → 清除缓存
     * </p>
     *
     * @param userId  评论者ID
     * @param request 评论请求
     * @return 评论ID
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createComment(Long userId, CommentCreateRequest request) {
        // 1. 校验笔记存在且已发布
        Note note = noteMapper.selectById(request.getNoteId());
        if (note == null || note.getStatus() != NoteStatus.PUBLISHED.getCode()) {
            throw new BizException(ResultCode.NOTE_NOT_FOUND, "笔记不存在或未发布");
        }

        // 2. DFA 敏感词检测
        Set<String> sensitiveWords = dfaFilter.detect(request.getContent());
        if (!sensitiveWords.isEmpty()) {
            log.warn("[评论] 检测到敏感词: userId={}, words={}", userId, sensitiveWords);
            throw new BizException(ResultCode.COMMENT_CONTENT_ILLEGAL,
                    "评论包含违规词汇：" + String.join("、", sensitiveWords));
        }

        // 3. 如果是回复评论，校验父评论存在
        Long parentId = request.getParentId() != null ? request.getParentId() : 0L;
        Long replyToId = request.getReplyToId();
        if (parentId > 0) {
            Comment parentComment = commentMapper.selectById(parentId);
            if (parentComment == null) {
                throw new BizException(ResultCode.COMMENT_NOT_FOUND, "父评论不存在");
            }
            // 确保父评论属于同一笔记
            if (!parentComment.getNoteId().equals(request.getNoteId())) {
                throw new BizException(ResultCode.PARAM_INVALID, "父评论不属于该笔记");
            }
            // 如果父评论本身也是子评论，则将 parentId 修正为根评论ID（只支持两级）
            if (parentComment.getParentId() > 0) {
                parentId = parentComment.getParentId();
            }
        }

        // 4. 校验被回复的评论存在（如果指定了 replyToId），并校验属于同一笔记
        if (replyToId != null && replyToId > 0) {
            Comment replyComment = commentMapper.selectById(replyToId);
            if (replyComment == null) {
                throw new BizException(ResultCode.COMMENT_NOT_FOUND, "被回复的评论不存在");
            }
            if (!replyComment.getNoteId().equals(request.getNoteId())) {
                throw new BizException(ResultCode.PARAM_INVALID, "被回复的评论不属于该笔记");
            }
        }

        // 5. 构建评论实体并入库
        Comment comment = new Comment();
        comment.setId(idGeneratorUtil.nextId());
        comment.setNoteId(request.getNoteId());
        comment.setUserId(userId);
        comment.setParentId(parentId);
        comment.setReplyToId(replyToId);
        comment.setContent(request.getContent());
        comment.setLikeCount(0);

        commentMapper.insert(comment);
        log.info("[评论] 发表成功: commentId={}, noteId={}, userId={}, parentId={}",
                comment.getId(), request.getNoteId(), userId, parentId);

        // 6. 事务提交后：清除缓存 + 发送评论通知
        // 【修复C2】MQ 发送必须在 afterCommit 内执行，避免事务回滚后通知已发出
        final Long noteId = request.getNoteId();
        final Long noteAuthorUserId = note.getUserId();
        final Long commentId = comment.getId();
        final String contentPreview = request.getContent() != null
                ? request.getContent().substring(0, Math.min(50, request.getContent().length()))
                : "";
        final String noteTitle = note.getTitle();
        final Long senderId = userId;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cacheHelper.delayDoubleDelete(RedisKeyConstants.COMMENT_LIST + noteId);
                cacheHelper.delayDoubleDelete(RedisKeyConstants.COMMENT_COUNT + noteId);

                // 【修复R1】评论计数增量：发送 MQ 事件让 counter 服务更新笔记评论数
                sendCommentCounterEvent(noteId, "COMMENT", 1);

                // 【修复m17】排除自己评论自己的通知
                if (senderId.equals(noteAuthorUserId)) {
                    return;
                }

                // 异步通知笔记作者
                Map<String, Object> notification = new HashMap<>();
                notification.put("type", 2); // 2=评论
                notification.put("senderId", senderId);
                notification.put("targetUserId", noteAuthorUserId);
                notification.put("targetId", noteId);
                notification.put("targetType", 1); // 1=笔记
                notification.put("content", contentPreview);
                notification.put("targetName", noteTitle);
                try {
                    rocketMQTemplate.asyncSend("NOTIFICATION_TOPIC",
                            MqTraceHelper.wrapWithTraceContext(
                                    org.springframework.messaging.support.MessageBuilder.withPayload(notification).build()),
                            new org.apache.rocketmq.client.producer.SendCallback() {
                                @Override
                                public void onSuccess(org.apache.rocketmq.client.producer.SendResult sendResult) {
                                    log.info("[CommentService] 评论通知已发送: commentId={}, noteAuthorUserId={}",
                                            commentId, noteAuthorUserId);
                                }
                                @Override
                                public void onException(Throwable e) {
                                    log.error("[CommentService] 评论通知发送失败: commentId={}", commentId, e);
                                }
                            });
                } catch (Exception e) {
                    log.error("[CommentService] 评论通知发送异常: commentId={}", commentId, e);
                }
            }
        });

        return comment.getId();
    }

    // ==================== 删除评论 ====================

    /**
     * 删除评论
     * <p>
     * 仅评论作者或笔记作者可删除。
     * 删除一级评论时，其下所有子评论也会被逻辑删除。
     * </p>
     *
     * @param userId    操作者ID
     * @param commentId 评论ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteComment(Long userId, Long commentId) {
        // 1. 查询评论
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw new BizException(ResultCode.COMMENT_NOT_FOUND);
        }

        // 2. 权限校验：评论作者 或 笔记作者 可删除
        Note note = noteMapper.selectById(comment.getNoteId());
        if (!comment.getUserId().equals(userId)
                && (note == null || !note.getUserId().equals(userId))) {
            throw new BizException(ResultCode.FORBIDDEN, "无权删除该评论");
        }

        // 3. 级联删除子评论（先删子再删父；用 delete 返回值替代 selectCount 避免 TOCTOU）
        int childDeleted = 0;
        if (comment.getParentId() == 0) {
            LambdaQueryWrapper<Comment> childWrapper = new LambdaQueryWrapper<Comment>()
                    .eq(Comment::getParentId, commentId);
            childDeleted = commentMapper.delete(childWrapper);
        }

        // 4. 逻辑删除父评论
        commentMapper.deleteById(commentId);
        log.info("[评论] 删除成功: commentId={}, userId={}, 级联删除{}条子评论", commentId, userId, childDeleted);

        // 5. 事务提交后清除缓存 + 发送 counter 事件
        // totalDeleted = 父评论(1) + 实际删除的子评论数
        final long totalDeleted = 1 + childDeleted;
        final Long noteId = comment.getNoteId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cacheHelper.delayDoubleDelete(RedisKeyConstants.COMMENT_LIST + noteId);
                cacheHelper.delayDoubleDelete(RedisKeyConstants.COMMENT_COUNT + noteId);
                // 发送单条 UNCOMMENT 事件（带计数），避免循环发送 N 条独立 MQ
                sendCommentCounterEvent(noteId, "UNCOMMENT", totalDeleted);
            }
        });
    }

    // ==================== 查询一级评论列表（游标分页） ====================

    /**
     * 获取笔记的一级评论列表（游标分页）
     * <p>
     * 游标分页原理：使用 WHERE id < lastId ORDER BY id DESC LIMIT pageSize
     * 优势：无论翻到第几页，查询性能恒定（不像 OFFSET 越大越慢）。
     * 首次请求 lastId 传 null 或 0，后续传上一页最后一条评论的 ID。
     * </p>
     *
     * @param noteId   笔记ID
     * @param lastId   游标（上一页最后一条评论的ID，首次传null）
     * @param pageSize 每页条数
     * @return 一级评论列表（每条一级评论预加载前3条子评论）
     */
    public List<CommentVO> getCommentList(Long noteId, Long lastId, int pageSize) {
        pageSize = Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));

        // 1. 游标分页查询一级评论
        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<Comment>()
                .eq(Comment::getNoteId, noteId)
                .eq(Comment::getParentId, 0);

        // 游标条件：如果有 lastId，则查询 id < lastId 的记录
        if (lastId != null && lastId > 0) {
            wrapper.lt(Comment::getId, lastId);
        }

        wrapper.orderByDesc(Comment::getId);
        wrapper.last("LIMIT " + pageSize);

        List<Comment> rootComments = commentMapper.selectList(wrapper);

        if (rootComments.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. 批量查询所有一级评论的子评论（避免 N+1 问题）
        List<Long> rootIds = rootComments.stream()
                .map(Comment::getId)
                .collect(Collectors.toList());

        // 2.1 查询子评论（限制总量，避免热门笔记加载过多数据到内存）
        // 每个父评论最多预加载 DEFAULT_CHILD_PREVIEW_SIZE 条，总量上限 = rootIds.size() * (DEFAULT_CHILD_PREVIEW_SIZE + 1)
        // 多查 1 条用于判断是否有更多子评论（childCount > preview 时显示"查看更多"）
        int maxChildPerParent = DEFAULT_CHILD_PREVIEW_SIZE + 1;
        int totalLimit = rootIds.size() * maxChildPerParent;
        LambdaQueryWrapper<Comment> allChildWrapper = new LambdaQueryWrapper<Comment>()
                .in(Comment::getParentId, rootIds)
                .orderByAsc(Comment::getId)
                .last("LIMIT " + totalLimit);
        List<Comment> allChildren = commentMapper.selectList(allChildWrapper);

        // 按 parentId 分组（每组最多 maxChildPerParent 条）
        Map<Long, List<Comment>> childrenMap = allChildren.stream()
                .collect(Collectors.groupingBy(Comment::getParentId));

        // 3. 预计算需要精确计数的 parentId（children.size() >= maxChildPerParent）
        List<Long> needExactCountIds = rootComments.stream()
                .map(Comment::getId)
                .filter(id -> childrenMap.getOrDefault(id, Collections.emptyList()).size() >= maxChildPerParent)
                .collect(Collectors.toList());

        // 批量聚合 COUNT（一次 SQL 替换 N 次循环 COUNT）
        Map<Long, Long> exactCountMap = Collections.emptyMap();
        if (!needExactCountIds.isEmpty()) {
            exactCountMap = commentMapper.batchCountByParentIds(needExactCountIds);
        }

        // 4. 组装 VO
        final Map<Long, Long> countMap = exactCountMap;
        return rootComments.stream().map(root -> {
            CommentVO vo = toCommentVO(root);

            List<Comment> children = childrenMap.getOrDefault(root.getId(), Collections.emptyList());
            if (children.size() >= maxChildPerParent) {
                vo.setChildCount(countMap.getOrDefault(root.getId(), (long) children.size()));
            } else {
                vo.setChildCount((long) children.size());
            }
            // 预加载前 N 条子评论
            vo.setChildren(children.stream()
                    .limit(DEFAULT_CHILD_PREVIEW_SIZE)
                    .map(this::toCommentVO)
                    .collect(Collectors.toList()));

            return vo;
        }).collect(Collectors.toList());
    }

    // ==================== 查询子评论列表（游标分页） ====================

    /**
     * 获取一级评论的子评论列表（游标分页，楼中楼展开）
     *
     * @param parentId 一级评论ID
     * @param lastId   游标
     * @param pageSize 每页条数
     * @return 子评论列表
     */
    public List<CommentVO> getChildComments(Long parentId, Long lastId, int pageSize) {
        pageSize = Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));

        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<Comment>()
                .eq(Comment::getParentId, parentId);

        if (lastId != null && lastId > 0) {
            wrapper.gt(Comment::getId, lastId); // 子评论按时间正序，所以用 gt
        }

        wrapper.orderByAsc(Comment::getId);
        wrapper.last("LIMIT " + pageSize);

        List<Comment> children = commentMapper.selectList(wrapper);
        return children.stream().map(this::toCommentVO).collect(Collectors.toList());
    }

    // ==================== 查询评论总数 ====================

    /**
     * 获取笔记的评论总数（走缓存）
     * <p>
     * 使用 Cache Aside 模式缓存评论计数，TTL 5 分钟。
     * 发表/删除评论时通过延迟双删清除缓存。
     * </p>
     */
    public long getCommentCount(Long noteId) {
        String cacheKey = RedisKeyConstants.COMMENT_COUNT + noteId;
        Long count = cacheHelper.getWithCacheAside(cacheKey, () -> {
            return commentMapper.selectCount(
                    new LambdaQueryWrapper<Comment>().eq(Comment::getNoteId, noteId));
        }, 5, TimeUnit.MINUTES);
        return count != null ? count : 0L;
    }

    // ==================== 传统分页查询（备用） ====================

    /**
     * 获取笔记的一级评论列表（传统分页，用于后台管理等场景）
     */
    public PageResult<CommentVO> getCommentPage(Long noteId, int pageNum, int pageSize) {
        pageSize = Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
        Page<Comment> page = new Page<>(Math.max(1, pageNum), pageSize);

        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<Comment>()
                .eq(Comment::getNoteId, noteId)
                .eq(Comment::getParentId, 0)
                .orderByDesc(Comment::getId);

        IPage<Comment> result = commentMapper.selectPage(page, wrapper);

        List<CommentVO> items = result.getRecords().stream()
                .map(this::toCommentVO)
                .collect(Collectors.toList());

        return PageResult.of(pageNum, pageSize, result.getTotal(), items);
    }

    // ==================== 私有方法 ====================

    /**
     * 【修复R1】发送评论计数事件到 MQ（SOCIAL_TOPIC:COMMENT/UNCOMMENT）
     * <p>
     * CounterEventConsumer 消费此事件，更新 counter 服务的笔记评论计数 (countType=3)。
     * 使用 asyncSend 避免阻塞评论发表事务的 afterCommit 回调。
     * </p>
     */
    private void sendCommentCounterEvent(Long noteId, String action, long count) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("noteId", noteId);
            if (count > 0) {
                event.put("count", count);
            }
            rocketMQTemplate.asyncSend(
                    "SOCIAL_TOPIC:" + action,
                    MqTraceHelper.wrapWithTraceContext(MessageBuilder.withPayload(event).build()),
                    new org.apache.rocketmq.client.producer.SendCallback() {
                        @Override
                        public void onSuccess(org.apache.rocketmq.client.producer.SendResult sendResult) {
                            log.debug("[评论计数] MQ发送成功: noteId={}, action={}, count={}", noteId, action, count);
                        }
                        @Override
                        public void onException(Throwable e) {
                            log.warn("[评论计数] MQ发送失败: noteId={}, action={}, count={}", noteId, action, count, e);
                        }
                    });
        } catch (Exception e) {
            // asyncSend 时序列化失败是代码级错误
            log.error("[评论计数] 事件序列化失败: noteId={}, action={}", noteId, action, e);
        }
    }

    /**
     * Comment → CommentVO
     */
    private CommentVO toCommentVO(Comment comment) {
        CommentVO vo = new CommentVO();
        vo.setId(comment.getId());
        vo.setNoteId(comment.getNoteId());
        vo.setUserId(comment.getUserId());
        vo.setParentId(comment.getParentId());
        vo.setReplyToId(comment.getReplyToId());
        vo.setContent(comment.getContent());
        vo.setLikeCount(comment.getLikeCount());
        vo.setCreatedAt(comment.getCreatedAt());
        return vo;
    }
}
