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
 * 商品数据生成器
 * <p>
 * 生成 t_spu + t_sku 表数据。
 * 标准量：100 万 SPU + 1000 万 SKU（平均每 SPU 10 个 SKU）。
 * </p>
 * <p>
 * 依赖：无（Step 1 基础数据）
 * </p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "myxhs.datagen.enabled", havingValue = "true", matchIfMissing = false)
public class ProductDataGenerator implements DataGenerator {

    private static final long SPU_BASE_COUNT = 1_000_000;
    private static final long SKU_BASE_COUNT = 10_000_000;

    private static final String[] CATEGORIES = {
            "服饰", "美妆", "数码", "家居", "食品", "运动", "母婴", "图书", "宠物", "文具"
    };

    private static final String[] SKU_SPECS = {
            "S码/白色", "M码/白色", "L码/白色", "XL码/白色",
            "S码/黑色", "M码/黑色", "L码/黑色", "XL码/黑色",
            "标准版", "豪华版", "100g", "200g", "500g",
            "红色", "蓝色", "绿色", "黄色", "紫色"
    };

    @Override
    public String name() {
        return "product";
    }

    @Override
    public int order() {
        return 10; // Step 1：无依赖
    }

    @Override
    public void generate(DataSource dataSource, double scale) {
        generateSpu(dataSource, scale);
        generateSku(dataSource, scale);
    }

    private void generateSpu(DataSource dataSource, double scale) {
        long totalCount = (long) (SPU_BASE_COUNT * scale);
        if (totalCount < 1) totalCount = 1;

        String sql = "INSERT INTO t_spu (id, name, category, description, main_image, status, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE id=id";

        BatchInsertExecutor executor = new BatchInsertExecutor(dataSource);
        executor.execute("t_spu", totalCount, sql, (ps, id) -> {
            try {
                ThreadLocalRandom r = ThreadLocalRandom.current();
                Timestamp now = Timestamp.valueOf(RandomDataFactory.randomDateTime());
                ps.setLong(1, id);
                ps.setString(2, RandomDataFactory.productName());
                ps.setString(3, CATEGORIES[r.nextInt(CATEGORIES.length)]);
                ps.setString(4, "商品描述 #" + id);
                ps.setString(5, RandomDataFactory.imageUrl(id));
                ps.setInt(6, r.nextDouble() < 0.95 ? 1 : 0); // 95% 上架
                ps.setTimestamp(7, now);
                ps.setTimestamp(8, now);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void generateSku(DataSource dataSource, double scale) {
        long spuCount = (long) (SPU_BASE_COUNT * scale);
        long totalCount = (long) (SKU_BASE_COUNT * scale);
        if (totalCount < 1) totalCount = 1;
        if (spuCount < 1) spuCount = 1;

        final long finalSpuCount = spuCount;
        String sql = "INSERT INTO t_sku (id, spu_id, name, spec, price, stock, status, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE id=id";

        BatchInsertExecutor executor = new BatchInsertExecutor(dataSource);
        executor.execute("t_sku", totalCount, sql, (ps, id) -> {
            try {
                ThreadLocalRandom r = ThreadLocalRandom.current();
                Timestamp now = Timestamp.valueOf(RandomDataFactory.randomDateTime());
                long spuId = ((id - 1) / 10) + 1; // 每 10 个 SKU 对应 1 个 SPU
                if (spuId > finalSpuCount) spuId = r.nextLong(1, finalSpuCount + 1);

                ps.setLong(1, id);
                ps.setLong(2, spuId);
                ps.setString(3, RandomDataFactory.productName());
                ps.setString(4, SKU_SPECS[r.nextInt(SKU_SPECS.length)]);
                ps.setDouble(5, RandomDataFactory.price());
                ps.setInt(6, RandomDataFactory.stock());
                ps.setInt(7, 1); // status=1 上架
                ps.setTimestamp(8, now);
                ps.setTimestamp(9, now);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
