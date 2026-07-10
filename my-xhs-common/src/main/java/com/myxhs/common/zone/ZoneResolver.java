package com.myxhs.common.zone;

import java.util.function.Function;

/**
 * Zone 解析器 — 从指定实体中解析所属 Zone。
 *
 * @param <E> 实体类型
 * @since 1.0.0
 */
@FunctionalInterface
public interface ZoneResolver<E> extends Function<E, String> {

    @Override
    default String apply(E entity) {
        return resolve(entity);
    }

    /**
     * 解析实体所属的 Zone
     *
     * @param entity 实体
     * @return Zone 名称，无法解析返回 null
     */
    String resolve(E entity);
}
