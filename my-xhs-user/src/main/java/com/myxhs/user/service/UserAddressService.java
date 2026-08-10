package com.myxhs.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.myxhs.common.cache.RedisOperator;
import com.myxhs.common.constants.RedisKeyConstants;
import com.myxhs.common.exception.BizException;
import com.myxhs.common.response.ResultCode;
import com.myxhs.user.dto.request.AddressCreateRequest;
import com.myxhs.user.dto.request.AddressUpdateRequest;
import com.myxhs.user.dto.response.AddressVO;
import com.myxhs.user.entity.UserAddress;
import com.myxhs.user.mapper.UserAddressMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 收货地址服务
 * <p>
 * 核心业务：地址CRUD、默认地址管理（唯一默认+事务保证）、地址数量上限（20条）、手机号脱敏。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAddressService {

    private final UserAddressMapper userAddressMapper;
    private final RedisOperator redisOperator;
    private final RedissonClient redissonClient;

    /** 每用户地址数量上限 */
    @Value("${app.address.limit:20}")
    private int addressLimit;

    // ==================== 新增地址 ====================

    /**
     * 新增收货地址
     * <p>
     * 1. 检查地址数量上限（20条）
     * 2. 如果是第一条地址，自动设为默认
     * 3. 如果设置为默认，先取消旧默认
     * 4. 插入地址记录
     * 5. 更新默认地址缓存
     * </p>
     */
    @Transactional(rollbackFor = Exception.class)
    public AddressVO createAddress(Long userId, AddressCreateRequest request) {
        // 1. 分布式锁防止并发创建超过上限
        String lockKey = RedisKeyConstants.USER_ADDRESS_LOCK + userId;
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(3, 10, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new BizException(ResultCode.LOCK_ACQUIRE_FAIL);
            }

            // 2. 检查地址数量上限（锁内检查，防止并发超限）
            Long count = userAddressMapper.selectCount(
                    new LambdaQueryWrapper<UserAddress>().eq(UserAddress::getUserId, userId)
            );
            if (count >= addressLimit) {
                throw new BizException(ResultCode.ADDRESS_LIMIT_EXCEEDED);
            }

            // 3. 构建实体
            UserAddress address = new UserAddress();
            address.setUserId(userId);
            address.setReceiverName(request.getReceiverName());
            address.setReceiverPhone(request.getReceiverPhone());
            address.setProvince(request.getProvince());
            address.setCity(request.getCity());
            address.setDistrict(request.getDistrict());
            address.setDetailAddress(request.getDetailAddress());

            // 4. 判断是否设为默认地址
            boolean setDefault = Boolean.TRUE.equals(request.getIsDefault());
            if (count == 0) {
                setDefault = true;
            }

            if (setDefault) {
                cancelDefaultAddress(userId);
                address.setIsDefault(1);
            } else {
                address.setIsDefault(0);
            }

            // 5. 插入
            userAddressMapper.insert(address);
            log.info("[收货地址] 新增成功, userId={}, addressId={}, isDefault={}", userId, address.getId(), address.getIsDefault());

            // 6. 更新默认地址缓存
            if (setDefault) {
                updateDefaultAddressCache(userId, address.getId());
            }

            return toAddressVO(address);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ResultCode.LOCK_ACQUIRE_FAIL);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    // ==================== 更新地址 ====================

    /**
     * 更新收货地址
     * <p>
     * 1. 校验地址归属
     * 2. 如果设置为默认，先取消旧默认
     * 3. 更新非null字段
     * 4. 更新默认地址缓存
     * </p>
     */
    @Transactional(rollbackFor = Exception.class)
    public AddressVO updateAddress(Long userId, Long addressId, AddressUpdateRequest request) {
        // 1. 校验地址归属
        UserAddress address = getAndVerifyOwnership(userId, addressId);

        // 2. 分布式锁（与 createAddress 共用锁 key，保证地址操作的串行化）
        String lockKey = RedisKeyConstants.USER_ADDRESS_LOCK + userId;
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(3, 10, TimeUnit.SECONDS)) {
                throw new BizException(ResultCode.LOCK_ACQUIRE_FAIL);
            }

            // 3. 构建更新条件（同时加 userId 和 addressId 条件，双重校验防越权）
            LambdaUpdateWrapper<UserAddress> updateWrapper = new LambdaUpdateWrapper<>();
            updateWrapper.eq(UserAddress::getId, addressId)
                         .eq(UserAddress::getUserId, userId);

            boolean hasUpdate = false;
            if (request.getReceiverName() != null) { updateWrapper.set(UserAddress::getReceiverName, request.getReceiverName()); hasUpdate = true; }
            if (request.getReceiverPhone() != null) { updateWrapper.set(UserAddress::getReceiverPhone, request.getReceiverPhone()); hasUpdate = true; }
            if (request.getProvince() != null) { updateWrapper.set(UserAddress::getProvince, request.getProvince()); hasUpdate = true; }
            if (request.getCity() != null) { updateWrapper.set(UserAddress::getCity, request.getCity()); hasUpdate = true; }
            if (request.getDistrict() != null) { updateWrapper.set(UserAddress::getDistrict, request.getDistrict()); hasUpdate = true; }
            if (request.getDetailAddress() != null) { updateWrapper.set(UserAddress::getDetailAddress, request.getDetailAddress()); hasUpdate = true; }

            // 4. 处理默认地址切换
            if (Boolean.TRUE.equals(request.getIsDefault()) && address.getIsDefault() == 0) {
                cancelDefaultAddress(userId);
                updateWrapper.set(UserAddress::getIsDefault, 1);
                hasUpdate = true;
                updateDefaultAddressCache(userId, addressId);
            }

            // 5. 执行更新（至少有一个字段需要更新时才执行）
            if (hasUpdate) {
                userAddressMapper.update(null, updateWrapper);
                log.info("[收货地址] 更新成功, userId={}, addressId={}", userId, addressId);
            } else {
                log.debug("[收货地址] 无字段需要更新, userId={}, addressId={}", userId, addressId);
            }

            // 6. 查询最新数据返回
            UserAddress updated = userAddressMapper.selectById(addressId);
            return toAddressVO(updated);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ResultCode.LOCK_ACQUIRE_FAIL);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    // ==================== 删除地址 ====================

    /**
     * 删除收货地址（逻辑删除）
     * <p>
     * 1. 校验地址归属
     * 2. 逻辑删除
     * 3. 如果删除的是默认地址，将第一条地址设为新默认
     * 4. 清除默认地址缓存
     * </p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteAddress(Long userId, Long addressId) {
        RLock lock = redissonClient.getLock(RedisKeyConstants.USER_ADDRESS_LOCK + userId);
        try {
            if (!lock.tryLock(3, 10, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new BizException(ResultCode.LOCK_ACQUIRE_FAIL);
            }
            doDeleteAddress(userId, addressId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ResultCode.LOCK_ACQUIRE_FAIL);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void doDeleteAddress(Long userId, Long addressId) {
        UserAddress address = getAndVerifyOwnership(userId, addressId);
        boolean wasDefault = address.getIsDefault() == 1;

        // 2. 逻辑删除
        userAddressMapper.deleteById(addressId);
        log.info("[收货地址] 删除成功, userId={}, addressId={}", userId, addressId);

        // 3. 清除默认地址缓存
        redisOperator.delete(RedisKeyConstants.USER_ADDRESS_DEFAULT + userId);

        // 4. 如果删除的是默认地址，自动设置第一条为新默认
        if (wasDefault) {
            UserAddress firstAddress = userAddressMapper.selectOne(
                    new LambdaQueryWrapper<UserAddress>()
                            .eq(UserAddress::getUserId, userId)
                            .eq(UserAddress::getIsDefault, 0)
                            .orderByDesc(UserAddress::getCreatedAt)
                            .last("LIMIT 1")
            );
            if (firstAddress != null) {
                // 设置新默认（加 isDefault=0 条件防止并发问题）
                int rows = userAddressMapper.update(null,
                        new LambdaUpdateWrapper<UserAddress>()
                                .eq(UserAddress::getId, firstAddress.getId())
                                .eq(UserAddress::getUserId, userId)
                                .eq(UserAddress::getIsDefault, 0)
                                .set(UserAddress::getIsDefault, 1)
                );
                if (rows > 0) {
                    updateDefaultAddressCache(userId, firstAddress.getId());
                    log.info("[收货地址] 删除默认地址后自动设置新默认, userId={}, newDefaultAddressId={}", userId, firstAddress.getId());
                }
            }
        }
    }

    // ==================== 查询地址列表 ====================

    /**
     * 获取用户地址列表
     */
    public List<AddressVO> listAddresses(Long userId) {
        List<UserAddress> addresses = userAddressMapper.selectList(
                new LambdaQueryWrapper<UserAddress>()
                        .eq(UserAddress::getUserId, userId)
                        .orderByDesc(UserAddress::getIsDefault)
                        .orderByDesc(UserAddress::getCreatedAt)
        );
        return addresses.stream().map(this::toAddressVO).collect(Collectors.toList());
    }

    // ==================== 查询地址详情 ====================

    /**
     * 获取地址详情
     */
    public AddressVO getAddress(Long userId, Long addressId) {
        UserAddress address = getAndVerifyOwnership(userId, addressId);
        return toAddressVO(address);
    }

    // ==================== 获取默认地址 ====================

    /**
     * 获取默认地址（带缓存）
     * <p>
     * 下单时高频调用，优先从 Redis 缓存获取默认地址 ID，再查 DB。
     * 缓存 TTL 30 分钟，设置/删除默认地址时主动更新。
     * </p>
     */
    public AddressVO getDefaultAddress(Long userId) {
        // 1. 先从缓存获取默认地址 ID
        String cacheKey = RedisKeyConstants.USER_ADDRESS_DEFAULT + userId;
        Object cachedId = redisOperator.get(cacheKey);

        if (cachedId != null) {
            Long addressId = Long.valueOf(cachedId.toString());
            UserAddress address = userAddressMapper.selectById(addressId);
            if (address != null && address.getUserId().equals(userId)) {
                return toAddressVO(address);
            }
            // 缓存失效，清除并继续查 DB
            redisOperator.delete(cacheKey);
        }

        // 2. 查 DB
        UserAddress address = userAddressMapper.selectOne(
                new LambdaQueryWrapper<UserAddress>()
                        .eq(UserAddress::getUserId, userId)
                        .eq(UserAddress::getIsDefault, 1)
                        .last("LIMIT 1")
        );

        if (address == null) {
            return null;
        }

        // 3. 回写缓存
        updateDefaultAddressCache(userId, address.getId());
        return toAddressVO(address);
    }

    // ==================== 设置默认地址 ====================

    /**
     * 设置默认地址
     * <p>
     * 1. 校验地址归属
     * 2. 取消旧默认
     * 3. 设置新默认
     * 4. 更新缓存
     * </p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void setDefaultAddress(Long userId, Long addressId) {
        RLock lock = redissonClient.getLock(RedisKeyConstants.USER_ADDRESS_LOCK + userId);
        try {
            if (!lock.tryLock(3, 10, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new BizException(ResultCode.LOCK_ACQUIRE_FAIL);
            }
            doSetDefaultAddress(userId, addressId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ResultCode.LOCK_ACQUIRE_FAIL);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void doSetDefaultAddress(Long userId, Long addressId) {
        UserAddress address = getAndVerifyOwnership(userId, addressId);

        if (address.getIsDefault() == 1) {
            // 已经是默认地址，无需操作
            log.debug("[收货地址] 地址已是默认, userId={}, addressId={}", userId, addressId);
            return;
        }

        // 2. 取消旧默认
        cancelDefaultAddress(userId);

        // 3. 设置新默认
        UserAddress updateDefault = new UserAddress();
        updateDefault.setId(addressId);
        updateDefault.setIsDefault(1);
        userAddressMapper.updateById(updateDefault);

        // 4. 更新缓存
        updateDefaultAddressCache(userId, addressId);
        log.info("[收货地址] 设置默认地址成功, userId={}, addressId={}", userId, addressId);
    }

    // ==================== 私有方法 ====================

    /**
     * 校验地址归属（确保当前用户是地址的所有者）
     */
    private UserAddress getAndVerifyOwnership(Long userId, Long addressId) {
        UserAddress address = userAddressMapper.selectById(addressId);
        if (address == null) {
            throw new BizException(ResultCode.ADDRESS_NOT_FOUND);
        }
        if (!address.getUserId().equals(userId)) {
            throw new BizException(ResultCode.FORBIDDEN, "无权操作此地址");
        }
        return address;
    }

    /**
     * 取消当前用户的默认地址
     */
    private void cancelDefaultAddress(Long userId) {
        userAddressMapper.update(null,
                new LambdaUpdateWrapper<UserAddress>()
                        .eq(UserAddress::getUserId, userId)
                        .eq(UserAddress::getIsDefault, 1)
                        .set(UserAddress::getIsDefault, 0)
        );
    }

    /**
     * 更新默认地址ID缓存
     * <p>
     * 下单时频繁查默认地址，缓存减少DB查询。TTL 30分钟。
     * </p>
     */
    private void updateDefaultAddressCache(Long userId, Long addressId) {
        String cacheKey = RedisKeyConstants.USER_ADDRESS_DEFAULT + userId;
        redisOperator.set(cacheKey, addressId, 30, TimeUnit.MINUTES);
        log.debug("[收货地址] 更新默认地址缓存, key={}, addressId={}", cacheKey, addressId);
    }

    /**
     * UserAddress → AddressVO（手机号脱敏）
     */
    private AddressVO toAddressVO(UserAddress address) {
        return AddressVO.builder()
                .id(address.getId())
                .receiverName(address.getReceiverName())
                .receiverPhone(maskPhone(address.getReceiverPhone()))
                .province(address.getProvince())
                .city(address.getCity())
                .district(address.getDistrict())
                .detailAddress(address.getDetailAddress())
                .isDefault(address.getIsDefault())
                .createdAt(address.getCreatedAt())
                .updatedAt(address.getUpdatedAt())
                .build();
    }

    /**
     * 手机号脱敏：138****1234
     */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
