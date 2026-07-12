package com.myxhs.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myxhs.common.cache.RedisOperator;
import com.myxhs.product.dto.response.CategoryTreeVO;
import com.myxhs.product.entity.Category;
import com.myxhs.product.mapper.CategoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CategoryServiceTest {

    @Mock
    private CategoryMapper categoryMapper;
    @Mock
    private RedisOperator redisOperator;

    private CategoryService categoryService;

    @BeforeEach
    void setUp() {
        categoryService = new CategoryService(categoryMapper, redisOperator);
    }

    @Test
    @DisplayName("获取分类树成功-层级结构")
    void getCategoryTreeSuccess() {
        when(redisOperator.get("myxhs:product:category:tree")).thenReturn(null);

        Category parent = new Category();
        parent.setId(1L);
        parent.setName("服装");
        parent.setParentId(0L);
        parent.setLevel(1);
        parent.setSort(1);
        parent.setIcon("icon1");
        parent.setStatus(1);

        Category child = new Category();
        child.setId(2L);
        child.setName("女装");
        child.setParentId(1L);
        child.setLevel(2);
        child.setSort(1);
        child.setIcon("icon2");
        child.setStatus(1);

        when(categoryMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(parent, child));

        List<CategoryTreeVO> tree = categoryService.getCategoryTree();

        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).getName()).isEqualTo("服装");
        assertThat(tree.get(0).getChildren()).hasSize(1);
        assertThat(tree.get(0).getChildren().get(0).getName()).isEqualTo("女装");

        verify(redisOperator).set(eq("myxhs:product:category:tree"), any(), eq(2L), eq(TimeUnit.HOURS));
    }

    @Test
    @DisplayName("获取分类树成功-单分类")
    void getCategorySuccess() {
        when(redisOperator.get("myxhs:product:category:tree")).thenReturn(null);

        Category cat = new Category();
        cat.setId(1L);
        cat.setName("数码");
        cat.setParentId(0L);
        cat.setLevel(1);
        cat.setSort(1);
        cat.setIcon("icon");
        cat.setStatus(1);

        when(categoryMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(cat));

        List<CategoryTreeVO> tree = categoryService.getCategoryTree();

        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).getId()).isEqualTo(1L);
        assertThat(tree.get(0).getName()).isEqualTo("数码");
        assertThat(tree.get(0).getChildren()).isEmpty();
    }
}
