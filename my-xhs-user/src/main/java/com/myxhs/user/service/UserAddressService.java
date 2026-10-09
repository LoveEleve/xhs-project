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
    /** 事务模板：把"取锁 → 事务 → 释放锁"改为锁在事务外，避免锁先于提交释放 */
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

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
    public AddressVO createAddress(Long userId, AddressCreateRequest request) {
        // 分布式锁在事务外：原实现 @Transactional 内取锁，锁先于事务提交释放
        // → 后进线程读不到未提交数据，并发可超上限/产生两个默认地址
        String lockKey = RedisKeyConstants.USER_ADDRESS_LOCK + userId;
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(3, 10, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new BizException(ResultCode.LOCK_ACQUIRE_FAIL);
            }
            try {
                AddressVO vo = transactionTemplate.execute(status -> doCreateAddress(userId, request));
                // 缓存写在事务提交后：回滚时不会留下指向"并不存在的默认地址"的脏缓存
                if (vo != null && Integer.valueOf(1).equals(vo.getIsDefault())) {
                    updateDefaultAddressCache(userId, vo.getId());
                }
                return vo;
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ResultCode.LOCK_ACQUIRE_FAIL);
        }
    }

    private AddressVO doCreateAddress(Long userId, AddressCreateRequest request) {
        // 1. 检查地址数量上限（锁内检查，防止并发超限）
        Long count = userAddressMapper.selectCount(
                new LambdaQueryWrapper<UserAddress>().eq(UserAddress::getUserId, userId)
        );
        if (count >= addressLimit) {
            throw new BizException(ResultCode.ADDRESS_LIMIT_EXCEEDED);
        }

        // 2. 构建实体
        UserAddress address = new UserAddress();
        address.setUserId(userId);
        address.setReceiverName(request.getReceiverName());
        address.setReceiverPhone(request.getReceiverPhone());
        address.setProvince(request.getProvince());
        address.setCity(request.getCity());
        address.setDistrict(request.getDistrict());
        address.setDetailAddress(request.getDetailAddress());

        // 3. 判断是否设为默认地址
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

        // 4. 插入
        userAddressMapper.insert(address);
        log.info("[收货地址] 新增成功, userId={}, addressId={}, isDefault={}", userId, address.getId(), address.getIsDefault());
        return toAddressVO(address);
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
    public AddressVO updateAddress(Long userId, Long addressId, AddressUpdateRequest request) {
        // 锁在事务外（同 createAddress，防锁先于提交释放）
        String lockKey = RedisKeyConstants.USER_ADDRESS_LOCK + userId;
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(3, 10, TimeUnit.SECONDS)) {
                throw new BizException(ResultCode.LOCK_ACQUIRE_FAIL);
            }
            try {
                UpdateOutcome outcome = transactionTemplate.execute(status -> doUpdateAddress(userId, addressId, request));
                if (outcome != null && outcome.defaultSwitched()) {
                    // 缓存写在提交后：原实现事务内先写缓存，回滚后默认地址缓存脏 30min
                    updateDefaultAddressCache(userId, addressId);
                }
                return outcome != null ? outcome.vo() : null;
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ResultCode.LOCK_ACQUIRE_FAIL);
        }
    }

    /** 事务内更新结果：VO + 是否发生默认地址切换（决定提交后是否写缓存） */
    private record UpdateOutcome(AddressVO vo, boolean defaultSwitched) {}

    private UpdateOutcome doUpdateAddress(Long userId, Long addressId, AddressUpdateRequest request) {
        // 1. 校验地址归属
        UserAddress address = getAndVerifyOwnership(userId, addressId);

        // 2. 构建更新条件（同时加 userId 和 addressId 条件，双重校验防越权）
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

        // 3. 处理默认地址切换
        boolean defaultSwitched = false;
        if (Boolean.TRUE.equals(request.getIsDefault()) && address.getIsDefault() == 0) {
            cancelDefaultAddress(userId);
            updateWrapper.set(UserAddress::getIsDefault, 1);
            hasUpdate = true;
            defaultSwitched = true;
        }

        // 4. 执行更新（至少有一个字段需要更新时才执行）
        if (hasUpdate) {
            userAddressMapper.update(null, updateWrapper);
            log.info("[收货地址] 更新成功, userId={}, addressId={}", userId, addressId);
        } else {
            log.debug("[收货地址] 无字段需要更新, userId={}, addressId={}", userId, addressId);
        }

        // 5. 查询最新数据返回
        UserAddress updated = userAddressMapper.selectById(addressId);
        return new UpdateOutcome(toAddressVO(updated), defaultSwitched);
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
    public void deleteAddress(Long userId, Long addressId) {
        RLock lock = redissonClient.getLock(RedisKeyConstants.USER_ADDRESS_LOCK + userId);
        try {
            if (!lock.tryLock(3, 10, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new BizException(ResultCode.LOCK_ACQUIRE_FAIL);
            }
            try {
                Long newDefaultId = transactionTemplate.execute(status -> doDeleteAddress(userId, addressId));
                // 缓存操作在提交后：先清后写（新默认存在时）
                redisOperator.delete(RedisKeyConstants.USER_ADDRESS_DEFAULT + userId);
                if (newDefaultId != null) {
                    updateDefaultAddressCache(userId, newDefaultId);
                }
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ResultCode.LOCK_ACQUIRE_FAIL);
        }
    }

    /**
     * @return 删除默认地址后自动选出的新默认地址 ID；无则 null
     */
    private Long doDeleteAddress(Long userId, Long addressId) {
        UserAddress address = getAndVerifyOwnership(userId, addressId);
        boolean wasDefault = address.getIsDefault() == 1;

        // 1. 逻辑删除
        userAddressMapper.deleteById(addressId);
        log.info("[收货地址] 删除成功, userId={}, addressId={}", userId, addressId);

        // 2. 如果删除的是默认地址，自动设置第一条为新默认
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
                    log.info("[收货地址] 删除默认地址后自动设置新默认, userId={}, newDefaultAddressId={}", userId, firstAddress.getId());
                    return firstAddress.getId();
                }
            }
        }
        return null;
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
    /**
     * 内部调用（X-Internal-Call）：返回明文手机号，供订单快照等内部用途
     */
    public AddressVO getAddressForInternal(Long userId, Long addressId) {
        UserAddress address = getAndVerifyOwnership(userId, addressId);
        return toAddressVO(address, false);
    }

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
    public void setDefaultAddress(Long userId, Long addressId) {
        RLock lock = redissonClient.getLock(RedisKeyConstants.USER_ADDRESS_LOCK + userId);
        try {
            if (!lock.tryLock(3, 10, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new BizException(ResultCode.LOCK_ACQUIRE_FAIL);
            }
            try {
                Boolean switched = transactionTemplate.execute(status -> doSetDefaultAddress(userId, addressId));
                if (Boolean.TRUE.equals(switched)) {
                    updateDefaultAddressCache(userId, addressId);
                }
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ResultCode.LOCK_ACQUIRE_FAIL);
        }
    }

    /**
     * @return 是否发生了默认地址切换（已是默认则 false，无需写缓存）
     */
    private boolean doSetDefaultAddress(Long userId, Long addressId) {
        UserAddress address = getAndVerifyOwnership(userId, addressId);

        if (address.getIsDefault() == 1) {
            // 已经是默认地址，无需操作
            log.debug("[收货地址] 地址已是默认, userId={}, addressId={}", userId, addressId);
            return false;
        }

        // 1. 取消旧默认
        cancelDefaultAddress(userId);

        // 2. 设置新默认
        UserAddress updateDefault = new UserAddress();
        updateDefault.setId(addressId);
        updateDefault.setIsDefault(1);
        userAddressMapper.updateById(updateDefault);

        log.info("[收货地址] 设置默认地址成功, userId={}, addressId={}", userId, addressId);
        return true;
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
        return toAddressVO(address, true);
    }

    /**
     * @param maskPhone 是否脱敏手机号：用户面列表/详情脱敏；<b>内部调用（订单快照）必须明文</b>，
     *                  否则订单收货电话会落库为 138****1234
     */
    private AddressVO toAddressVO(UserAddress address, boolean maskPhone) {
        return AddressVO.builder()
                .id(address.getId())
                .receiverName(address.getReceiverName())
                .receiverPhone(maskPhone ? maskPhone(address.getReceiverPhone()) : address.getReceiverPhone())
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
