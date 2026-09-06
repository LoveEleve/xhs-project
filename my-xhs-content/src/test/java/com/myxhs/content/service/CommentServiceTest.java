package com.myxhs.content.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myxhs.common.cache.CacheHelper;
import com.myxhs.common.id.IdGeneratorUtil;
import com.myxhs.content.dto.request.CommentCreateRequest;
import com.myxhs.content.entity.Comment;
import com.myxhs.content.entity.Note;
import com.myxhs.content.enums.NoteStatus;
import com.myxhs.content.filter.DFAFilter;
import com.myxhs.content.mapper.CommentMapper;
import com.myxhs.content.mapper.NoteMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import com.myxhs.content.feign.UserFeignClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CommentService 单元测试
 * <p>
 * 测试策略：纯 Mockito Mock，不连接任何外部服务。
 * Mock 对象：CommentMapper, NoteMapper, DFAFilter, IdGeneratorUtil, CacheHelper
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommentServiceTest {

    @Mock
    private CommentMapper commentMapper;

    @Mock
    private NoteMapper noteMapper;

    @Mock
    private DFAFilter dfaFilter;

    @Mock
    private IdGeneratorUtil idGeneratorUtil;

    @Mock
    private CacheHelper cacheHelper;
    @Mock
    private RocketMQTemplate rocketMQTemplate;
    @Mock
    private UserFeignClient userFeignClient;

    private CommentService commentService;

    private static final Long USER_ID = 1001L;
    private static final Long NOTE_ID = 100L;
    private static final Long COMMENT_ID = 999L;
    private static final Long NOTE_AUTHOR_ID = 2002L;
    private static final String TEST_CONTENT = "这是一条测试评论";

    @BeforeEach
    void setUp() {
        commentService = new CommentService(
                commentMapper, noteMapper, dfaFilter,
                idGeneratorUtil, cacheHelper, rocketMQTemplate, userFeignClient);
    }

    // ==================== 发表评论 ====================

    @Test
    @DisplayName("发表评论 - 正常发表成功，验证 mapper.insert 被调用")
    void addCommentSuccess() {
        Note note = buildNote();
        CommentCreateRequest request = buildCreateRequest();

        when(noteMapper.selectById(NOTE_ID)).thenReturn(note);
        when(dfaFilter.detect(TEST_CONTENT)).thenReturn(Collections.emptySet());
        when(idGeneratorUtil.nextId()).thenReturn(COMMENT_ID);
        when(commentMapper.insert(any(Comment.class))).thenReturn(1);

        try (MockedStatic<TransactionSynchronizationManager> mockedTs =
                     mockStatic(TransactionSynchronizationManager.class)) {
            mockedTs.when(() -> TransactionSynchronizationManager.registerSynchronization(any()))
                    .thenAnswer(invocation -> null);

            Long result = commentService.createComment(USER_ID, request);

            assertThat(result).isEqualTo(COMMENT_ID);
            verify(commentMapper).insert(any(Comment.class));
        }
    }

    // ==================== 查询评论列表 ====================

    @Test
    @DisplayName("查询评论列表 - 游标分页返回正确的评论数据")
    void getCommentListSuccess() {
        Comment root1 = buildComment(1L, NOTE_ID, 0L);
        Comment root2 = buildComment(2L, NOTE_ID, 0L);
        Comment child1 = buildComment(3L, NOTE_ID, 1L);

        // 第一次调用 selectList 返回根评论，第二次返回子评论
        when(commentMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(root1, root2))
                .thenReturn(Collections.singletonList(child1));

        List<com.myxhs.content.dto.response.CommentVO> result =
                commentService.getCommentList(NOTE_ID, null, 10);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getId()).isEqualTo(1L);
        assertThat(result.get(1).getId()).isEqualTo(2L);
    }

    // ==================== 查询评论总数 ====================

    @Test
    @DisplayName("查询评论总数 - 返回正确的评论计数")
    void getCommentCountSuccess() {
        when(cacheHelper.getWithCacheAside(anyString(), any(), eq(5L), any(java.util.concurrent.TimeUnit.class)))
                .thenReturn(5L);

        long count = commentService.getCommentCount(NOTE_ID);

        assertThat(count).isEqualTo(5L);
    }

    // ==================== 删除评论 ====================

    @Test
    @DisplayName("删除评论 - 评论作者软删除成功，验证 deleteById 被调用")
    void deleteCommentSuccess() {
        Comment comment = buildComment(COMMENT_ID, NOTE_ID, 1L);
        Note note = buildNote();

        when(commentMapper.selectById(COMMENT_ID)).thenReturn(comment);
        when(noteMapper.selectById(NOTE_ID)).thenReturn(note);
        when(commentMapper.deleteById(COMMENT_ID)).thenReturn(1);

        try (MockedStatic<TransactionSynchronizationManager> mockedTs =
                     mockStatic(TransactionSynchronizationManager.class)) {
            mockedTs.when(() -> TransactionSynchronizationManager.registerSynchronization(any()))
                    .thenAnswer(invocation -> null);

            assertThatCode(() -> commentService.deleteComment(USER_ID, COMMENT_ID))
                    .doesNotThrowAnyException();

            verify(commentMapper).deleteById(COMMENT_ID);
        }
    }

    // ==================== 辅助方法 ====================

    private Note buildNote() {
        Note note = new Note();
        note.setId(NOTE_ID);
        note.setUserId(NOTE_AUTHOR_ID);
        note.setTitle("测试笔记标题");
        note.setContent("测试笔记内容");
        note.setStatus(NoteStatus.PUBLISHED.getCode());
        note.setCreatedAt(LocalDateTime.now());
        return note;
    }

    private Comment buildComment(Long id, Long noteId, Long parentId) {
        Comment comment = new Comment();
        comment.setId(id);
        comment.setNoteId(noteId);
        comment.setUserId(USER_ID);
        comment.setParentId(parentId);
        comment.setReplyToId(null);
        comment.setContent("测试评论内容-" + id);
        comment.setLikeCount(0);
        comment.setCreatedAt(LocalDateTime.now());
        return comment;
    }

    private CommentCreateRequest buildCreateRequest() {
        CommentCreateRequest request = new CommentCreateRequest();
        request.setNoteId(NOTE_ID);
        request.setParentId(0L);
        request.setContent(TEST_CONTENT);
        return request;
    }
}
