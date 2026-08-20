package com.myxhs.home.feign.fallback;

import com.myxhs.common.response.R;
import com.myxhs.common.response.ResultCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HomeFeignFallbackFactoryTest {

    @Test
    void productFallbackReturnsServiceUnavailable() {
        R<java.util.Map<String, Object>> result = new ProductFeignFallbackFactory()
                .create(new RuntimeException("downstream down"))
                .getSpuDetail(1L);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getCode()).isEqualTo(ResultCode.SERVICE_UNAVAILABLE.getCode());
        assertThat(result.getMessage()).contains("商品服务不可用");
    }

    @Test
    void userFallbackReturnsServiceUnavailable() {
        R<java.util.Map<String, Object>> result = new UserFeignFallbackFactory()
                .create(new RuntimeException("downstream down"))
                .getUserPublicInfo(1L);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getCode()).isEqualTo(ResultCode.SERVICE_UNAVAILABLE.getCode());
        assertThat(result.getMessage()).contains("用户服务不可用");
    }

    @Test
    void cartFallbackReturnsServiceUnavailable() {
        R<java.util.Map<String, Object>> listResult = new CartFeignFallbackFactory()
                .create(new RuntimeException("downstream down"))
                .getCartList(1L);
        R<java.util.Map<String, Integer>> countResult = new CartFeignFallbackFactory()
                .create(new RuntimeException("downstream down"))
                .getCartCount(1L);

        assertThat(listResult.isSuccess()).isFalse();
        assertThat(listResult.getCode()).isEqualTo(ResultCode.SERVICE_UNAVAILABLE.getCode());
        assertThat(listResult.getMessage()).contains("购物车服务不可用");
        assertThat(countResult.isSuccess()).isFalse();
        assertThat(countResult.getCode()).isEqualTo(ResultCode.SERVICE_UNAVAILABLE.getCode());
    }

    @Test
    void contentPrimaryFallbackReturnsServiceUnavailable() {
        ContentFeignFallbackFactory factory = new ContentFeignFallbackFactory();
        R<java.util.Map<String, Object>> detailResult = factory.create(new RuntimeException("downstream down"))
                .getNoteDetail(1L);
        R<java.util.Map<String, Object>> batchResult = factory.create(new RuntimeException("downstream down"))
                .batchGetNoteDetail(java.util.List.of(1L, 2L));

        assertThat(detailResult.isSuccess()).isFalse();
        assertThat(detailResult.getCode()).isEqualTo(ResultCode.SERVICE_UNAVAILABLE.getCode());
        assertThat(detailResult.getMessage()).contains("内容服务不可用");
        assertThat(batchResult.isSuccess()).isFalse();
        assertThat(batchResult.getCode()).isEqualTo(ResultCode.SERVICE_UNAVAILABLE.getCode());
    }
}

