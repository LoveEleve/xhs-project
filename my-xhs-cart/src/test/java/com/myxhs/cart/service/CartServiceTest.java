package com.myxhs.cart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.myxhs.cart.dto.request.CartAddRequest;
import com.myxhs.cart.dto.request.CartUpdateQuantityRequest;
import com.myxhs.cart.dto.response.CartListVO;
import com.myxhs.cart.feign.ProductFeignClient;
import com.myxhs.cart.mapper.CartItemMapper;
import com.myxhs.common.exception.BizException;
import com.myxhs.common.response.R;
import com.myxhs.common.response.ResultCode;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CartService 单元测试
 * <p>
 * 测试策略：纯 Mockito Mock，不连接任何外部服务。
 * Mock 对象：StringRedisTemplate, RocketMQTemplate, ProductFeignClient,
 * DefaultRedisScript (cartAddScript/cartRemoveScript/cartCheckAllScript)
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CartServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private RocketMQTemplate rocketMQTemplate;

    @Mock
    private ProductFeignClient productFeignClient;

    @Mock
    private DefaultRedisScript<Long> cartAddScript;

    @Mock
    private DefaultRedisScript<Long> cartRemoveScript;

    @Mock
    private DefaultRedisScript<Long> cartCheckAllScript;

    @Mock
    private DefaultRedisScript<Long> cartUpdateQuantityScript;

    @Mock
    private DefaultRedisScript<Long> cartClearScript;

    @Mock
    private DefaultRedisScript<Long> cartMergeItemScript;

    @Mock
    private DefaultRedisScript<Long> cartCheckItemScript;
    @Mock
    private CartItemMapper cartItemMapper;

    private ObjectMapper objectMapper;
    private CartService cartService;

    private static final Long USER_ID = 1001L;
    private static final Long SKU_ID = 2001L;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        cartService = new CartService(
                stringRedisTemplate,
                rocketMQTemplate,
                productFeignClient,
                objectMapper,
                cartAddScript,
                cartItemMapper,
                cartRemoveScript,
                cartCheckAllScript,
                cartUpdateQuantityScript,
                cartClearScript,
                cartMergeItemScript,
                cartCheckItemScript
        );
    }

    // ==================== 加入购物车 ====================

    @Test
    @DisplayName("加入购物车 - 成功添加商品")
    void addToCartSuccess() {
        CartAddRequest request = new CartAddRequest();
        request.setSkuId(SKU_ID);
        request.setQuantity(2);

        ProductFeignClient.SkuDTO skuDTO = new ProductFeignClient.SkuDTO();
        skuDTO.setId(SKU_ID);
        skuDTO.setStatus(1);
        when(productFeignClient.getSkuDetail(SKU_ID)).thenReturn(R.ok(skuDTO));

        // Lua 脚本返回当前数量（表示成功）
        // 使用具体数量的 any() 匹配 varargs（5 个 String 参数）
        when(stringRedisTemplate.execute(eq(cartAddScript), anyList(), any(), any(), any(), any(), any()))
                .thenReturn(2L);

        assertThatCode(() -> cartService.addToCart(USER_ID, request))
                .doesNotThrowAnyException();

        verify(stringRedisTemplate).execute(
                eq(cartAddScript),
                argThat((List<String> keys) -> keys.size() == 3
                        && keys.get(0).contains("myxhs:cart:{" + USER_ID + "}:items")
                        && keys.get(1).contains(":checked")
                        && keys.get(2).contains(":sort")),
                eq(String.valueOf(SKU_ID)),
                eq("2"),
                anyString(),
                anyString(),
                anyString());
    }

    @Test
    @DisplayName("加入购物车 - 返回 null 时抛异常")
    void addToCartReturnsNull() {
        CartAddRequest request = new CartAddRequest();
        request.setSkuId(SKU_ID);
        request.setQuantity(1);

        ProductFeignClient.SkuDTO skuDTO = new ProductFeignClient.SkuDTO();
        skuDTO.setId(SKU_ID);
        skuDTO.setStatus(1);
        when(productFeignClient.getSkuDetail(SKU_ID)).thenReturn(R.ok(skuDTO));
        when(stringRedisTemplate.execute(eq(cartAddScript), anyList(), any(), any(), any(), any(), any()))
                .thenReturn(null);

        assertThatThrownBy(() -> cartService.addToCart(USER_ID, request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("购物车操作失败");
    }

    @Test
    @DisplayName("加入购物车 - 数量超限时抛异常")
    void addToCartLimitExceeded() {
        CartAddRequest request = new CartAddRequest();
        request.setSkuId(SKU_ID);
        request.setQuantity(1);

        ProductFeignClient.SkuDTO skuDTO = new ProductFeignClient.SkuDTO();
        skuDTO.setId(SKU_ID);
        skuDTO.setStatus(1);
        when(productFeignClient.getSkuDetail(SKU_ID)).thenReturn(R.ok(skuDTO));
        when(stringRedisTemplate.execute(eq(cartAddScript), anyList(), any(), any(), any(), any(), any()))
                .thenReturn(-1L);

        assertThatThrownBy(() -> cartService.addToCart(USER_ID, request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("购物车最多添加");
    }

    @Test
    @DisplayName("加入购物车 - 下架SKU被拒绝")
    void addToCartRejectsOffShelfSku() {
        CartAddRequest request = new CartAddRequest();
        request.setSkuId(SKU_ID);
        request.setQuantity(1);

        ProductFeignClient.SkuDTO skuDTO = new ProductFeignClient.SkuDTO();
        skuDTO.setId(SKU_ID);
        skuDTO.setStatus(0);
        when(productFeignClient.getSkuDetail(SKU_ID)).thenReturn(R.ok(skuDTO));

        assertThatThrownBy(() -> cartService.addToCart(USER_ID, request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("商品不存在或未上架");
        verify(stringRedisTemplate, never()).execute(any(), anyList(), any(), any(), any(), any(), any());
    }

    // ==================== 购物车列表 ====================

    @Test
    @DisplayName("购物车列表 - 成功获取商品列表")
    void getCartListSuccess() {
        // Mock Pipeline 返回数据
        Map<Object, Object> itemsMap = new HashMap<>();
        itemsMap.put("2001", "2");

        Set<Object> checkedSet = new HashSet<>();
        checkedSet.add("2001");

        DefaultTypedTuple<String> tuple = new DefaultTypedTuple<>("2001", 1700000000000D);
        Set<ZSetOperations.TypedTuple<String>> sortTuples = new HashSet<>();
        sortTuples.add(tuple);

        List<Object> pipelineResults = Arrays.asList(itemsMap, checkedSet, sortTuples);

        when(stringRedisTemplate.executePipelined(any(RedisCallback.class)))
                .thenReturn(pipelineResults);

        // Mock Feign 返回 SKU 详情
        ProductFeignClient.SkuDTO skuDTO = new ProductFeignClient.SkuDTO();
        skuDTO.setId(SKU_ID);
        skuDTO.setSpuId(3001L);
        skuDTO.setName("测试商品");
        skuDTO.setPrice(new BigDecimal("99.00"));
        skuDTO.setOriginalPrice(new BigDecimal("199.00"));
        skuDTO.setStock(100);
        skuDTO.setStatus(1);
        skuDTO.setSpuStatus(1);
        skuDTO.setSpecs("{\"颜色\":\"红色\"}");

        R<List<ProductFeignClient.SkuDTO>> r = R.ok(Collections.singletonList(skuDTO));
        when(productFeignClient.batchGetSkuDetails(anyList())).thenReturn(r);

        CartListVO result = cartService.getCartList(USER_ID);

        assertThat(result).isNotNull();
        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getSkuId()).isEqualTo(SKU_ID);
        assertThat(result.getItems().get(0).getName()).isEqualTo("测试商品");
        assertThat(result.getItems().get(0).getQuantity()).isEqualTo(2);
        assertThat(result.getItems().get(0).getChecked()).isTrue();
        assertThat(result.getTotalCount()).isEqualTo(1);
        assertThat(result.getAllChecked()).isTrue();
    }

    @Test
    @DisplayName("购物车列表 - 空购物车返回空列表")
    void getCartListEmpty() {
        List<Object> pipelineResults = Arrays.asList(
                Collections.emptyMap(),
                Collections.emptySet(),
                Collections.emptySet()
        );

        when(stringRedisTemplate.executePipelined(any(RedisCallback.class)))
                .thenReturn(pipelineResults);

        CartListVO result = cartService.getCartList(USER_ID);

        assertThat(result).isNotNull();
        assertThat(result.getItems()).isEmpty();
        assertThat(result.getTotalCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("购物车列表 - 商品下架时标记失效")
    void getCartListInvalidSku() {
        Map<Object, Object> itemsMap = new HashMap<>();
        itemsMap.put("2001", "1");

        List<Object> pipelineResults = Arrays.asList(
                itemsMap,
                Collections.emptySet(),
                Collections.emptySet()
        );

        when(stringRedisTemplate.executePipelined(any(RedisCallback.class)))
                .thenReturn(pipelineResults);

        ProductFeignClient.SkuDTO skuDTO = new ProductFeignClient.SkuDTO();
        skuDTO.setId(SKU_ID);
        skuDTO.setSpuId(3001L);
        skuDTO.setName("已下架商品");
        skuDTO.setPrice(new BigDecimal("99.00"));
        skuDTO.setStock(100);
        skuDTO.setStatus(2); // 下架状态

        R<List<ProductFeignClient.SkuDTO>> r = R.ok(Collections.singletonList(skuDTO));
        when(productFeignClient.batchGetSkuDetails(anyList())).thenReturn(r);

        CartListVO result = cartService.getCartList(USER_ID);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getValid()).isFalse();
        assertThat(result.getItems().get(0).getInvalidReason()).isEqualTo("商品已下架");
    }

    // ==================== 修改数量 ====================

    @Test
    @DisplayName("修改数量 - 成功更新商品数量")
    void updateQuantitySuccess() {
        CartUpdateQuantityRequest request = new CartUpdateQuantityRequest();
        request.setSkuId(SKU_ID);
        request.setQuantity(5);

        // 生产代码使用 cartUpdateQuantityScript Lua（HEXISTS+HSET 原子），stub execute 返回 1
        when(stringRedisTemplate.execute(eq(cartUpdateQuantityScript), anyList(), any(), any()))
                .thenReturn(1L);

        assertThatCode(() -> cartService.updateQuantity(USER_ID, request))
                .doesNotThrowAnyException();

        verify(stringRedisTemplate).execute(
                eq(cartUpdateQuantityScript),
                argThat((List<String> keys) -> keys.size() == 1 && keys.get(0).contains(":items")),
                eq(String.valueOf(SKU_ID)),
                eq("5"));
    }

    @Test
    @DisplayName("修改数量 - 商品不存在时抛异常")
    void updateQuantityItemNotFound() {
        CartUpdateQuantityRequest request = new CartUpdateQuantityRequest();
        request.setSkuId(SKU_ID);
        request.setQuantity(3);

        // Lua 返回 0 表示商品不在购物车（HEXISTS 检查失败）
        when(stringRedisTemplate.execute(eq(cartUpdateQuantityScript), anyList(), any(), any()))
                .thenReturn(0L);

        assertThatThrownBy(() -> cartService.updateQuantity(USER_ID, request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining(ResultCode.CART_ITEM_NOT_FOUND.getMessage());
    }

    // ==================== 删除商品 ====================

    @Test
    @DisplayName("删除商品 - 成功从购物车删除")
    void removeFromCartSuccess() {
        when(stringRedisTemplate.execute(eq(cartRemoveScript), anyList(), any()))
                .thenReturn(1L);

        assertThatCode(() -> cartService.removeFromCart(USER_ID, SKU_ID))
                .doesNotThrowAnyException();

        verify(stringRedisTemplate).execute(
                eq(cartRemoveScript),
                argThat((List<String> keys) -> keys.size() == 3
                        && keys.get(0).contains("myxhs:cart:{" + USER_ID + "}:items")
                        && keys.get(1).contains(":checked")
                        && keys.get(2).contains(":sort")),
                eq(String.valueOf(SKU_ID)));
    }

    // ==================== 清空购物车 ====================

    @Test
    @DisplayName("清空购物车 - 成功清空且验证 Redis DEL 被调用")
    void clearCartSuccess() {
        when(stringRedisTemplate.delete(anyCollection())).thenReturn(3L);

        org.springframework.data.redis.core.ValueOperations<String, String> valueOps = mock(org.springframework.data.redis.core.ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);

        assertThatCode(() -> cartService.clearCart(USER_ID))
                .doesNotThrowAnyException();

        verify(stringRedisTemplate).delete(argThat((Collection<String> keys) -> keys.size() == 3
                && keys.stream().allMatch(k -> k.contains("myxhs:cart:{" + USER_ID + "}")
                        && (k.contains(":items") || k.contains(":checked") || k.contains(":sort")))));
    }

    // ==================== 获取购物车数量 ====================

    @Test
    @DisplayName("获取购物车数量 - 返回品种数")
    void getCartCountSuccess() {
        org.springframework.data.redis.core.HashOperations<String, Object, Object> hashOps = mock(org.springframework.data.redis.core.HashOperations.class);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOps);
        when(hashOps.size(argThat(key -> key.contains(":items")))).thenReturn(3L);

        int count = cartService.getCartCount(USER_ID);

        assertThat(count).isEqualTo(3);
    }

    @Test
    @DisplayName("获取购物车数量 - 空购物车返回 0")
    void getCartCountEmpty() {
        org.springframework.data.redis.core.HashOperations<String, Object, Object> hashOps = mock(org.springframework.data.redis.core.HashOperations.class);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOps);
        when(hashOps.size(argThat(key -> key.contains(":items")))).thenReturn(0L);

        int count = cartService.getCartCount(USER_ID);

        assertThat(count).isEqualTo(0);
    }
}
