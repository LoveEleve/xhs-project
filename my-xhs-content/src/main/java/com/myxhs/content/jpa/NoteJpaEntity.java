package com.myxhs.content.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 演示实体：t_note（只读映射，用于 JPA 与 MyBatis 并存验证）
 */
@Data
@Entity
@Table(name = "t_note")
public class NoteJpaEntity {

    @Id
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    private String title;

    private Integer status;
}
