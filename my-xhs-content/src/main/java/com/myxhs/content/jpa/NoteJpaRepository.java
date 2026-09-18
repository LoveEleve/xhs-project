package com.myxhs.content.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 演示仓库：Spring Data JPA（与 MyBatis Mapper 并存）
 */
public interface NoteJpaRepository extends JpaRepository<NoteJpaEntity, Long> {
}
