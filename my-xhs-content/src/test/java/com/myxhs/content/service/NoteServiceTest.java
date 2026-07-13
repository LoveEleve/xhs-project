package com.myxhs.content.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.myxhs.common.cache.CacheHelper;
import com.myxhs.common.exception.BizException;
import com.myxhs.common.id.IdGeneratorUtil;
import com.myxhs.common.metrics.BusinessMetrics;
import com.myxhs.common.response.ResultCode;
import com.myxhs.content.dto.request.NotePublishRequest;
import com.myxhs.content.dto.response.NoteDetailVO;
import com.myxhs.content.entity.Note;
import com.myxhs.content.entity.LocalMessage;
import com.myxhs.content.enums.NoteStatus;
import com.myxhs.content.filter.DFAFilter;
import com.myxhs.content.mapper.CommentMapper;
import com.myxhs.content.mapper.LocalMessageMapper;
import com.myxhs.content.mapper.NoteMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.*;

/**
 * NoteService 单元测试
 * <p>
 * 测试策略：纯 Mockito Mock，不连接任何外部服务。
 * Mock 对象：NoteMapper, LocalMessageMapper, DFAFilter, IdGeneratorUtil,
 * CacheHelper, RocketMQTemplate, BusinessMetrics, StringRedisTemplate, CommentMapper
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NoteServiceTest {

    @Mock
    private NoteMapper noteMapper;
    @Mock
    private LocalMessageMapper localMessageMapper;
    @Mock
    private DFAFilter dfaFilter;
    @Mock
    private IdGeneratorUtil idGeneratorUtil;
    @Mock
    private CacheHelper cacheHelper;
    @Mock
    private RocketMQTemplate rocketMQTemplate;
    @Mock
    private BusinessMetrics businessMetrics;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private CommentMapper commentMapper;

    private ObjectMapper objectMapper;
    private NoteService noteService;

    private static final Long USER_ID = 1001L;
    private static final Long NOTE_ID = 10001L;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        noteService = new NoteService(
                noteMapper, localMessageMapper, dfaFilter, idGeneratorUtil,
                cacheHelper, objectMapper, rocketMQTemplate, businessMetrics
        );
    }

    // ==================== 发布笔记 ====================

    @Test
    @DisplayName("发布笔记 - 正常发布成功，返回笔记ID")
    void publishNoteSuccess() {
        try (MockedStatic<TransactionSynchronizationManager> mockedTs =
                     mockStatic(TransactionSynchronizationManager.class)) {
            mockedTs.when(() -> TransactionSynchronizationManager.registerSynchronization(any()))
                    .thenAnswer(invocation -> null);

            when(dfaFilter.detect(anyString())).thenReturn(Collections.emptySet());
            when(idGeneratorUtil.nextId()).thenReturn(NOTE_ID);

            doAnswer(invocation -> { Note n = invocation.getArgument(0); n.setId(NOTE_ID); return 1; })
                .when(noteMapper).insert(any(Note.class));
            doAnswer(invocation -> { LocalMessage lm = invocation.getArgument(0); lm.setId(2L); return 1; })
                .when(localMessageMapper).insert(any(LocalMessage.class));

            NotePublishRequest request = buildPublishRequest("测试标题", "这是一篇测试笔记内容");
            Long noteId = noteService.publishNote(USER_ID, request);

            assertThat(noteId).isEqualTo(NOTE_ID);
        }
    }

    @Test
    @DisplayName("发布笔记 - 验证 noteMapper.insert() 被正确调用")
    void publishNoteSavesToDatabase() {
        try (MockedStatic<TransactionSynchronizationManager> mockedTs =
                     mockStatic(TransactionSynchronizationManager.class)) {
            mockedTs.when(() -> TransactionSynchronizationManager.registerSynchronization(any()))
                    .thenAnswer(invocation -> null);

            when(dfaFilter.detect(anyString())).thenReturn(Collections.emptySet());
            when(idGeneratorUtil.nextId()).thenReturn(NOTE_ID);

            doAnswer(invocation -> { Note n = invocation.getArgument(0); n.setId(NOTE_ID); return 1; })
                .when(noteMapper).insert(any(Note.class));
            doAnswer(invocation -> { LocalMessage lm = invocation.getArgument(0); lm.setId(2L); return 1; })
                .when(localMessageMapper).insert(any(LocalMessage.class));

            NotePublishRequest request = buildPublishRequest("测试标题", "测试内容");
            noteService.publishNote(USER_ID, request);

            ArgumentCaptor<Note> noteCaptor = ArgumentCaptor.forClass(Note.class);
            verify(noteMapper).insert(noteCaptor.capture());

            Note captured = noteCaptor.getValue();
            assertThat(captured.getId()).isEqualTo(NOTE_ID);
            assertThat(captured.getUserId()).isEqualTo(USER_ID);
            assertThat(captured.getTitle()).isEqualTo("测试标题");
            assertThat(captured.getContent()).isEqualTo("测试内容");
            assertThat(captured.getStatus()).isEqualTo(NoteStatus.PUBLISHED.getCode());
        }
    }

    // ==================== 查询笔记详情 ====================

    @Test
    @DisplayName("查询笔记详情 - 正常查询返回正确笔记数据")
    void getNoteDetailSuccess() {
        Note note = buildNote(NOTE_ID, USER_ID, "测试标题", "测试内容", NoteStatus.PUBLISHED.getCode());

        when(cacheHelper.getWithCacheAside(anyString(), ArgumentMatchers.<Supplier<Note>>any(), anyLong(), any(TimeUnit.class)))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Supplier<Note> supplier = invocation.getArgument(1);
                    return supplier.get();
                });
        when(noteMapper.selectById(NOTE_ID)).thenReturn(note);

        NoteDetailVO vo = noteService.getNoteDetail(NOTE_ID);

        assertThat(vo).isNotNull();
        assertThat(vo.getId()).isEqualTo(NOTE_ID);
        assertThat(vo.getUserId()).isEqualTo(USER_ID);
        assertThat(vo.getTitle()).isEqualTo("测试标题");
        assertThat(vo.getContent()).isEqualTo("测试内容");
        assertThat(vo.getStatus()).isEqualTo(NoteStatus.PUBLISHED.getCode());
    }

    @Test
    @DisplayName("查询笔记详情 - 笔记不存在抛出 BizException")
    void getNoteDetailNotFound() {
        when(cacheHelper.getWithCacheAside(anyString(), ArgumentMatchers.<Supplier<Note>>any(), anyLong(), any(TimeUnit.class)))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Supplier<Note> supplier = invocation.getArgument(1);
                    return supplier.get();
                });
        when(noteMapper.selectById(NOTE_ID)).thenReturn(null);

        assertThatThrownBy(() -> noteService.getNoteDetail(NOTE_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining(ResultCode.NOTE_NOT_FOUND.getMessage());
    }

    // ==================== 删除笔记 ====================

    @Test
    @DisplayName("删除笔记 - 逻辑删除成功，验证 deleteById 被调用")
    void deleteNoteSuccess() {
        try (MockedStatic<TransactionSynchronizationManager> mockedTs =
                     mockStatic(TransactionSynchronizationManager.class)) {
            mockedTs.when(() -> TransactionSynchronizationManager.registerSynchronization(any()))
                    .thenAnswer(invocation -> null);

            Note note = buildNote(NOTE_ID, USER_ID, "待删除笔记", "内容", NoteStatus.PUBLISHED.getCode());
            when(noteMapper.selectById(NOTE_ID)).thenReturn(note);
            when(noteMapper.deleteById(NOTE_ID)).thenReturn(1);

            noteService.deleteNote(USER_ID, NOTE_ID);

            verify(noteMapper).selectById(NOTE_ID);
            verify(noteMapper).deleteById(NOTE_ID);
        }
    }

    // ==================== 辅助方法 ====================

    private Note buildNote(Long id, Long userId, String title, String content, Integer status) {
        Note note = new Note();
        note.setId(id);
        note.setUserId(userId);
        note.setTitle(title);
        note.setContent(content);
        note.setStatus(status);
        note.setNoteType(0);
        return note;
    }

    private NotePublishRequest buildPublishRequest(String title, String content) {
        NotePublishRequest request = new NotePublishRequest();
        request.setTitle(title);
        request.setContent(content);
        request.setImages(Arrays.asList("https://img.example.com/1.jpg", "https://img.example.com/2.jpg"));
        request.setCoverUrl("https://img.example.com/cover.jpg");
        request.setTopicIds(Arrays.asList(1L, 2L));
        request.setTags(Arrays.asList("旅行", "美食"));
        request.setNoteType(0);
        return request;
    }
}
