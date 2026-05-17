package com.myxhs.common.datagen.generators;

import com.myxhs.common.datagen.BatchInsertExecutor;
import com.myxhs.common.datagen.DataGenerator;
import com.myxhs.common.datagen.RandomDataFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 笔记数据生成器
 * <p>
 * 生成 t_note 表数据（标准量 5000 万）。
 * 数据分布：帕累托分布（1% 头部用户产生 20% 内容）。
 * </p>
 * <p>
 * 依赖：t_user（Step 1）
 * </p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "myxhs.datagen.enabled", havingValue = "true", matchIfMissing = false)
public class NoteDataGenerator implements DataGenerator {

    /** 标准数据量：5000 万笔记 */
    private static final long BASE_COUNT = 50_000_000;
    /** 用户标准量 */
    private static final long USER_BASE_COUNT = 10_000_000;

    private static final int[] NOTE_TYPES = {0, 1, 2}; // 0=图文, 1=视频, 2=短文
    private static final int[] NOTE_STATUS = {1, 1, 1, 1, 1, 1, 1, 1, 1, 2}; // 90% 已发布, 10% 审核中

    @Override
    public String name() {
        return "note";
    }

    @Override
    public int order() {
        return 20; // Step 2：依赖 user
    }

    @Override
    public void generate(DataSource dataSource, double scale) {
        long totalCount = (long) (BASE_COUNT * scale);
        long userCount = (long) (USER_BASE_COUNT * scale);
        if (totalCount < 1) totalCount = 1;
        if (userCount < 1) userCount = 1;

        final long finalUserCount = userCount;

        String sql = "INSERT INTO t_note (id, user_id, title, content, cover_image, note_type, status, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE id=id";

        BatchInsertExecutor executor = new BatchInsertExecutor(dataSource);
        executor.execute("t_note", totalCount, sql, (ps, id) -> {
            try {
                ThreadLocalRandom r = ThreadLocalRandom.current();
                // 帕累托分布：头部用户产生更多内容
                long userId = RandomDataFactory.paretoId(finalUserCount, 1.5);
                Timestamp createdAt = Timestamp.valueOf(RandomDataFactory.randomDateTime());

                ps.setLong(1, id);
                ps.setLong(2, userId);
                ps.setString(3, RandomDataFactory.noteTitle());
                ps.setString(4, "这是笔记 #" + id + " 的内容，由用户 " + userId + " 发布。");
                ps.setString(5, RandomDataFactory.imageUrl(id));
                ps.setInt(6, NOTE_TYPES[r.nextInt(NOTE_TYPES.length)]);
                ps.setInt(7, NOTE_STATUS[r.nextInt(NOTE_STATUS.length)]);
                ps.setTimestamp(8, createdAt);
                ps.setTimestamp(9, createdAt);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
