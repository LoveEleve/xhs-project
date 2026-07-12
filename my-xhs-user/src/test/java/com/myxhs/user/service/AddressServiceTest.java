package com.myxhs.user.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.myxhs.common.cache.RedisOperator;
import com.myxhs.user.dto.request.AddressCreateRequest;
import com.myxhs.user.dto.response.AddressVO;
import com.myxhs.user.entity.UserAddress;
import com.myxhs.user.mapper.UserAddressMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 收货地址服务单元测试
 * <p>
 * 测试策略：纯 Mockito Mock，不连接任何外部服务。
 * Mock 对象：UserAddressMapper, RedisOperator, RedissonClient
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AddressServiceTest {

    @Mock
    private UserAddressMapper userAddressMapper;
    @Mock
    private RedisOperator redisOperator;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock rLock;

    private UserAddressService userAddressService;

    private static final Long USER_ID = 1001L;
    private static final Long ADDRESS_ID = 2001L;

    @BeforeEach
    void setUp() throws Exception {
        // 初始化 MyBatis-Plus 表元数据（LambdaUpdateWrapper 等需要）
        initMybatisPlusTableInfo(UserAddress.class);

        userAddressService = new UserAddressService(
                userAddressMapper, redisOperator, redissonClient
        );

        // 设置 @Value 字段（不使用 Spring Context 时需手动设置）
        Field limitField = UserAddressService.class.getDeclaredField("addressLimit");
        limitField.setAccessible(true);
        limitField.set(userAddressService, 20);
    }

    // ==================== 查询地址列表 ====================

    @Test
    @DisplayName("查询地址列表 - 返回正确的地址列表")
    void listAddressesSuccess() {
        // Given
        UserAddress address = buildAddress();
        when(userAddressMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(address));

        // When
        List<AddressVO> result = userAddressService.listAddresses(USER_ID);

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(ADDRESS_ID);
        assertThat(result.get(0).getReceiverName()).isEqualTo("张三");
        assertThat(result.get(0).getProvince()).isEqualTo("广东省");
        assertThat(result.get(0).getCity()).isEqualTo("深圳市");
    }

    // ==================== 新增地址 ====================

    @Test
    @DisplayName("新增地址 - 创建成功，验证 mapper.insert 调用")
    void addAddressSuccess() throws InterruptedException {
        // Given
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        doNothing().when(rLock).unlock();

        when(userAddressMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(userAddressMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        doAnswer(invocation -> {
            UserAddress addr = invocation.getArgument(0);
            addr.setId(ADDRESS_ID);
            return 1;
        }).when(userAddressMapper).insert(any(UserAddress.class));

        doNothing().when(redisOperator).set(anyString(), any(), anyLong(), any(TimeUnit.class));

        AddressCreateRequest request = buildCreateRequest();

        // When
        AddressVO result = userAddressService.createAddress(USER_ID, request);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getReceiverName()).isEqualTo("张三");
        assertThat(result.getIsDefault()).isEqualTo(1);
        verify(userAddressMapper).insert(any(UserAddress.class));
    }

    // ==================== 删除地址 ====================

    @Test
    @DisplayName("删除地址 - 逻辑删除，验证 deleted=1")
    void deleteAddressSuccess() {
        // Given
        UserAddress address = buildAddress();
        address.setIsDefault(0);
        when(userAddressMapper.selectById(ADDRESS_ID)).thenReturn(address);
        when(userAddressMapper.deleteById(ADDRESS_ID)).thenReturn(1);
        when(redisOperator.delete(anyString())).thenReturn(true);

        // When
        assertThatCode(() -> userAddressService.deleteAddress(USER_ID, ADDRESS_ID))
                .doesNotThrowAnyException();

        // Then: 验证逻辑删除被调用
        verify(userAddressMapper).deleteById(ADDRESS_ID);
    }

    // ==================== 设置默认地址 ====================

    @Test
    @DisplayName("设置默认地址 - setDefault 成功")
    void setDefaultAddressSuccess() {
        // Given
        UserAddress address = buildAddress();
        address.setIsDefault(0);
        when(userAddressMapper.selectById(ADDRESS_ID)).thenReturn(address);
        when(userAddressMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(userAddressMapper.updateById(any(UserAddress.class))).thenReturn(1);
        doNothing().when(redisOperator).set(anyString(), any(), anyLong(), any(TimeUnit.class));

        // When
        assertThatCode(() -> userAddressService.setDefaultAddress(USER_ID, ADDRESS_ID))
                .doesNotThrowAnyException();

        // Then
        verify(userAddressMapper).updateById(any(UserAddress.class));
    }

    // ==================== 辅助方法 ====================

    private UserAddress buildAddress() {
        UserAddress address = new UserAddress();
        address.setId(ADDRESS_ID);
        address.setUserId(USER_ID);
        address.setReceiverName("张三");
        address.setReceiverPhone("13800138000");
        address.setProvince("广东省");
        address.setCity("深圳市");
        address.setDistrict("南山区");
        address.setDetailAddress("科技园路1号");
        address.setIsDefault(1);
        address.setCreatedAt(LocalDateTime.of(2025, 1, 1, 0, 0));
        return address;
    }

    private AddressCreateRequest buildCreateRequest() {
        AddressCreateRequest request = new AddressCreateRequest();
        request.setReceiverName("张三");
        request.setReceiverPhone("13800138000");
        request.setProvince("广东省");
        request.setCity("深圳市");
        request.setDistrict("南山区");
        request.setDetailAddress("科技园路1号");
        request.setIsDefault(true);
        return request;
    }

    /**
     * 初始化 MyBatis-Plus 表元数据（LambdaUpdateWrapper 等需要）
     */
    private static void initMybatisPlusTableInfo(Class<?> entityClass) {
        try {
            MybatisConfiguration config = new MybatisConfiguration();
            MapperBuilderAssistant assistant = new MapperBuilderAssistant(config, "");
            TableInfoHelper.initTableInfo(assistant, entityClass);
        } catch (Exception ignored) {
            // 已初始化则忽略
        }
    }
}
