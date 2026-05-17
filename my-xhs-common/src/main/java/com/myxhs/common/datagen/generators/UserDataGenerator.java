package com.myxhs.common.datagen.generators;

import com.myxhs.common.datagen.BatchInsertExecutor;
import com.myxhs.common.datagen.DataGenerator;
import com.myxhs.common.datagen.RandomDataFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Timestamp;

/**
 * 用户数据生成器
 * <p>
 * 生成 t_user 表数据（标准量 1000 万）。
 * 数据分布：帕累托分布模拟真实用户活跃度。
 * </p>
 * <p>
 * 依赖：无（Step 1 基础数据）
 * </p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "myxhs.datagen.enabled", havingValue = "true", matchIfMissing = false)
public class UserDataGenerator implements DataGenerator {

    /** 标准数据量：1000 万用户 */
    private static final long BASE_COUNT = 10_000_000;

    @Override
    public String name() {
        return "user";
    }

    @Override
    public int order() {
        return 10; // Step 1：无依赖
    }

    @Override
    public void generate(DataSource dataSource, double scale) {
        long totalCount = (long) (BASE_COUNT * scale);
        if (totalCount < 1) totalCount = 1;

        String sql = "INSERT INTO t_user (id, username, password, nickname, avatar, gender, birthday, phone, email, signature, status, deleted, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE id=id"; // 幂等：重复执行不报错

        BatchInsertExecutor executor = new BatchInsertExecutor(dataSource);
        executor.execute("t_user", totalCount, sql, (ps, id) -> {
            try {
                Timestamp now = Timestamp.valueOf(RandomDataFactory.randomDateTime());
                java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
                ps.setLong(1, id);
                ps.setString(2, "user_" + id); // username
                ps.setString(3, "$2a$10$mockPasswordHashForStressTest000000000000000"); // BCrypt mock
                ps.setString(4, RandomDataFactory.chineseName()); // nickname
                ps.setString(5, RandomDataFactory.avatar(id)); // avatar
                ps.setInt(6, r.nextInt(3)); // gender: 0=未知, 1=男, 2=女
                ps.setDate(7, java.sql.Date.valueOf(
                        java.time.LocalDate.of(1980 + r.nextInt(30), 1 + r.nextInt(12), 1 + r.nextInt(28)))); // birthday
                ps.setString(8, RandomDataFactory.phone()); // phone
                ps.setString(9, RandomDataFactory.email(id)); // email
                ps.setString(10, "这是用户 " + id + " 的个性签名"); // signature
                ps.setInt(11, 1); // status=1 正常
                ps.setInt(12, 0); // deleted=0
                ps.setTimestamp(13, now); // created_at
                ps.setTimestamp(14, now); // updated_at
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
