# MyXHS 完整 DDL

## MySQL 13306（user 库）

### my_xhs_analytics.t_favorite
```sql
t_favorite
CREATE TABLE `t_favorite` (
`id` bigint NOT NULL COMMENT 'ID',
`user_id` bigint NOT NULL COMMENT '用户ID',
`note_id` bigint NOT NULL COMMENT '笔记ID',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_user_note` (`user_id`,`note_id`),
KEY `idx_note_id` (`note_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='收藏表'
```

### my_xhs_analytics.t_follow
```sql
t_follow
CREATE TABLE `t_follow` (
`id` bigint NOT NULL COMMENT 'ID',
`user_id` bigint NOT NULL COMMENT '用户ID',
`follow_user_id` bigint NOT NULL COMMENT '被关注用户ID',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '关注时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_user_follow` (`user_id`,`follow_user_id`),
KEY `idx_follow_user_id` (`follow_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='关注关系表'
```

### my_xhs_analytics.t_like
```sql
t_like
CREATE TABLE `t_like` (
`id` bigint NOT NULL COMMENT 'ID',
`user_id` bigint NOT NULL COMMENT '用户ID',
`biz_type` tinyint NOT NULL COMMENT '业务类型：1-笔记 2-评论',
`biz_id` bigint NOT NULL COMMENT '业务ID',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_user_biz` (`user_id`,`biz_type`,`biz_id`),
KEY `idx_biz` (`biz_type`,`biz_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='点赞表'
```

### my_xhs_analytics.t_user_behavior
```sql
t_user_behavior
CREATE TABLE `t_user_behavior` (
`id` bigint NOT NULL COMMENT 'ID',
`user_id` bigint NOT NULL COMMENT '用户ID',
`note_id` bigint NOT NULL COMMENT '笔记ID',
`behavior_type` tinyint NOT NULL COMMENT '行为类型：1-浏览 2-点赞 3-收藏 4-评论 5-分享 6-搜索',
`duration` int DEFAULT NULL COMMENT '停留时长（秒），仅浏览行为',
`deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
KEY `idx_user_id` (`user_id`),
KEY `idx_note_id` (`note_id`),
KEY `idx_behavior_type` (`behavior_type`),
KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户行为记录表'
```

### my_xhs_im.t_chat_message
```sql
t_chat_message
CREATE TABLE `t_chat_message` (
`id` bigint NOT NULL COMMENT 'ID',
`conversation_id` bigint NOT NULL COMMENT '会话ID = min(A,B)<<32|max(A,B)',
`sender_id` bigint NOT NULL COMMENT '发送者ID',
`receiver_id` bigint NOT NULL COMMENT '接收者ID',
`content` varchar(2048) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '消息内容',
`msg_type` tinyint NOT NULL DEFAULT '0' COMMENT '消息类型：0-文本 1-图片 2-系统消息',
`seq_no` bigint DEFAULT NULL COMMENT '会话内序列号（Redis INCR生成，保证有序）',
`is_read` tinyint NOT NULL DEFAULT '0' COMMENT '是否已读',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
KEY `idx_conversation` (`conversation_id`),
KEY `idx_conversation_seq` (`conversation_id`,`seq_no`),
KEY `idx_sender` (`sender_id`),
KEY `idx_receiver` (`receiver_id`),
KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='聊天消息表'
```

### my_xhs_im.t_chat_user_relation
```sql
t_chat_user_relation
CREATE TABLE `t_chat_user_relation` (
`id` bigint NOT NULL COMMENT 'ID',
`user_id` bigint NOT NULL COMMENT '用户ID',
`peer_id` bigint NOT NULL COMMENT '对方用户ID',
`conversation_id` bigint NOT NULL COMMENT '会话ID',
`last_message_id` bigint DEFAULT NULL COMMENT '最后一条消息ID',
`last_content` varchar(2048) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '最后一条消息内容',
`last_msg_type` tinyint DEFAULT NULL COMMENT '最后一条消息类型',
`unread_count` int NOT NULL DEFAULT '0' COMMENT '未读数',
`is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_user_peer` (`user_id`,`peer_id`),
KEY `idx_user_id` (`user_id`),
KEY `idx_updated_at` (`updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户聊天关系表'
```

### my_xhs_notification.t_notification
```sql
t_notification
CREATE TABLE `t_notification` (
`id` bigint NOT NULL COMMENT 'ID（雪花算法）',
`user_id` bigint NOT NULL COMMENT '接收用户ID',
`type` tinyint NOT NULL COMMENT '通知类型：1-点赞 2-评论 3-关注 4-系统通知 5-订单通知',
`title` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '通知标题',
`content` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '通知内容',
`sender_id` bigint DEFAULT NULL COMMENT '发送者ID',
`sender_name` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '发送者昵称(冗余，避免查用户表)',
`target_id` bigint DEFAULT NULL COMMENT '关联目标ID(笔记/商品/订单)',
`target_type` tinyint DEFAULT NULL COMMENT '目标类型：1-笔记 2-商品 3-订单',
`sender_avatar` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '发送者头像URL（冗余）',
`is_read` tinyint NOT NULL DEFAULT '0' COMMENT '是否已读：0-未读 1-已读',
`extra_data` text COLLATE utf8mb4_unicode_ci COMMENT '扩展数据JSON',
`is_aggregated` tinyint NOT NULL DEFAULT '0' COMMENT '是否被聚合',
`aggregate_id` bigint DEFAULT NULL COMMENT '聚合目标通知ID',
`aggregate_count` int DEFAULT '1' COMMENT '聚合数量',
`notify_date` date GENERATED ALWAYS AS (cast(`created_at` as date)) STORED COMMENT '通知日期',
`deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
KEY `idx_user_id_created` (`user_id`,`created_at` DESC),
KEY `idx_user_type_read` (`user_id`,`type`,`is_read`),
KEY `idx_user_type_target` (`user_id`,`type`,`target_id`,`notify_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通知表'
```

### my_xhs_notification.t_push_task
```sql
t_push_task
CREATE TABLE `t_push_task` (
`id` bigint NOT NULL COMMENT 'ID',
`task_name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '任务名称',
`task_type` tinyint NOT NULL COMMENT '任务类型',
`template_id` bigint DEFAULT NULL COMMENT '关联推送模板ID',
`biz_id` bigint DEFAULT NULL COMMENT '业务ID',
`target_type` tinyint NOT NULL COMMENT '目标类型',
`target_condition` text COLLATE utf8mb4_unicode_ci COMMENT '筛选条件JSON',
`target_count` int NOT NULL COMMENT '目标用户数',
`success_count` int NOT NULL DEFAULT '0' COMMENT '成功数',
`fail_count` int NOT NULL DEFAULT '0' COMMENT '失败数',
`status` tinyint NOT NULL DEFAULT '0' COMMENT '状态',
`execute_time` datetime DEFAULT NULL COMMENT '计划执行时间',
`start_time` datetime DEFAULT NULL COMMENT '实际开始时间',
`end_time` datetime DEFAULT NULL COMMENT '实际结束时间',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
KEY `idx_status` (`status`),
KEY `idx_execute_time` (`execute_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='推送任务表'
```

### my_xhs_notification.t_push_task_fail
```sql
t_push_task_fail
CREATE TABLE `t_push_task_fail` (
`id` bigint NOT NULL COMMENT 'ID',
`task_id` bigint NOT NULL COMMENT '任务ID',
`user_id` bigint NOT NULL COMMENT '用户ID',
`fail_reason` varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '失败原因',
`retry_count` int NOT NULL DEFAULT '0' COMMENT '重试次数',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
KEY `idx_task_id` (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='推送失败记录表'
```

### my_xhs_notification.t_push_template
```sql
t_push_template
CREATE TABLE `t_push_template` (
`id` bigint NOT NULL COMMENT 'ID',
`type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '通知类型',
`title_template` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '标题模板',
`content_template` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '内容模板',
`aggregate_title_template` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '聚合标题模板',
`status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：0-禁用 1-启用',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_type` (`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='推送模板表'
```

### my_xhs_user.t_id_segment
```sql
t_id_segment
CREATE TABLE `t_id_segment` (
`id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增ID',
`biz_tag` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '业务标签(如user/order)',
`max_id` bigint NOT NULL DEFAULT '0' COMMENT '当前最大ID',
`step` int NOT NULL DEFAULT '1000' COMMENT '号段步长',
`version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
`description` varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '描述',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_biz_tag` (`biz_tag`)
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='号段分配表'
```

### my_xhs_user.t_user
```sql
t_user
CREATE TABLE `t_user` (
`id` bigint NOT NULL COMMENT 'ID',
`username` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户名',
`password` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '密码',
`nickname` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '昵称',
`avatar` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '头像URL',
`gender` tinyint DEFAULT '0' COMMENT '性别：0-未知 1-男 2-女',
`birthday` date DEFAULT NULL COMMENT '生日',
`phone` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
`email` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '邮箱',
`signature` varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '个性签名',
`status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：0-禁用 1-正常',
`deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_username` (`username`),
UNIQUE KEY `uk_phone` (`phone`),
KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表'
```

### my_xhs_user.t_user_address
```sql
t_user_address
CREATE TABLE `t_user_address` (
`id` bigint NOT NULL COMMENT 'ID',
`user_id` bigint NOT NULL COMMENT '用户ID',
`receiver_name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '收货人',
`receiver_phone` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '联系电话',
`province` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '省',
`city` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '市',
`district` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '区',
`detail_address` varchar(256) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '详细地址',
`is_default` tinyint NOT NULL DEFAULT '0' COMMENT '是否默认：0-否 1-是',
`deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='收货地址表'
```

### nacos_config.config_info
```sql
config_info
CREATE TABLE `config_info` (
`id` bigint NOT NULL AUTO_INCREMENT COMMENT 'id',
`data_id` varchar(255) COLLATE utf8mb3_bin NOT NULL COMMENT 'data_id',
`group_id` varchar(128) COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'group_id',
`content` longtext COLLATE utf8mb3_bin NOT NULL COMMENT 'content',
`md5` varchar(32) COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'md5',
`gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
`src_user` text COLLATE utf8mb3_bin COMMENT 'source user',
`src_ip` varchar(50) COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'source ip',
`app_name` varchar(128) COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'app_name',
`tenant_id` varchar(128) COLLATE utf8mb3_bin DEFAULT '' COMMENT '租户字段',
`c_desc` varchar(256) COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'configuration description',
`c_use` varchar(64) COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'configuration usage',
`effect` varchar(64) COLLATE utf8mb3_bin DEFAULT NULL COMMENT '配置生效的描述',
`type` varchar(64) COLLATE utf8mb3_bin DEFAULT NULL COMMENT '配置的类型',
`c_schema` text COLLATE utf8mb3_bin COMMENT '配置的模式',
`encrypted_data_key` text COLLATE utf8mb3_bin NOT NULL COMMENT '密钥',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_configinfo_datagrouptenant` (`data_id`,`group_id`,`tenant_id`)
) ENGINE=InnoDB AUTO_INCREMENT=13 DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_bin COMMENT='config_info'
```

### nacos_config.config_info_aggr
```sql
config_info_aggr
CREATE TABLE `config_info_aggr` (
`id` bigint NOT NULL AUTO_INCREMENT COMMENT 'id',
`data_id` varchar(255) COLLATE utf8mb3_bin NOT NULL COMMENT 'data_id',
`group_id` varchar(128) COLLATE utf8mb3_bin NOT NULL COMMENT 'group_id',
`datum_id` varchar(255) COLLATE utf8mb3_bin NOT NULL COMMENT 'datum_id',
`content` longtext COLLATE utf8mb3_bin NOT NULL COMMENT '内容',
`gmt_modified` datetime NOT NULL COMMENT '修改时间',
`app_name` varchar(128) COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'app_name',
`tenant_id` varchar(128) COLLATE utf8mb3_bin DEFAULT '' COMMENT '租户字段',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_configinfoaggr_datagrouptenantdatum` (`data_id`,`group_id`,`tenant_id`,`datum_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_bin COMMENT='增加租户字段'
```

### nacos_config.config_info_beta
```sql
config_info_beta
CREATE TABLE `config_info_beta` (
`id` bigint NOT NULL AUTO_INCREMENT COMMENT 'id',
`data_id` varchar(255) COLLATE utf8mb3_bin NOT NULL COMMENT 'data_id',
`group_id` varchar(128) COLLATE utf8mb3_bin NOT NULL COMMENT 'group_id',
`app_name` varchar(128) COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'app_name',
`content` longtext COLLATE utf8mb3_bin NOT NULL COMMENT 'content',
`beta_ips` varchar(1024) COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'betaIps',
`md5` varchar(32) COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'md5',
`gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
`src_user` text COLLATE utf8mb3_bin COMMENT 'source user',
`src_ip` varchar(50) COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'source ip',
`tenant_id` varchar(128) COLLATE utf8mb3_bin DEFAULT '' COMMENT '租户字段',
`encrypted_data_key` text COLLATE utf8mb3_bin NOT NULL COMMENT '密钥',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_configinfobeta_datagrouptenant` (`data_id`,`group_id`,`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_bin COMMENT='config_info_beta'
```

### nacos_config.config_info_tag
```sql
config_info_tag
CREATE TABLE `config_info_tag` (
`id` bigint NOT NULL AUTO_INCREMENT COMMENT 'id',
`data_id` varchar(255) COLLATE utf8mb3_bin NOT NULL COMMENT 'data_id',
`group_id` varchar(128) COLLATE utf8mb3_bin NOT NULL COMMENT 'group_id',
`tenant_id` varchar(128) COLLATE utf8mb3_bin DEFAULT '' COMMENT 'tenant_id',
`tag_id` varchar(128) COLLATE utf8mb3_bin NOT NULL COMMENT 'tag_id',
`app_name` varchar(128) COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'app_name',
`content` longtext COLLATE utf8mb3_bin NOT NULL COMMENT 'content',
`md5` varchar(32) COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'md5',
`gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
`src_user` text COLLATE utf8mb3_bin COMMENT 'source user',
`src_ip` varchar(50) COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'source ip',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_configinfotag_datagrouptenanttag` (`data_id`,`group_id`,`tenant_id`,`tag_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_bin COMMENT='config_info_tag'
```

### nacos_config.config_tags_relation
```sql
config_tags_relation
CREATE TABLE `config_tags_relation` (
`id` bigint NOT NULL COMMENT 'id',
`tag_name` varchar(128) COLLATE utf8mb3_bin NOT NULL COMMENT 'tag_name',
`tag_type` varchar(64) COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'tag_type',
`data_id` varchar(255) COLLATE utf8mb3_bin NOT NULL COMMENT 'data_id',
`group_id` varchar(128) COLLATE utf8mb3_bin NOT NULL COMMENT 'group_id',
`tenant_id` varchar(128) COLLATE utf8mb3_bin DEFAULT '' COMMENT 'tenant_id',
`nid` bigint NOT NULL AUTO_INCREMENT COMMENT 'nid, 自增长标识',
PRIMARY KEY (`nid`),
UNIQUE KEY `uk_configtagrelation_configidtag` (`id`,`tag_name`,`tag_type`),
KEY `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_bin COMMENT='config_tag_relation'
```

### nacos_config.group_capacity
```sql
group_capacity
CREATE TABLE `group_capacity` (
`id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
`group_id` varchar(128) COLLATE utf8mb3_bin NOT NULL DEFAULT '' COMMENT 'Group ID，空字符表示整个集群',
`quota` int unsigned NOT NULL DEFAULT '0' COMMENT '配额，0表示使用默认值',
`usage` int unsigned NOT NULL DEFAULT '0' COMMENT '使用量',
`max_size` int unsigned NOT NULL DEFAULT '0' COMMENT '单个配置大小上限，单位为字节，0表示使用默认值',
`max_aggr_count` int unsigned NOT NULL DEFAULT '0' COMMENT '聚合子配置最大个数，，0表示使用默认值',
`max_aggr_size` int unsigned NOT NULL DEFAULT '0' COMMENT '单个聚合数据的子配置大小上限，单位为字节，0表示使用默认值',
`max_history_count` int unsigned NOT NULL DEFAULT '0' COMMENT '最大变更历史数量',
`gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_group_id` (`group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_bin COMMENT='集群、各Group容量信息表'
```

### nacos_config.his_config_info
```sql
his_config_info
CREATE TABLE `his_config_info` (
`id` bigint unsigned NOT NULL COMMENT 'id',
`nid` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'nid, 自增标识',
`data_id` varchar(255) COLLATE utf8mb3_bin NOT NULL COMMENT 'data_id',
`group_id` varchar(128) COLLATE utf8mb3_bin NOT NULL COMMENT 'group_id',
`app_name` varchar(128) COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'app_name',
`content` longtext COLLATE utf8mb3_bin NOT NULL COMMENT 'content',
`md5` varchar(32) COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'md5',
`gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
`src_user` text COLLATE utf8mb3_bin COMMENT 'source user',
`src_ip` varchar(50) COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'source ip',
`op_type` char(10) COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'operation type',
`tenant_id` varchar(128) COLLATE utf8mb3_bin DEFAULT '' COMMENT '租户字段',
`encrypted_data_key` text COLLATE utf8mb3_bin NOT NULL COMMENT '密钥',
PRIMARY KEY (`nid`),
KEY `idx_gmt_create` (`gmt_create`),
KEY `idx_gmt_modified` (`gmt_modified`),
KEY `idx_did` (`data_id`)
) ENGINE=InnoDB AUTO_INCREMENT=34 DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_bin COMMENT='多租户改造'
```

### nacos_config.permissions
```sql
permissions
CREATE TABLE `permissions` (
`role` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'role',
`resource` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'resource',
`action` varchar(8) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'action',
UNIQUE KEY `uk_role_permission` (`role`,`resource`,`action`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
```

### nacos_config.roles
```sql
roles
CREATE TABLE `roles` (
`username` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'username',
`role` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'role',
UNIQUE KEY `idx_user_role` (`username`,`role`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
```

### nacos_config.tenant_capacity
```sql
tenant_capacity
CREATE TABLE `tenant_capacity` (
`id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
`tenant_id` varchar(128) COLLATE utf8mb3_bin NOT NULL DEFAULT '' COMMENT 'Tenant ID',
`quota` int unsigned NOT NULL DEFAULT '0' COMMENT '配额，0表示使用默认值',
`usage` int unsigned NOT NULL DEFAULT '0' COMMENT '使用量',
`max_size` int unsigned NOT NULL DEFAULT '0' COMMENT '单个配置大小上限，单位为字节，0表示使用默认值',
`max_aggr_count` int unsigned NOT NULL DEFAULT '0' COMMENT '聚合子配置最大个数',
`max_aggr_size` int unsigned NOT NULL DEFAULT '0' COMMENT '单个聚合数据的子配置大小上限，单位为字节，0表示使用默认值',
`max_history_count` int unsigned NOT NULL DEFAULT '0' COMMENT '最大变更历史数量',
`gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_bin COMMENT='租户容量信息表'
```

### nacos_config.tenant_info
```sql
tenant_info
CREATE TABLE `tenant_info` (
`id` bigint NOT NULL AUTO_INCREMENT COMMENT 'id',
`kp` varchar(128) COLLATE utf8mb3_bin NOT NULL COMMENT 'kp',
`tenant_id` varchar(128) COLLATE utf8mb3_bin DEFAULT '' COMMENT 'tenant_id',
`tenant_name` varchar(128) COLLATE utf8mb3_bin DEFAULT '' COMMENT 'tenant_name',
`tenant_desc` varchar(256) COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'tenant_desc',
`create_source` varchar(32) COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'create_source',
`gmt_create` bigint NOT NULL COMMENT '创建时间',
`gmt_modified` bigint NOT NULL COMMENT '修改时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_tenant_info_kptenantid` (`kp`,`tenant_id`),
KEY `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_bin COMMENT='tenant_info'
```

### nacos_config.users
```sql
users
CREATE TABLE `users` (
`username` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'username',
`password` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'password',
`enabled` tinyint(1) NOT NULL COMMENT 'enabled',
PRIMARY KEY (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
```

### seata_lab.seata_account
```sql
seata_account
CREATE TABLE `seata_account` (
`id` bigint NOT NULL AUTO_INCREMENT,
`name` varchar(64) NOT NULL,
`balance` decimal(12,2) NOT NULL DEFAULT '0.00',
PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
```

### seata_lab.undo_log
```sql
undo_log
CREATE TABLE `undo_log` (
`id` bigint NOT NULL AUTO_INCREMENT,
`branch_id` bigint NOT NULL,
`xid` varchar(100) NOT NULL,
`context` varchar(128) NOT NULL,
`rollback_info` longblob NOT NULL,
`log_status` int NOT NULL,
`log_created` datetime NOT NULL,
`log_modified` datetime NOT NULL,
`ext` varchar(100) DEFAULT NULL,
PRIMARY KEY (`id`),
UNIQUE KEY `ux_undo_log` (`xid`,`branch_id`)
) ENGINE=InnoDB AUTO_INCREMENT=13 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
```

### xxl_job.xxl_job_group
```sql
xxl_job_group
CREATE TABLE `xxl_job_group` (
`id` int NOT NULL AUTO_INCREMENT,
`app_name` varchar(64) NOT NULL COMMENT '???AppName',
`title` varchar(12) NOT NULL COMMENT '?????',
`address_type` tinyint NOT NULL DEFAULT '0' COMMENT '????????0=?????1=????',
`address_list` text COMMENT '???????????????',
`update_time` datetime DEFAULT NULL,
PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
```

### xxl_job.xxl_job_info
```sql
xxl_job_info
CREATE TABLE `xxl_job_info` (
`id` int NOT NULL AUTO_INCREMENT,
`job_group` int NOT NULL COMMENT '?????ID',
`job_desc` varchar(255) NOT NULL,
`add_time` datetime DEFAULT NULL,
`update_time` datetime DEFAULT NULL,
`author` varchar(64) DEFAULT NULL COMMENT '??',
`alarm_email` varchar(255) DEFAULT NULL COMMENT '????',
`schedule_type` varchar(50) NOT NULL DEFAULT 'NONE' COMMENT '????',
`schedule_conf` varchar(128) DEFAULT NULL COMMENT '???????????????',
`misfire_strategy` varchar(50) NOT NULL DEFAULT 'DO_NOTHING' COMMENT '??????',
`executor_route_strategy` varchar(50) DEFAULT NULL COMMENT '???????',
`executor_handler` varchar(255) DEFAULT NULL COMMENT '?????handler',
`executor_param` varchar(512) DEFAULT NULL COMMENT '???????',
`executor_block_strategy` varchar(50) DEFAULT NULL COMMENT '??????',
`executor_timeout` int NOT NULL DEFAULT '0' COMMENT '????????????',
`executor_fail_retry_count` int NOT NULL DEFAULT '0' COMMENT '??????',
`glue_type` varchar(50) NOT NULL COMMENT 'GLUE??',
`glue_source` mediumtext COMMENT 'GLUE???',
`glue_remark` varchar(128) DEFAULT NULL COMMENT 'GLUE??',
`glue_updatetime` datetime DEFAULT NULL COMMENT 'GLUE????',
`child_jobid` varchar(255) DEFAULT NULL COMMENT '???ID???????',
`trigger_status` tinyint NOT NULL DEFAULT '0' COMMENT '?????0-???1-??',
`trigger_last_time` bigint NOT NULL DEFAULT '0' COMMENT '??????',
`trigger_next_time` bigint NOT NULL DEFAULT '0' COMMENT '??????',
PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=17 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
```

### xxl_job.xxl_job_lock
```sql
xxl_job_lock
CREATE TABLE `xxl_job_lock` (
`lock_name` varchar(50) NOT NULL COMMENT '???',
PRIMARY KEY (`lock_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
```

### xxl_job.xxl_job_log
```sql
xxl_job_log
CREATE TABLE `xxl_job_log` (
`id` bigint NOT NULL AUTO_INCREMENT,
`job_group` int NOT NULL COMMENT '?????ID',
`job_id` int NOT NULL COMMENT '?????ID',
`executor_address` varchar(255) DEFAULT NULL COMMENT '?????????????',
`executor_handler` varchar(255) DEFAULT NULL COMMENT '?????handler',
`executor_param` varchar(512) DEFAULT NULL COMMENT '???????',
`executor_sharding_param` varchar(20) DEFAULT NULL COMMENT '????????????? 1/2',
`executor_fail_retry_count` int NOT NULL DEFAULT '0' COMMENT '??????',
`trigger_time` datetime DEFAULT NULL COMMENT '??-??',
`trigger_code` int NOT NULL COMMENT '??-??',
`trigger_msg` text COMMENT '??-??',
`handle_time` datetime DEFAULT NULL COMMENT '??-??',
`handle_code` int NOT NULL COMMENT '??-??',
`handle_msg` text COMMENT '??-??',
`alarm_status` tinyint NOT NULL DEFAULT '0' COMMENT '?????0-???1-?????2-?????3-????',
PRIMARY KEY (`id`),
KEY `I_trigger_time` (`trigger_time`),
KEY `I_handle_code` (`handle_code`)
) ENGINE=InnoDB AUTO_INCREMENT=48088 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
```

### xxl_job.xxl_job_log_report
```sql
xxl_job_log_report
CREATE TABLE `xxl_job_log_report` (
`id` int NOT NULL AUTO_INCREMENT,
`trigger_day` datetime DEFAULT NULL COMMENT '??-??',
`running_count` int NOT NULL DEFAULT '0' COMMENT '???-????',
`suc_count` int NOT NULL DEFAULT '0' COMMENT '????-????',
`fail_count` int NOT NULL DEFAULT '0' COMMENT '????-????',
`update_time` datetime DEFAULT NULL,
PRIMARY KEY (`id`),
UNIQUE KEY `i_trigger_day` (`trigger_day`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=19 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
```

### xxl_job.xxl_job_logglue
```sql
xxl_job_logglue
CREATE TABLE `xxl_job_logglue` (
`id` int NOT NULL AUTO_INCREMENT,
`job_id` int NOT NULL COMMENT '?????ID',
`glue_type` varchar(50) DEFAULT NULL COMMENT 'GLUE??',
`glue_source` mediumtext COMMENT 'GLUE???',
`glue_remark` varchar(128) NOT NULL COMMENT 'GLUE??',
`add_time` datetime DEFAULT NULL,
`update_time` datetime DEFAULT NULL,
PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
```

### xxl_job.xxl_job_registry
```sql
xxl_job_registry
CREATE TABLE `xxl_job_registry` (
`id` int NOT NULL AUTO_INCREMENT,
`registry_group` varchar(50) NOT NULL,
`registry_key` varchar(255) NOT NULL,
`registry_value` varchar(255) NOT NULL,
`update_time` datetime DEFAULT NULL,
PRIMARY KEY (`id`),
KEY `i_g_k_v` (`registry_group`,`registry_key`,`registry_value`)
) ENGINE=InnoDB AUTO_INCREMENT=161 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
```

### xxl_job.xxl_job_user
```sql
xxl_job_user
CREATE TABLE `xxl_job_user` (
`id` int NOT NULL AUTO_INCREMENT,
`username` varchar(50) NOT NULL COMMENT '??',
`password` varchar(50) NOT NULL COMMENT '??',
`role` tinyint NOT NULL COMMENT '???0-?????1-???',
`permission` varchar(255) DEFAULT NULL COMMENT '??????ID?????????',
PRIMARY KEY (`id`),
UNIQUE KEY `i_username` (`username`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
```

## MySQL 13307（content 库）

### my_xhs_cart.t_cart_item
```sql
t_cart_item
CREATE TABLE `t_cart_item` (
`id` bigint NOT NULL COMMENT 'ID',
`user_id` bigint NOT NULL COMMENT '用户ID',
`sku_id` bigint NOT NULL COMMENT 'SKU ID',
`quantity` int NOT NULL DEFAULT '1' COMMENT '数量',
`checked` tinyint NOT NULL DEFAULT '1' COMMENT '是否选中：0-否 1-是',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_user_sku` (`user_id`,`sku_id`),
KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='购物车项表'
```

### my_xhs_content.t_comment
```sql
t_comment
CREATE TABLE `t_comment` (
`id` bigint NOT NULL COMMENT 'ID',
`note_id` bigint NOT NULL COMMENT '笔记ID',
`user_id` bigint NOT NULL COMMENT '评论用户ID',
`parent_id` bigint DEFAULT '0' COMMENT '父评论ID(0为一级评论)',
`reply_to_id` bigint DEFAULT NULL COMMENT '回复的评论ID',
`content` varchar(1024) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '评论内容',
`like_count` int NOT NULL DEFAULT '0' COMMENT '点赞数',
`deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
KEY `idx_note_id` (`note_id`),
KEY `idx_user_id` (`user_id`),
KEY `idx_parent_id` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评论表'
```

### my_xhs_content.t_hot_search_snapshot
```sql
t_hot_search_snapshot
CREATE TABLE `t_hot_search_snapshot` (
`id` bigint NOT NULL,
`keyword` varchar(100) NOT NULL,
`score` double NOT NULL DEFAULT '0',
`rank_no` int NOT NULL DEFAULT '0',
`search_count` bigint NOT NULL DEFAULT '0',
`snapshot_time` datetime NOT NULL,
PRIMARY KEY (`id`),
KEY `idx_snapshot_time` (`snapshot_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
```

### my_xhs_content.t_local_message
```sql
t_local_message
CREATE TABLE `t_local_message` (
`id` bigint NOT NULL COMMENT 'ID',
`topic` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'MQ Topic',
`body` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '消息体JSON',
`status` tinyint NOT NULL DEFAULT '0' COMMENT '0=待发送 1=已发送 2=发送失败 3=死信',
`retry_count` int NOT NULL DEFAULT '0' COMMENT '重试次数',
`push_status` tinyint NOT NULL DEFAULT '0' COMMENT '推送状态：0=未推送 1=推送中 2=已推送 3=推送失败',
`push_cursor` int NOT NULL DEFAULT '0' COMMENT '推送游标（已推送到第几个粉丝）',
`push_total` int NOT NULL DEFAULT '0' COMMENT '总粉丝数',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
KEY `idx_status_retry` (`status`,`retry_count`,`created_at`),
KEY `idx_push_status` (`push_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='本地消息表(Feed可靠性保障)'
```

### my_xhs_content.t_note
```sql
t_note
CREATE TABLE `t_note` (
`id` bigint NOT NULL COMMENT 'ID',
`user_id` bigint NOT NULL COMMENT '作者ID',
`title` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '标题',
`content` text COLLATE utf8mb4_unicode_ci COMMENT '正文',
`images` text COLLATE utf8mb4_unicode_ci COMMENT '图片URL列表(JSON)',
`video_url` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '视频URL',
`cover_url` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '封面图URL',
`topic_ids` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '话题ID列表(JSON)',
`tags` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '标签列表(JSON)',
`status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0-草稿 1-审核中 2-已发布 3-已下架',
`audit_status` tinyint NOT NULL DEFAULT '0' COMMENT '审核状态：0-待审核 1-通过 2-拒绝',
`reject_reason` varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '审核拒绝原因',
`note_type` tinyint NOT NULL DEFAULT '0' COMMENT '笔记类型：0-图文 1-视频',
`deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
KEY `idx_user_id` (`user_id`),
KEY `idx_status` (`status`),
KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='笔记表'
```

### my_xhs_content.t_topic
```sql
t_topic
CREATE TABLE `t_topic` (
`id` bigint NOT NULL COMMENT 'ID',
`name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '话题名称',
`icon` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '话题图标',
`description` varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '话题描述',
`note_count` int NOT NULL DEFAULT '0' COMMENT '笔记数',
`status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：0-禁用 1-正常',
`deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='话题表'
```

### my_xhs_counter.t_counter
```sql
t_counter
CREATE TABLE `t_counter` (
`id` bigint NOT NULL COMMENT 'ID',
`target_type` tinyint NOT NULL COMMENT '目标类型：1-笔记 2-用户',
`target_id` bigint NOT NULL COMMENT '目标ID',
`count_type` tinyint NOT NULL COMMENT '计数类型：1-点赞 2-收藏 3-评论 4-分享 5-浏览 6-粉丝 7-关注',
`count_value` bigint NOT NULL DEFAULT '0' COMMENT '计数值',
`deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_target_count` (`target_type`,`target_id`,`count_type`),
KEY `idx_target` (`target_type`,`target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='计数表'
```

### my_xhs_coupon.t_coupon_outbox
```sql
t_coupon_outbox
CREATE TABLE `t_coupon_outbox` (
`id` bigint NOT NULL,
`user_id` bigint NOT NULL,
`template_id` bigint NOT NULL,
`claim_no` varchar(64) NOT NULL,
`status` tinyint NOT NULL DEFAULT '0' COMMENT '0=??? 1=???',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
PRIMARY KEY (`id`),
UNIQUE KEY `uk_claim_no` (`claim_no`),
KEY `idx_status_created` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
```

### my_xhs_coupon.t_coupon_template
```sql
t_coupon_template
CREATE TABLE `t_coupon_template` (
`id` bigint NOT NULL COMMENT 'ID',
`name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '优惠券名称',
`type` tinyint NOT NULL COMMENT '类型：1-满减 2-折扣 3-无门槛',
`discount_value` decimal(10,2) NOT NULL COMMENT '优惠金额/折扣率',
`min_amount` decimal(10,2) DEFAULT '0.00' COMMENT '最低消费金额',
`total_count` int NOT NULL COMMENT '发放总量',
`remain_count` int NOT NULL COMMENT '剩余数量',
`per_user_limit` int NOT NULL DEFAULT '1' COMMENT '每人限领',
`valid_start` datetime NOT NULL COMMENT '有效期开始',
`valid_end` datetime NOT NULL COMMENT '有效期结束',
`status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：0-禁用 1-启用',
`deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='优惠券模板表'
```

### my_xhs_coupon.t_user_coupon
```sql
t_user_coupon
CREATE TABLE `t_user_coupon` (
`id` bigint NOT NULL COMMENT 'ID',
`user_id` bigint NOT NULL COMMENT '用户ID',
`coupon_id` bigint NOT NULL COMMENT '优惠券模板ID',
`claim_no` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '领券流水号(MQ msgId)，用于幂等去重',
`status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0-未使用 1-已使用 2-已过期',
`used_order_id` bigint DEFAULT NULL COMMENT '使用的订单ID',
`received_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
`used_at` datetime DEFAULT NULL COMMENT '使用时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_claim_no` (`claim_no`),
KEY `idx_user_id` (`user_id`),
KEY `idx_coupon_id` (`coupon_id`),
KEY `idx_user_coupon` (`user_id`,`coupon_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户优惠券表'
```

### my_xhs_product.t_category
```sql
t_category
CREATE TABLE `t_category` (
`id` bigint NOT NULL COMMENT '分类ID',
`name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '分类名称',
`parent_id` bigint NOT NULL DEFAULT '0' COMMENT '父分类ID（0为一级分类）',
`level` tinyint NOT NULL COMMENT '层级：1-一级 2-二级 3-三级',
`sort` int NOT NULL DEFAULT '0' COMMENT '排序值（越小越靠前）',
`icon` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '分类图标URL',
`status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：0-禁用 1-启用',
`deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
KEY `idx_parent_id` (`parent_id`),
KEY `idx_level` (`level`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品分类表（三级分类树）'
```

### my_xhs_product.t_sku
```sql
t_sku
CREATE TABLE `t_sku` (
`id` bigint NOT NULL COMMENT 'SKU ID',
`spu_id` bigint NOT NULL COMMENT 'SPU ID',
`name` varchar(256) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'SKU名称',
`price` decimal(10,2) NOT NULL COMMENT '价格',
`original_price` decimal(10,2) DEFAULT NULL COMMENT '原价',
`stock` int NOT NULL DEFAULT '0' COMMENT '库存(冗余，实际由库存服务管理)',
`specs` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '规格属性(JSON)',
`status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：0-下架 1-上架',
`deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
KEY `idx_spu_id` (`spu_id`),
KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品SKU表'
```

### my_xhs_product.t_spu
```sql
t_spu
CREATE TABLE `t_spu` (
`id` bigint NOT NULL COMMENT 'SPU ID',
`name` varchar(256) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '商品名称',
`category_id` bigint NOT NULL COMMENT '分类ID',
`brand_id` bigint DEFAULT NULL COMMENT '品牌ID',
`description` text COLLATE utf8mb4_unicode_ci COMMENT '商品描述',
`images` text COLLATE utf8mb4_unicode_ci COMMENT '商品图片列表(JSON)',
`status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：0-下架 1-上架',
`deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
KEY `idx_category_id` (`category_id`),
KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品SPU表'
```

### my_xhs_search.t_hot_search
```sql
t_hot_search
CREATE TABLE `t_hot_search` (
`id` bigint NOT NULL COMMENT 'ID',
`keyword` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '搜索关键词',
`search_count` bigint NOT NULL DEFAULT '0' COMMENT '搜索次数',
`status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：0-禁用 1-正常',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_keyword` (`keyword`),
KEY `idx_search_count` (`search_count`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='热搜词表'
```

### my_xhs_search.t_hot_search_snapshot
```sql
t_hot_search_snapshot
CREATE TABLE `t_hot_search_snapshot` (
`id` bigint NOT NULL,
`keyword` varchar(128) NOT NULL,
`score` double DEFAULT '0',
`rank_no` int DEFAULT '0',
`search_count` bigint DEFAULT '0',
`snapshot_time` datetime NOT NULL,
PRIMARY KEY (`id`),
KEY `idx_snapshot_time` (`snapshot_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
```

## MySQL 13308（order 库）

### my_xhs_order.t_order_no_mapping
```sql
t_order_no_mapping
CREATE TABLE `t_order_no_mapping` (
`id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增ID',
`order_no` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订单号',
`user_id` bigint NOT NULL COMMENT '用户ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_order_no` (`order_no`),
KEY `idx_order_id` (`order_id`)
) ENGINE=InnoDB AUTO_INCREMENT=126 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单号映射表（非分片键查询路由）'
```

### my_xhs_order_0.t_local_message_0
```sql
t_local_message_0
CREATE TABLE `t_local_message_0` (
`id` bigint NOT NULL COMMENT '消息ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`transaction_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事务ID',
`service_name` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '服务名',
`operation_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作类型',
`payload` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作参数JSON',
`status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0-待处理 1-成功 2-失败 3-死信',
`retry_count` int NOT NULL DEFAULT '0' COMMENT '重试次数',
`next_retry_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次重试时间',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
KEY `idx_transaction_id` (`transaction_id`),
KEY `idx_user_id` (`user_id`),
KEY `idx_status_created` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='本地消息表_0'
```

### my_xhs_order_0.t_local_message_1
```sql
t_local_message_1
CREATE TABLE `t_local_message_1` (
`id` bigint NOT NULL COMMENT '消息ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`transaction_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事务ID',
`service_name` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '服务名',
`operation_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作类型',
`payload` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作参数JSON',
`status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0-待处理 1-成功 2-失败 3-死信',
`retry_count` int NOT NULL DEFAULT '0' COMMENT '重试次数',
`next_retry_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次重试时间',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
KEY `idx_transaction_id` (`transaction_id`),
KEY `idx_user_id` (`user_id`),
KEY `idx_status_created` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='本地消息表_0'
```

### my_xhs_order_0.t_local_message_2
```sql
t_local_message_2
CREATE TABLE `t_local_message_2` (
`id` bigint NOT NULL COMMENT '消息ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`transaction_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事务ID',
`service_name` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '服务名',
`operation_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作类型',
`payload` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作参数JSON',
`status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0-待处理 1-成功 2-失败 3-死信',
`retry_count` int NOT NULL DEFAULT '0' COMMENT '重试次数',
`next_retry_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次重试时间',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
KEY `idx_transaction_id` (`transaction_id`),
KEY `idx_user_id` (`user_id`),
KEY `idx_status_created` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='本地消息表_0'
```

### my_xhs_order_0.t_local_message_3
```sql
t_local_message_3
CREATE TABLE `t_local_message_3` (
`id` bigint NOT NULL COMMENT '消息ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`transaction_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事务ID',
`service_name` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '服务名',
`operation_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作类型',
`payload` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作参数JSON',
`status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0-待处理 1-成功 2-失败 3-死信',
`retry_count` int NOT NULL DEFAULT '0' COMMENT '重试次数',
`next_retry_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次重试时间',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
KEY `idx_transaction_id` (`transaction_id`),
KEY `idx_user_id` (`user_id`),
KEY `idx_status_created` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='本地消息表_0'
```

### my_xhs_order_0.t_order_0
```sql
t_order_0
CREATE TABLE `t_order_0` (
`id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`order_no` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订单号',
`total_amount` decimal(10,2) NOT NULL COMMENT '订单总金额',
`pay_amount` decimal(10,2) NOT NULL COMMENT '实付金额',
`discount_amount` decimal(10,2) DEFAULT '0.00' COMMENT '优惠金额',
`coupon_id` bigint DEFAULT NULL COMMENT '使用的优惠券ID',
`status` tinyint NOT NULL DEFAULT '0' COMMENT '状态',
`address_snapshot` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '收货地址快照(JSON)',
`remark` varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单备注',
`paid_at` datetime DEFAULT NULL COMMENT '支付时间',
`delivered_at` datetime DEFAULT NULL COMMENT '发货时间',
`completed_at` datetime DEFAULT NULL COMMENT '完成时间',
`cancelled_at` datetime DEFAULT NULL COMMENT '取消时间',
`deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_order_no` (`order_no`),
KEY `idx_user_id` (`user_id`),
KEY `idx_status_created` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表_0'
```

### my_xhs_order_0.t_order_1
```sql
t_order_1
CREATE TABLE `t_order_1` (
`id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`order_no` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订单号',
`total_amount` decimal(10,2) NOT NULL COMMENT '订单总金额',
`pay_amount` decimal(10,2) NOT NULL COMMENT '实付金额',
`discount_amount` decimal(10,2) DEFAULT '0.00' COMMENT '优惠金额',
`coupon_id` bigint DEFAULT NULL COMMENT '使用的优惠券ID',
`status` tinyint NOT NULL DEFAULT '0' COMMENT '状态',
`address_snapshot` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '收货地址快照(JSON)',
`remark` varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单备注',
`paid_at` datetime DEFAULT NULL COMMENT '支付时间',
`delivered_at` datetime DEFAULT NULL COMMENT '发货时间',
`completed_at` datetime DEFAULT NULL COMMENT '完成时间',
`cancelled_at` datetime DEFAULT NULL COMMENT '取消时间',
`deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_order_no` (`order_no`),
KEY `idx_user_id` (`user_id`),
KEY `idx_status_created` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表_0'
```

### my_xhs_order_0.t_order_2
```sql
t_order_2
CREATE TABLE `t_order_2` (
`id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`order_no` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订单号',
`total_amount` decimal(10,2) NOT NULL COMMENT '订单总金额',
`pay_amount` decimal(10,2) NOT NULL COMMENT '实付金额',
`discount_amount` decimal(10,2) DEFAULT '0.00' COMMENT '优惠金额',
`coupon_id` bigint DEFAULT NULL COMMENT '使用的优惠券ID',
`status` tinyint NOT NULL DEFAULT '0' COMMENT '状态',
`address_snapshot` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '收货地址快照(JSON)',
`remark` varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单备注',
`paid_at` datetime DEFAULT NULL COMMENT '支付时间',
`delivered_at` datetime DEFAULT NULL COMMENT '发货时间',
`completed_at` datetime DEFAULT NULL COMMENT '完成时间',
`cancelled_at` datetime DEFAULT NULL COMMENT '取消时间',
`deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_order_no` (`order_no`),
KEY `idx_user_id` (`user_id`),
KEY `idx_status_created` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表_0'
```

### my_xhs_order_0.t_order_3
```sql
t_order_3
CREATE TABLE `t_order_3` (
`id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`order_no` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订单号',
`total_amount` decimal(10,2) NOT NULL COMMENT '订单总金额',
`pay_amount` decimal(10,2) NOT NULL COMMENT '实付金额',
`discount_amount` decimal(10,2) DEFAULT '0.00' COMMENT '优惠金额',
`coupon_id` bigint DEFAULT NULL COMMENT '使用的优惠券ID',
`status` tinyint NOT NULL DEFAULT '0' COMMENT '状态',
`address_snapshot` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '收货地址快照(JSON)',
`remark` varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单备注',
`paid_at` datetime DEFAULT NULL COMMENT '支付时间',
`delivered_at` datetime DEFAULT NULL COMMENT '发货时间',
`completed_at` datetime DEFAULT NULL COMMENT '完成时间',
`cancelled_at` datetime DEFAULT NULL COMMENT '取消时间',
`deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_order_no` (`order_no`),
KEY `idx_user_id` (`user_id`),
KEY `idx_status_created` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表_0'
```

### my_xhs_order_0.t_order_event_0
```sql
t_order_event_0
CREATE TABLE `t_order_event_0` (
`id` bigint NOT NULL AUTO_INCREMENT COMMENT '事件ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`event_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事件类型',
`from_status` tinyint DEFAULT NULL COMMENT '变更前状态',
`to_status` tinyint NOT NULL COMMENT '变更后状态',
`payload` text COLLATE utf8mb4_unicode_ci COMMENT '事件载荷（JSON）',
`event_seq` int NOT NULL COMMENT '事件序号',
`event_time` datetime(3) NOT NULL COMMENT '事件发生时间',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_order_event_seq` (`order_id`,`event_seq`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`),
KEY `idx_event_time` (`event_time`)
) ENGINE=InnoDB AUTO_INCREMENT=1289233889027244033 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单事件流(Event Sourcing)_0'
```

### my_xhs_order_0.t_order_event_1
```sql
t_order_event_1
CREATE TABLE `t_order_event_1` (
`id` bigint NOT NULL AUTO_INCREMENT COMMENT '事件ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`event_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事件类型',
`from_status` tinyint DEFAULT NULL COMMENT '变更前状态',
`to_status` tinyint NOT NULL COMMENT '变更后状态',
`payload` text COLLATE utf8mb4_unicode_ci COMMENT '事件载荷（JSON）',
`event_seq` int NOT NULL COMMENT '事件序号',
`event_time` datetime(3) NOT NULL COMMENT '事件发生时间',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_order_event_seq` (`order_id`,`event_seq`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`),
KEY `idx_event_time` (`event_time`)
) ENGINE=InnoDB AUTO_INCREMENT=1289234481443323906 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单事件流(Event Sourcing)_0'
```

### my_xhs_order_0.t_order_event_2
```sql
t_order_event_2
CREATE TABLE `t_order_event_2` (
`id` bigint NOT NULL AUTO_INCREMENT COMMENT '事件ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`event_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事件类型',
`from_status` tinyint DEFAULT NULL COMMENT '变更前状态',
`to_status` tinyint NOT NULL COMMENT '变更后状态',
`payload` text COLLATE utf8mb4_unicode_ci COMMENT '事件载荷（JSON）',
`event_seq` int NOT NULL COMMENT '事件序号',
`event_time` datetime(3) NOT NULL COMMENT '事件发生时间',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_order_event_seq` (`order_id`,`event_seq`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`),
KEY `idx_event_time` (`event_time`)
) ENGINE=InnoDB AUTO_INCREMENT=1289234491803254786 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单事件流(Event Sourcing)_0'
```

### my_xhs_order_0.t_order_event_3
```sql
t_order_event_3
CREATE TABLE `t_order_event_3` (
`id` bigint NOT NULL AUTO_INCREMENT COMMENT '事件ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`event_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事件类型',
`from_status` tinyint DEFAULT NULL COMMENT '变更前状态',
`to_status` tinyint NOT NULL COMMENT '变更后状态',
`payload` text COLLATE utf8mb4_unicode_ci COMMENT '事件载荷（JSON）',
`event_seq` int NOT NULL COMMENT '事件序号',
`event_time` datetime(3) NOT NULL COMMENT '事件发生时间',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_order_event_seq` (`order_id`,`event_seq`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`),
KEY `idx_event_time` (`event_time`)
) ENGINE=InnoDB AUTO_INCREMENT=1289233887462768641 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单事件流(Event Sourcing)_0'
```

### my_xhs_order_0.t_order_item_0
```sql
t_order_item_0
CREATE TABLE `t_order_item_0` (
`id` bigint NOT NULL COMMENT 'ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`sku_id` bigint NOT NULL COMMENT 'SKU ID',
`spu_id` bigint NOT NULL COMMENT 'SPU ID',
`sku_name` varchar(256) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'SKU名称快照',
`sku_image` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'SKU图片快照',
`price` decimal(10,2) NOT NULL COMMENT '单价快照',
`quantity` int NOT NULL COMMENT '数量',
`total_amount` decimal(10,2) NOT NULL COMMENT '小计金额',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表_0'
```

### my_xhs_order_0.t_order_item_1
```sql
t_order_item_1
CREATE TABLE `t_order_item_1` (
`id` bigint NOT NULL COMMENT 'ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`sku_id` bigint NOT NULL COMMENT 'SKU ID',
`spu_id` bigint NOT NULL COMMENT 'SPU ID',
`sku_name` varchar(256) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'SKU名称快照',
`sku_image` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'SKU图片快照',
`price` decimal(10,2) NOT NULL COMMENT '单价快照',
`quantity` int NOT NULL COMMENT '数量',
`total_amount` decimal(10,2) NOT NULL COMMENT '小计金额',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表_0'
```

### my_xhs_order_0.t_order_item_2
```sql
t_order_item_2
CREATE TABLE `t_order_item_2` (
`id` bigint NOT NULL COMMENT 'ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`sku_id` bigint NOT NULL COMMENT 'SKU ID',
`spu_id` bigint NOT NULL COMMENT 'SPU ID',
`sku_name` varchar(256) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'SKU名称快照',
`sku_image` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'SKU图片快照',
`price` decimal(10,2) NOT NULL COMMENT '单价快照',
`quantity` int NOT NULL COMMENT '数量',
`total_amount` decimal(10,2) NOT NULL COMMENT '小计金额',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表_0'
```

### my_xhs_order_0.t_order_item_3
```sql
t_order_item_3
CREATE TABLE `t_order_item_3` (
`id` bigint NOT NULL COMMENT 'ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`sku_id` bigint NOT NULL COMMENT 'SKU ID',
`spu_id` bigint NOT NULL COMMENT 'SPU ID',
`sku_name` varchar(256) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'SKU名称快照',
`sku_image` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'SKU图片快照',
`price` decimal(10,2) NOT NULL COMMENT '单价快照',
`quantity` int NOT NULL COMMENT '数量',
`total_amount` decimal(10,2) NOT NULL COMMENT '小计金额',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表_0'
```

### my_xhs_order_0.t_order_snapshot_0
```sql
t_order_snapshot_0
CREATE TABLE `t_order_snapshot_0` (
`id` bigint NOT NULL COMMENT 'ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`event` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '触发事件',
`snapshot_data` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订单完整快照(JSON)',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单快照表_0'
```

### my_xhs_order_0.t_order_snapshot_1
```sql
t_order_snapshot_1
CREATE TABLE `t_order_snapshot_1` (
`id` bigint NOT NULL COMMENT 'ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`event` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '触发事件',
`snapshot_data` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订单完整快照(JSON)',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单快照表_0'
```

### my_xhs_order_0.t_order_snapshot_2
```sql
t_order_snapshot_2
CREATE TABLE `t_order_snapshot_2` (
`id` bigint NOT NULL COMMENT 'ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`event` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '触发事件',
`snapshot_data` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订单完整快照(JSON)',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单快照表_0'
```

### my_xhs_order_0.t_order_snapshot_3
```sql
t_order_snapshot_3
CREATE TABLE `t_order_snapshot_3` (
`id` bigint NOT NULL COMMENT 'ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`event` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '触发事件',
`snapshot_data` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订单完整快照(JSON)',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单快照表_0'
```

### my_xhs_order_1.t_local_message_0
```sql
t_local_message_0
CREATE TABLE `t_local_message_0` (
`id` bigint NOT NULL COMMENT '消息ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`transaction_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事务ID',
`service_name` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '服务名',
`operation_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作类型',
`payload` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作参数JSON',
`status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0-待处理 1-成功 2-失败 3-死信',
`retry_count` int NOT NULL DEFAULT '0' COMMENT '重试次数',
`next_retry_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次重试时间',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
KEY `idx_transaction_id` (`transaction_id`),
KEY `idx_user_id` (`user_id`),
KEY `idx_status_created` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='本地消息表_0'
```

### my_xhs_order_1.t_local_message_1
```sql
t_local_message_1
CREATE TABLE `t_local_message_1` (
`id` bigint NOT NULL COMMENT '消息ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`transaction_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事务ID',
`service_name` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '服务名',
`operation_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作类型',
`payload` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作参数JSON',
`status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0-待处理 1-成功 2-失败 3-死信',
`retry_count` int NOT NULL DEFAULT '0' COMMENT '重试次数',
`next_retry_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次重试时间',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
KEY `idx_transaction_id` (`transaction_id`),
KEY `idx_user_id` (`user_id`),
KEY `idx_status_created` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='本地消息表_0'
```

### my_xhs_order_1.t_local_message_2
```sql
t_local_message_2
CREATE TABLE `t_local_message_2` (
`id` bigint NOT NULL COMMENT '消息ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`transaction_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事务ID',
`service_name` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '服务名',
`operation_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作类型',
`payload` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作参数JSON',
`status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0-待处理 1-成功 2-失败 3-死信',
`retry_count` int NOT NULL DEFAULT '0' COMMENT '重试次数',
`next_retry_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次重试时间',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
KEY `idx_transaction_id` (`transaction_id`),
KEY `idx_user_id` (`user_id`),
KEY `idx_status_created` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='本地消息表_0'
```

### my_xhs_order_1.t_local_message_3
```sql
t_local_message_3
CREATE TABLE `t_local_message_3` (
`id` bigint NOT NULL COMMENT '消息ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`transaction_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事务ID',
`service_name` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '服务名',
`operation_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作类型',
`payload` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作参数JSON',
`status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0-待处理 1-成功 2-失败 3-死信',
`retry_count` int NOT NULL DEFAULT '0' COMMENT '重试次数',
`next_retry_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次重试时间',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
KEY `idx_transaction_id` (`transaction_id`),
KEY `idx_user_id` (`user_id`),
KEY `idx_status_created` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='本地消息表_0'
```

### my_xhs_order_1.t_order_0
```sql
t_order_0
CREATE TABLE `t_order_0` (
`id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`order_no` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订单号',
`total_amount` decimal(10,2) NOT NULL COMMENT '订单总金额',
`pay_amount` decimal(10,2) NOT NULL COMMENT '实付金额',
`discount_amount` decimal(10,2) DEFAULT '0.00' COMMENT '优惠金额',
`coupon_id` bigint DEFAULT NULL COMMENT '使用的优惠券ID',
`status` tinyint NOT NULL DEFAULT '0' COMMENT '状态',
`address_snapshot` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '收货地址快照(JSON)',
`remark` varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单备注',
`paid_at` datetime DEFAULT NULL COMMENT '支付时间',
`delivered_at` datetime DEFAULT NULL COMMENT '发货时间',
`completed_at` datetime DEFAULT NULL COMMENT '完成时间',
`cancelled_at` datetime DEFAULT NULL COMMENT '取消时间',
`deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_order_no` (`order_no`),
KEY `idx_user_id` (`user_id`),
KEY `idx_status_created` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表_0'
```

### my_xhs_order_1.t_order_1
```sql
t_order_1
CREATE TABLE `t_order_1` (
`id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`order_no` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订单号',
`total_amount` decimal(10,2) NOT NULL COMMENT '订单总金额',
`pay_amount` decimal(10,2) NOT NULL COMMENT '实付金额',
`discount_amount` decimal(10,2) DEFAULT '0.00' COMMENT '优惠金额',
`coupon_id` bigint DEFAULT NULL COMMENT '使用的优惠券ID',
`status` tinyint NOT NULL DEFAULT '0' COMMENT '状态',
`address_snapshot` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '收货地址快照(JSON)',
`remark` varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单备注',
`paid_at` datetime DEFAULT NULL COMMENT '支付时间',
`delivered_at` datetime DEFAULT NULL COMMENT '发货时间',
`completed_at` datetime DEFAULT NULL COMMENT '完成时间',
`cancelled_at` datetime DEFAULT NULL COMMENT '取消时间',
`deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_order_no` (`order_no`),
KEY `idx_user_id` (`user_id`),
KEY `idx_status_created` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表_0'
```

### my_xhs_order_1.t_order_2
```sql
t_order_2
CREATE TABLE `t_order_2` (
`id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`order_no` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订单号',
`total_amount` decimal(10,2) NOT NULL COMMENT '订单总金额',
`pay_amount` decimal(10,2) NOT NULL COMMENT '实付金额',
`discount_amount` decimal(10,2) DEFAULT '0.00' COMMENT '优惠金额',
`coupon_id` bigint DEFAULT NULL COMMENT '使用的优惠券ID',
`status` tinyint NOT NULL DEFAULT '0' COMMENT '状态',
`address_snapshot` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '收货地址快照(JSON)',
`remark` varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单备注',
`paid_at` datetime DEFAULT NULL COMMENT '支付时间',
`delivered_at` datetime DEFAULT NULL COMMENT '发货时间',
`completed_at` datetime DEFAULT NULL COMMENT '完成时间',
`cancelled_at` datetime DEFAULT NULL COMMENT '取消时间',
`deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_order_no` (`order_no`),
KEY `idx_user_id` (`user_id`),
KEY `idx_status_created` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表_0'
```

### my_xhs_order_1.t_order_3
```sql
t_order_3
CREATE TABLE `t_order_3` (
`id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`order_no` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订单号',
`total_amount` decimal(10,2) NOT NULL COMMENT '订单总金额',
`pay_amount` decimal(10,2) NOT NULL COMMENT '实付金额',
`discount_amount` decimal(10,2) DEFAULT '0.00' COMMENT '优惠金额',
`coupon_id` bigint DEFAULT NULL COMMENT '使用的优惠券ID',
`status` tinyint NOT NULL DEFAULT '0' COMMENT '状态',
`address_snapshot` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '收货地址快照(JSON)',
`remark` varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单备注',
`paid_at` datetime DEFAULT NULL COMMENT '支付时间',
`delivered_at` datetime DEFAULT NULL COMMENT '发货时间',
`completed_at` datetime DEFAULT NULL COMMENT '完成时间',
`cancelled_at` datetime DEFAULT NULL COMMENT '取消时间',
`deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_order_no` (`order_no`),
KEY `idx_user_id` (`user_id`),
KEY `idx_status_created` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表_0'
```

### my_xhs_order_1.t_order_event_0
```sql
t_order_event_0
CREATE TABLE `t_order_event_0` (
`id` bigint NOT NULL AUTO_INCREMENT COMMENT '事件ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`event_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事件类型',
`from_status` tinyint DEFAULT NULL COMMENT '变更前状态',
`to_status` tinyint NOT NULL COMMENT '变更后状态',
`payload` text COLLATE utf8mb4_unicode_ci COMMENT '事件载荷（JSON）',
`event_seq` int NOT NULL COMMENT '事件序号',
`event_time` datetime(3) NOT NULL COMMENT '事件发生时间',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_order_event_seq` (`order_id`,`event_seq`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`),
KEY `idx_event_time` (`event_time`)
) ENGINE=InnoDB AUTO_INCREMENT=1292528299466899458 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单事件流(Event Sourcing)_0'
```

### my_xhs_order_1.t_order_event_1
```sql
t_order_event_1
CREATE TABLE `t_order_event_1` (
`id` bigint NOT NULL AUTO_INCREMENT COMMENT '事件ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`event_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事件类型',
`from_status` tinyint DEFAULT NULL COMMENT '变更前状态',
`to_status` tinyint NOT NULL COMMENT '变更后状态',
`payload` text COLLATE utf8mb4_unicode_ci COMMENT '事件载荷（JSON）',
`event_seq` int NOT NULL COMMENT '事件序号',
`event_time` datetime(3) NOT NULL COMMENT '事件发生时间',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_order_event_seq` (`order_id`,`event_seq`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`),
KEY `idx_event_time` (`event_time`)
) ENGINE=InnoDB AUTO_INCREMENT=1289241764084662273 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单事件流(Event Sourcing)_0'
```

### my_xhs_order_1.t_order_event_2
```sql
t_order_event_2
CREATE TABLE `t_order_event_2` (
`id` bigint NOT NULL AUTO_INCREMENT COMMENT '事件ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`event_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事件类型',
`from_status` tinyint DEFAULT NULL COMMENT '变更前状态',
`to_status` tinyint NOT NULL COMMENT '变更后状态',
`payload` text COLLATE utf8mb4_unicode_ci COMMENT '事件载荷（JSON）',
`event_seq` int NOT NULL COMMENT '事件序号',
`event_time` datetime(3) NOT NULL COMMENT '事件发生时间',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_order_event_seq` (`order_id`,`event_seq`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`),
KEY `idx_event_time` (`event_time`)
) ENGINE=InnoDB AUTO_INCREMENT=1291421314692497409 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单事件流(Event Sourcing)_0'
```

### my_xhs_order_1.t_order_event_3
```sql
t_order_event_3
CREATE TABLE `t_order_event_3` (
`id` bigint NOT NULL AUTO_INCREMENT COMMENT '事件ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`event_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事件类型',
`from_status` tinyint DEFAULT NULL COMMENT '变更前状态',
`to_status` tinyint NOT NULL COMMENT '变更后状态',
`payload` text COLLATE utf8mb4_unicode_ci COMMENT '事件载荷（JSON）',
`event_seq` int NOT NULL COMMENT '事件序号',
`event_time` datetime(3) NOT NULL COMMENT '事件发生时间',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_order_event_seq` (`order_id`,`event_seq`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`),
KEY `idx_event_time` (`event_time`)
) ENGINE=InnoDB AUTO_INCREMENT=1289233887882199042 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单事件流(Event Sourcing)_0'
```

### my_xhs_order_1.t_order_item_0
```sql
t_order_item_0
CREATE TABLE `t_order_item_0` (
`id` bigint NOT NULL COMMENT 'ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`sku_id` bigint NOT NULL COMMENT 'SKU ID',
`spu_id` bigint NOT NULL COMMENT 'SPU ID',
`sku_name` varchar(256) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'SKU名称快照',
`sku_image` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'SKU图片快照',
`price` decimal(10,2) NOT NULL COMMENT '单价快照',
`quantity` int NOT NULL COMMENT '数量',
`total_amount` decimal(10,2) NOT NULL COMMENT '小计金额',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表_0'
```

### my_xhs_order_1.t_order_item_1
```sql
t_order_item_1
CREATE TABLE `t_order_item_1` (
`id` bigint NOT NULL COMMENT 'ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`sku_id` bigint NOT NULL COMMENT 'SKU ID',
`spu_id` bigint NOT NULL COMMENT 'SPU ID',
`sku_name` varchar(256) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'SKU名称快照',
`sku_image` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'SKU图片快照',
`price` decimal(10,2) NOT NULL COMMENT '单价快照',
`quantity` int NOT NULL COMMENT '数量',
`total_amount` decimal(10,2) NOT NULL COMMENT '小计金额',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表_0'
```

### my_xhs_order_1.t_order_item_2
```sql
t_order_item_2
CREATE TABLE `t_order_item_2` (
`id` bigint NOT NULL COMMENT 'ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`sku_id` bigint NOT NULL COMMENT 'SKU ID',
`spu_id` bigint NOT NULL COMMENT 'SPU ID',
`sku_name` varchar(256) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'SKU名称快照',
`sku_image` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'SKU图片快照',
`price` decimal(10,2) NOT NULL COMMENT '单价快照',
`quantity` int NOT NULL COMMENT '数量',
`total_amount` decimal(10,2) NOT NULL COMMENT '小计金额',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表_0'
```

### my_xhs_order_1.t_order_item_3
```sql
t_order_item_3
CREATE TABLE `t_order_item_3` (
`id` bigint NOT NULL COMMENT 'ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`sku_id` bigint NOT NULL COMMENT 'SKU ID',
`spu_id` bigint NOT NULL COMMENT 'SPU ID',
`sku_name` varchar(256) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'SKU名称快照',
`sku_image` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'SKU图片快照',
`price` decimal(10,2) NOT NULL COMMENT '单价快照',
`quantity` int NOT NULL COMMENT '数量',
`total_amount` decimal(10,2) NOT NULL COMMENT '小计金额',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表_0'
```

### my_xhs_order_1.t_order_snapshot_0
```sql
t_order_snapshot_0
CREATE TABLE `t_order_snapshot_0` (
`id` bigint NOT NULL COMMENT 'ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`event` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '触发事件',
`snapshot_data` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订单完整快照(JSON)',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单快照表_0'
```

### my_xhs_order_1.t_order_snapshot_1
```sql
t_order_snapshot_1
CREATE TABLE `t_order_snapshot_1` (
`id` bigint NOT NULL COMMENT 'ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`event` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '触发事件',
`snapshot_data` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订单完整快照(JSON)',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单快照表_0'
```

### my_xhs_order_1.t_order_snapshot_2
```sql
t_order_snapshot_2
CREATE TABLE `t_order_snapshot_2` (
`id` bigint NOT NULL COMMENT 'ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`event` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '触发事件',
`snapshot_data` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订单完整快照(JSON)',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单快照表_0'
```

### my_xhs_order_1.t_order_snapshot_3
```sql
t_order_snapshot_3
CREATE TABLE `t_order_snapshot_3` (
`id` bigint NOT NULL COMMENT 'ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`event` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '触发事件',
`snapshot_data` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订单完整快照(JSON)',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单快照表_0'
```

### my_xhs_order_2.t_local_message_0
```sql
t_local_message_0
CREATE TABLE `t_local_message_0` (
`id` bigint NOT NULL COMMENT '消息ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`transaction_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事务ID',
`service_name` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '服务名',
`operation_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作类型',
`payload` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作参数JSON',
`status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0-待处理 1-成功 2-失败 3-死信',
`retry_count` int NOT NULL DEFAULT '0' COMMENT '重试次数',
`next_retry_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次重试时间',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
KEY `idx_transaction_id` (`transaction_id`),
KEY `idx_user_id` (`user_id`),
KEY `idx_status_created` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='本地消息表_0'
```

### my_xhs_order_2.t_local_message_1
```sql
t_local_message_1
CREATE TABLE `t_local_message_1` (
`id` bigint NOT NULL COMMENT '消息ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`transaction_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事务ID',
`service_name` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '服务名',
`operation_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作类型',
`payload` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作参数JSON',
`status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0-待处理 1-成功 2-失败 3-死信',
`retry_count` int NOT NULL DEFAULT '0' COMMENT '重试次数',
`next_retry_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次重试时间',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
KEY `idx_transaction_id` (`transaction_id`),
KEY `idx_user_id` (`user_id`),
KEY `idx_status_created` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='本地消息表_0'
```

### my_xhs_order_2.t_local_message_2
```sql
t_local_message_2
CREATE TABLE `t_local_message_2` (
`id` bigint NOT NULL COMMENT '消息ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`transaction_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事务ID',
`service_name` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '服务名',
`operation_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作类型',
`payload` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作参数JSON',
`status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0-待处理 1-成功 2-失败 3-死信',
`retry_count` int NOT NULL DEFAULT '0' COMMENT '重试次数',
`next_retry_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次重试时间',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
KEY `idx_transaction_id` (`transaction_id`),
KEY `idx_user_id` (`user_id`),
KEY `idx_status_created` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='本地消息表_0'
```

### my_xhs_order_2.t_local_message_3
```sql
t_local_message_3
CREATE TABLE `t_local_message_3` (
`id` bigint NOT NULL COMMENT '消息ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`transaction_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事务ID',
`service_name` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '服务名',
`operation_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作类型',
`payload` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作参数JSON',
`status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0-待处理 1-成功 2-失败 3-死信',
`retry_count` int NOT NULL DEFAULT '0' COMMENT '重试次数',
`next_retry_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次重试时间',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
KEY `idx_transaction_id` (`transaction_id`),
KEY `idx_user_id` (`user_id`),
KEY `idx_status_created` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='本地消息表_0'
```

### my_xhs_order_2.t_order_0
```sql
t_order_0
CREATE TABLE `t_order_0` (
`id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`order_no` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订单号',
`total_amount` decimal(10,2) NOT NULL COMMENT '订单总金额',
`pay_amount` decimal(10,2) NOT NULL COMMENT '实付金额',
`discount_amount` decimal(10,2) DEFAULT '0.00' COMMENT '优惠金额',
`coupon_id` bigint DEFAULT NULL COMMENT '使用的优惠券ID',
`status` tinyint NOT NULL DEFAULT '0' COMMENT '状态',
`address_snapshot` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '收货地址快照(JSON)',
`remark` varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单备注',
`paid_at` datetime DEFAULT NULL COMMENT '支付时间',
`delivered_at` datetime DEFAULT NULL COMMENT '发货时间',
`completed_at` datetime DEFAULT NULL COMMENT '完成时间',
`cancelled_at` datetime DEFAULT NULL COMMENT '取消时间',
`deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_order_no` (`order_no`),
KEY `idx_user_id` (`user_id`),
KEY `idx_status_created` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表_0'
```

### my_xhs_order_2.t_order_1
```sql
t_order_1
CREATE TABLE `t_order_1` (
`id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`order_no` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订单号',
`total_amount` decimal(10,2) NOT NULL COMMENT '订单总金额',
`pay_amount` decimal(10,2) NOT NULL COMMENT '实付金额',
`discount_amount` decimal(10,2) DEFAULT '0.00' COMMENT '优惠金额',
`coupon_id` bigint DEFAULT NULL COMMENT '使用的优惠券ID',
`status` tinyint NOT NULL DEFAULT '0' COMMENT '状态',
`address_snapshot` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '收货地址快照(JSON)',
`remark` varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单备注',
`paid_at` datetime DEFAULT NULL COMMENT '支付时间',
`delivered_at` datetime DEFAULT NULL COMMENT '发货时间',
`completed_at` datetime DEFAULT NULL COMMENT '完成时间',
`cancelled_at` datetime DEFAULT NULL COMMENT '取消时间',
`deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_order_no` (`order_no`),
KEY `idx_user_id` (`user_id`),
KEY `idx_status_created` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表_0'
```

### my_xhs_order_2.t_order_2
```sql
t_order_2
CREATE TABLE `t_order_2` (
`id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`order_no` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订单号',
`total_amount` decimal(10,2) NOT NULL COMMENT '订单总金额',
`pay_amount` decimal(10,2) NOT NULL COMMENT '实付金额',
`discount_amount` decimal(10,2) DEFAULT '0.00' COMMENT '优惠金额',
`coupon_id` bigint DEFAULT NULL COMMENT '使用的优惠券ID',
`status` tinyint NOT NULL DEFAULT '0' COMMENT '状态',
`address_snapshot` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '收货地址快照(JSON)',
`remark` varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单备注',
`paid_at` datetime DEFAULT NULL COMMENT '支付时间',
`delivered_at` datetime DEFAULT NULL COMMENT '发货时间',
`completed_at` datetime DEFAULT NULL COMMENT '完成时间',
`cancelled_at` datetime DEFAULT NULL COMMENT '取消时间',
`deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_order_no` (`order_no`),
KEY `idx_user_id` (`user_id`),
KEY `idx_status_created` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表_0'
```

### my_xhs_order_2.t_order_3
```sql
t_order_3
CREATE TABLE `t_order_3` (
`id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`order_no` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订单号',
`total_amount` decimal(10,2) NOT NULL COMMENT '订单总金额',
`pay_amount` decimal(10,2) NOT NULL COMMENT '实付金额',
`discount_amount` decimal(10,2) DEFAULT '0.00' COMMENT '优惠金额',
`coupon_id` bigint DEFAULT NULL COMMENT '使用的优惠券ID',
`status` tinyint NOT NULL DEFAULT '0' COMMENT '状态',
`address_snapshot` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '收货地址快照(JSON)',
`remark` varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单备注',
`paid_at` datetime DEFAULT NULL COMMENT '支付时间',
`delivered_at` datetime DEFAULT NULL COMMENT '发货时间',
`completed_at` datetime DEFAULT NULL COMMENT '完成时间',
`cancelled_at` datetime DEFAULT NULL COMMENT '取消时间',
`deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_order_no` (`order_no`),
KEY `idx_user_id` (`user_id`),
KEY `idx_status_created` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表_0'
```

### my_xhs_order_2.t_order_event_0
```sql
t_order_event_0
CREATE TABLE `t_order_event_0` (
`id` bigint NOT NULL AUTO_INCREMENT COMMENT '事件ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`event_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事件类型',
`from_status` tinyint DEFAULT NULL COMMENT '变更前状态',
`to_status` tinyint NOT NULL COMMENT '变更后状态',
`payload` text COLLATE utf8mb4_unicode_ci COMMENT '事件载荷（JSON）',
`event_seq` int NOT NULL COMMENT '事件序号',
`event_time` datetime(3) NOT NULL COMMENT '事件发生时间',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_order_event_seq` (`order_id`,`event_seq`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`),
KEY `idx_event_time` (`event_time`)
) ENGINE=InnoDB AUTO_INCREMENT=1292880841669427201 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单事件流(Event Sourcing)_0'
```

### my_xhs_order_2.t_order_event_1
```sql
t_order_event_1
CREATE TABLE `t_order_event_1` (
`id` bigint NOT NULL AUTO_INCREMENT COMMENT '事件ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`event_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事件类型',
`from_status` tinyint DEFAULT NULL COMMENT '变更前状态',
`to_status` tinyint NOT NULL COMMENT '变更后状态',
`payload` text COLLATE utf8mb4_unicode_ci COMMENT '事件载荷（JSON）',
`event_seq` int NOT NULL COMMENT '事件序号',
`event_time` datetime(3) NOT NULL COMMENT '事件发生时间',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_order_event_seq` (`order_id`,`event_seq`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`),
KEY `idx_event_time` (`event_time`)
) ENGINE=InnoDB AUTO_INCREMENT=1289234482361876482 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单事件流(Event Sourcing)_0'
```

### my_xhs_order_2.t_order_event_2
```sql
t_order_event_2
CREATE TABLE `t_order_event_2` (
`id` bigint NOT NULL AUTO_INCREMENT COMMENT '事件ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`event_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事件类型',
`from_status` tinyint DEFAULT NULL COMMENT '变更前状态',
`to_status` tinyint NOT NULL COMMENT '变更后状态',
`payload` text COLLATE utf8mb4_unicode_ci COMMENT '事件载荷（JSON）',
`event_seq` int NOT NULL COMMENT '事件序号',
`event_time` datetime(3) NOT NULL COMMENT '事件发生时间',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_order_event_seq` (`order_id`,`event_seq`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`),
KEY `idx_event_time` (`event_time`)
) ENGINE=InnoDB AUTO_INCREMENT=1289233889782218753 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单事件流(Event Sourcing)_0'
```

### my_xhs_order_2.t_order_event_3
```sql
t_order_event_3
CREATE TABLE `t_order_event_3` (
`id` bigint NOT NULL AUTO_INCREMENT COMMENT '事件ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`event_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事件类型',
`from_status` tinyint DEFAULT NULL COMMENT '变更前状态',
`to_status` tinyint NOT NULL COMMENT '变更后状态',
`payload` text COLLATE utf8mb4_unicode_ci COMMENT '事件载荷（JSON）',
`event_seq` int NOT NULL COMMENT '事件序号',
`event_time` datetime(3) NOT NULL COMMENT '事件发生时间',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_order_event_seq` (`order_id`,`event_seq`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`),
KEY `idx_event_time` (`event_time`)
) ENGINE=InnoDB AUTO_INCREMENT=1289233888305823745 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单事件流(Event Sourcing)_0'
```

### my_xhs_order_2.t_order_item_0
```sql
t_order_item_0
CREATE TABLE `t_order_item_0` (
`id` bigint NOT NULL COMMENT 'ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`sku_id` bigint NOT NULL COMMENT 'SKU ID',
`spu_id` bigint NOT NULL COMMENT 'SPU ID',
`sku_name` varchar(256) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'SKU名称快照',
`sku_image` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'SKU图片快照',
`price` decimal(10,2) NOT NULL COMMENT '单价快照',
`quantity` int NOT NULL COMMENT '数量',
`total_amount` decimal(10,2) NOT NULL COMMENT '小计金额',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表_0'
```

### my_xhs_order_2.t_order_item_1
```sql
t_order_item_1
CREATE TABLE `t_order_item_1` (
`id` bigint NOT NULL COMMENT 'ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`sku_id` bigint NOT NULL COMMENT 'SKU ID',
`spu_id` bigint NOT NULL COMMENT 'SPU ID',
`sku_name` varchar(256) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'SKU名称快照',
`sku_image` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'SKU图片快照',
`price` decimal(10,2) NOT NULL COMMENT '单价快照',
`quantity` int NOT NULL COMMENT '数量',
`total_amount` decimal(10,2) NOT NULL COMMENT '小计金额',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表_0'
```

### my_xhs_order_2.t_order_item_2
```sql
t_order_item_2
CREATE TABLE `t_order_item_2` (
`id` bigint NOT NULL COMMENT 'ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`sku_id` bigint NOT NULL COMMENT 'SKU ID',
`spu_id` bigint NOT NULL COMMENT 'SPU ID',
`sku_name` varchar(256) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'SKU名称快照',
`sku_image` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'SKU图片快照',
`price` decimal(10,2) NOT NULL COMMENT '单价快照',
`quantity` int NOT NULL COMMENT '数量',
`total_amount` decimal(10,2) NOT NULL COMMENT '小计金额',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表_0'
```

### my_xhs_order_2.t_order_item_3
```sql
t_order_item_3
CREATE TABLE `t_order_item_3` (
`id` bigint NOT NULL COMMENT 'ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`sku_id` bigint NOT NULL COMMENT 'SKU ID',
`spu_id` bigint NOT NULL COMMENT 'SPU ID',
`sku_name` varchar(256) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'SKU名称快照',
`sku_image` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'SKU图片快照',
`price` decimal(10,2) NOT NULL COMMENT '单价快照',
`quantity` int NOT NULL COMMENT '数量',
`total_amount` decimal(10,2) NOT NULL COMMENT '小计金额',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表_0'
```

### my_xhs_order_2.t_order_snapshot_0
```sql
t_order_snapshot_0
CREATE TABLE `t_order_snapshot_0` (
`id` bigint NOT NULL COMMENT 'ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`event` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '触发事件',
`snapshot_data` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订单完整快照(JSON)',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单快照表_0'
```

### my_xhs_order_2.t_order_snapshot_1
```sql
t_order_snapshot_1
CREATE TABLE `t_order_snapshot_1` (
`id` bigint NOT NULL COMMENT 'ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`event` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '触发事件',
`snapshot_data` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订单完整快照(JSON)',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单快照表_0'
```

### my_xhs_order_2.t_order_snapshot_2
```sql
t_order_snapshot_2
CREATE TABLE `t_order_snapshot_2` (
`id` bigint NOT NULL COMMENT 'ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`event` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '触发事件',
`snapshot_data` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订单完整快照(JSON)',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单快照表_0'
```

### my_xhs_order_2.t_order_snapshot_3
```sql
t_order_snapshot_3
CREATE TABLE `t_order_snapshot_3` (
`id` bigint NOT NULL COMMENT 'ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`event` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '触发事件',
`snapshot_data` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订单完整快照(JSON)',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单快照表_0'
```

### my_xhs_order_3.t_local_message_0
```sql
t_local_message_0
CREATE TABLE `t_local_message_0` (
`id` bigint NOT NULL COMMENT '消息ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`transaction_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事务ID',
`service_name` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '服务名',
`operation_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作类型',
`payload` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作参数JSON',
`status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0-待处理 1-成功 2-失败 3-死信',
`retry_count` int NOT NULL DEFAULT '0' COMMENT '重试次数',
`next_retry_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次重试时间',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
KEY `idx_transaction_id` (`transaction_id`),
KEY `idx_user_id` (`user_id`),
KEY `idx_status_created` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='本地消息表_0'
```

### my_xhs_order_3.t_local_message_1
```sql
t_local_message_1
CREATE TABLE `t_local_message_1` (
`id` bigint NOT NULL COMMENT '消息ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`transaction_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事务ID',
`service_name` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '服务名',
`operation_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作类型',
`payload` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作参数JSON',
`status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0-待处理 1-成功 2-失败 3-死信',
`retry_count` int NOT NULL DEFAULT '0' COMMENT '重试次数',
`next_retry_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次重试时间',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
KEY `idx_transaction_id` (`transaction_id`),
KEY `idx_user_id` (`user_id`),
KEY `idx_status_created` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='本地消息表_0'
```

### my_xhs_order_3.t_local_message_2
```sql
t_local_message_2
CREATE TABLE `t_local_message_2` (
`id` bigint NOT NULL COMMENT '消息ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`transaction_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事务ID',
`service_name` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '服务名',
`operation_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作类型',
`payload` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作参数JSON',
`status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0-待处理 1-成功 2-失败 3-死信',
`retry_count` int NOT NULL DEFAULT '0' COMMENT '重试次数',
`next_retry_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次重试时间',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
KEY `idx_transaction_id` (`transaction_id`),
KEY `idx_user_id` (`user_id`),
KEY `idx_status_created` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='本地消息表_0'
```

### my_xhs_order_3.t_local_message_3
```sql
t_local_message_3
CREATE TABLE `t_local_message_3` (
`id` bigint NOT NULL COMMENT '消息ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`transaction_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事务ID',
`service_name` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '服务名',
`operation_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作类型',
`payload` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作参数JSON',
`status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0-待处理 1-成功 2-失败 3-死信',
`retry_count` int NOT NULL DEFAULT '0' COMMENT '重试次数',
`next_retry_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次重试时间',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
KEY `idx_transaction_id` (`transaction_id`),
KEY `idx_user_id` (`user_id`),
KEY `idx_status_created` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='本地消息表_0'
```

### my_xhs_order_3.t_order_0
```sql
t_order_0
CREATE TABLE `t_order_0` (
`id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`order_no` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订单号',
`total_amount` decimal(10,2) NOT NULL COMMENT '订单总金额',
`pay_amount` decimal(10,2) NOT NULL COMMENT '实付金额',
`discount_amount` decimal(10,2) DEFAULT '0.00' COMMENT '优惠金额',
`coupon_id` bigint DEFAULT NULL COMMENT '使用的优惠券ID',
`status` tinyint NOT NULL DEFAULT '0' COMMENT '状态',
`address_snapshot` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '收货地址快照(JSON)',
`remark` varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单备注',
`paid_at` datetime DEFAULT NULL COMMENT '支付时间',
`delivered_at` datetime DEFAULT NULL COMMENT '发货时间',
`completed_at` datetime DEFAULT NULL COMMENT '完成时间',
`cancelled_at` datetime DEFAULT NULL COMMENT '取消时间',
`deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_order_no` (`order_no`),
KEY `idx_user_id` (`user_id`),
KEY `idx_status_created` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表_0'
```

### my_xhs_order_3.t_order_1
```sql
t_order_1
CREATE TABLE `t_order_1` (
`id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`order_no` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订单号',
`total_amount` decimal(10,2) NOT NULL COMMENT '订单总金额',
`pay_amount` decimal(10,2) NOT NULL COMMENT '实付金额',
`discount_amount` decimal(10,2) DEFAULT '0.00' COMMENT '优惠金额',
`coupon_id` bigint DEFAULT NULL COMMENT '使用的优惠券ID',
`status` tinyint NOT NULL DEFAULT '0' COMMENT '状态',
`address_snapshot` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '收货地址快照(JSON)',
`remark` varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单备注',
`paid_at` datetime DEFAULT NULL COMMENT '支付时间',
`delivered_at` datetime DEFAULT NULL COMMENT '发货时间',
`completed_at` datetime DEFAULT NULL COMMENT '完成时间',
`cancelled_at` datetime DEFAULT NULL COMMENT '取消时间',
`deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_order_no` (`order_no`),
KEY `idx_user_id` (`user_id`),
KEY `idx_status_created` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表_0'
```

### my_xhs_order_3.t_order_2
```sql
t_order_2
CREATE TABLE `t_order_2` (
`id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`order_no` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订单号',
`total_amount` decimal(10,2) NOT NULL COMMENT '订单总金额',
`pay_amount` decimal(10,2) NOT NULL COMMENT '实付金额',
`discount_amount` decimal(10,2) DEFAULT '0.00' COMMENT '优惠金额',
`coupon_id` bigint DEFAULT NULL COMMENT '使用的优惠券ID',
`status` tinyint NOT NULL DEFAULT '0' COMMENT '状态',
`address_snapshot` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '收货地址快照(JSON)',
`remark` varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单备注',
`paid_at` datetime DEFAULT NULL COMMENT '支付时间',
`delivered_at` datetime DEFAULT NULL COMMENT '发货时间',
`completed_at` datetime DEFAULT NULL COMMENT '完成时间',
`cancelled_at` datetime DEFAULT NULL COMMENT '取消时间',
`deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_order_no` (`order_no`),
KEY `idx_user_id` (`user_id`),
KEY `idx_status_created` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表_0'
```

### my_xhs_order_3.t_order_3
```sql
t_order_3
CREATE TABLE `t_order_3` (
`id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`order_no` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订单号',
`total_amount` decimal(10,2) NOT NULL COMMENT '订单总金额',
`pay_amount` decimal(10,2) NOT NULL COMMENT '实付金额',
`discount_amount` decimal(10,2) DEFAULT '0.00' COMMENT '优惠金额',
`coupon_id` bigint DEFAULT NULL COMMENT '使用的优惠券ID',
`status` tinyint NOT NULL DEFAULT '0' COMMENT '状态',
`address_snapshot` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '收货地址快照(JSON)',
`remark` varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单备注',
`paid_at` datetime DEFAULT NULL COMMENT '支付时间',
`delivered_at` datetime DEFAULT NULL COMMENT '发货时间',
`completed_at` datetime DEFAULT NULL COMMENT '完成时间',
`cancelled_at` datetime DEFAULT NULL COMMENT '取消时间',
`deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_order_no` (`order_no`),
KEY `idx_user_id` (`user_id`),
KEY `idx_status_created` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表_0'
```

### my_xhs_order_3.t_order_event_0
```sql
t_order_event_0
CREATE TABLE `t_order_event_0` (
`id` bigint NOT NULL AUTO_INCREMENT COMMENT '事件ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`event_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事件类型',
`from_status` tinyint DEFAULT NULL COMMENT '变更前状态',
`to_status` tinyint NOT NULL COMMENT '变更后状态',
`payload` text COLLATE utf8mb4_unicode_ci COMMENT '事件载荷（JSON）',
`event_seq` int NOT NULL COMMENT '事件序号',
`event_time` datetime(3) NOT NULL COMMENT '事件发生时间',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_order_event_seq` (`order_id`,`event_seq`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`),
KEY `idx_event_time` (`event_time`)
) ENGINE=InnoDB AUTO_INCREMENT=1291350367327305729 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单事件流(Event Sourcing)_0'
```

### my_xhs_order_3.t_order_event_1
```sql
t_order_event_1
CREATE TABLE `t_order_event_1` (
`id` bigint NOT NULL AUTO_INCREMENT COMMENT '事件ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`event_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事件类型',
`from_status` tinyint DEFAULT NULL COMMENT '变更前状态',
`to_status` tinyint NOT NULL COMMENT '变更后状态',
`payload` text COLLATE utf8mb4_unicode_ci COMMENT '事件载荷（JSON）',
`event_seq` int NOT NULL COMMENT '事件序号',
`event_time` datetime(3) NOT NULL COMMENT '事件发生时间',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_order_event_seq` (`order_id`,`event_seq`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`),
KEY `idx_event_time` (`event_time`)
) ENGINE=InnoDB AUTO_INCREMENT=1289234482827444225 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单事件流(Event Sourcing)_0'
```

### my_xhs_order_3.t_order_event_2
```sql
t_order_event_2
CREATE TABLE `t_order_event_2` (
`id` bigint NOT NULL AUTO_INCREMENT COMMENT '事件ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`event_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事件类型',
`from_status` tinyint DEFAULT NULL COMMENT '变更前状态',
`to_status` tinyint NOT NULL COMMENT '变更后状态',
`payload` text COLLATE utf8mb4_unicode_ci COMMENT '事件载荷（JSON）',
`event_seq` int NOT NULL COMMENT '事件序号',
`event_time` datetime(3) NOT NULL COMMENT '事件发生时间',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_order_event_seq` (`order_id`,`event_seq`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`),
KEY `idx_event_time` (`event_time`)
) ENGINE=InnoDB AUTO_INCREMENT=1289233887060115458 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单事件流(Event Sourcing)_0'
```

### my_xhs_order_3.t_order_event_3
```sql
t_order_event_3
CREATE TABLE `t_order_event_3` (
`id` bigint NOT NULL AUTO_INCREMENT COMMENT '事件ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`event_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事件类型',
`from_status` tinyint DEFAULT NULL COMMENT '变更前状态',
`to_status` tinyint NOT NULL COMMENT '变更后状态',
`payload` text COLLATE utf8mb4_unicode_ci COMMENT '事件载荷（JSON）',
`event_seq` int NOT NULL COMMENT '事件序号',
`event_time` datetime(3) NOT NULL COMMENT '事件发生时间',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_order_event_seq` (`order_id`,`event_seq`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`),
KEY `idx_event_time` (`event_time`)
) ENGINE=InnoDB AUTO_INCREMENT=1291369930014146561 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单事件流(Event Sourcing)_0'
```

### my_xhs_order_3.t_order_item_0
```sql
t_order_item_0
CREATE TABLE `t_order_item_0` (
`id` bigint NOT NULL COMMENT 'ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`sku_id` bigint NOT NULL COMMENT 'SKU ID',
`spu_id` bigint NOT NULL COMMENT 'SPU ID',
`sku_name` varchar(256) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'SKU名称快照',
`sku_image` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'SKU图片快照',
`price` decimal(10,2) NOT NULL COMMENT '单价快照',
`quantity` int NOT NULL COMMENT '数量',
`total_amount` decimal(10,2) NOT NULL COMMENT '小计金额',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表_0'
```

### my_xhs_order_3.t_order_item_1
```sql
t_order_item_1
CREATE TABLE `t_order_item_1` (
`id` bigint NOT NULL COMMENT 'ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`sku_id` bigint NOT NULL COMMENT 'SKU ID',
`spu_id` bigint NOT NULL COMMENT 'SPU ID',
`sku_name` varchar(256) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'SKU名称快照',
`sku_image` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'SKU图片快照',
`price` decimal(10,2) NOT NULL COMMENT '单价快照',
`quantity` int NOT NULL COMMENT '数量',
`total_amount` decimal(10,2) NOT NULL COMMENT '小计金额',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表_0'
```

### my_xhs_order_3.t_order_item_2
```sql
t_order_item_2
CREATE TABLE `t_order_item_2` (
`id` bigint NOT NULL COMMENT 'ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`sku_id` bigint NOT NULL COMMENT 'SKU ID',
`spu_id` bigint NOT NULL COMMENT 'SPU ID',
`sku_name` varchar(256) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'SKU名称快照',
`sku_image` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'SKU图片快照',
`price` decimal(10,2) NOT NULL COMMENT '单价快照',
`quantity` int NOT NULL COMMENT '数量',
`total_amount` decimal(10,2) NOT NULL COMMENT '小计金额',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表_0'
```

### my_xhs_order_3.t_order_item_3
```sql
t_order_item_3
CREATE TABLE `t_order_item_3` (
`id` bigint NOT NULL COMMENT 'ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`sku_id` bigint NOT NULL COMMENT 'SKU ID',
`spu_id` bigint NOT NULL COMMENT 'SPU ID',
`sku_name` varchar(256) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'SKU名称快照',
`sku_image` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'SKU图片快照',
`price` decimal(10,2) NOT NULL COMMENT '单价快照',
`quantity` int NOT NULL COMMENT '数量',
`total_amount` decimal(10,2) NOT NULL COMMENT '小计金额',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表_0'
```

### my_xhs_order_3.t_order_snapshot_0
```sql
t_order_snapshot_0
CREATE TABLE `t_order_snapshot_0` (
`id` bigint NOT NULL COMMENT 'ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`event` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '触发事件',
`snapshot_data` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订单完整快照(JSON)',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单快照表_0'
```

### my_xhs_order_3.t_order_snapshot_1
```sql
t_order_snapshot_1
CREATE TABLE `t_order_snapshot_1` (
`id` bigint NOT NULL COMMENT 'ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`event` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '触发事件',
`snapshot_data` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订单完整快照(JSON)',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单快照表_0'
```

### my_xhs_order_3.t_order_snapshot_2
```sql
t_order_snapshot_2
CREATE TABLE `t_order_snapshot_2` (
`id` bigint NOT NULL COMMENT 'ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`event` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '触发事件',
`snapshot_data` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订单完整快照(JSON)',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单快照表_0'
```

### my_xhs_order_3.t_order_snapshot_3
```sql
t_order_snapshot_3
CREATE TABLE `t_order_snapshot_3` (
`id` bigint NOT NULL COMMENT 'ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID（分片键）',
`event` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '触发事件',
`snapshot_data` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订单完整快照(JSON)',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
PRIMARY KEY (`id`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单快照表_0'
```

### my_xhs_payment.t_payment
```sql
t_payment
CREATE TABLE `t_payment` (
`id` bigint NOT NULL COMMENT 'ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID',
`payment_no` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '支付流水号',
`amount` decimal(10,2) NOT NULL COMMENT '支付金额',
`pay_type` tinyint NOT NULL COMMENT '支付方式：1-支付宝(Mock) 2-微信(Mock)',
`status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0-待支付 1-支付成功 2-支付失败 3-已退款',
`paid_at` datetime DEFAULT NULL COMMENT '支付成功时间',
`deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_payment_no` (`payment_no`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='支付记录表'
```

### my_xhs_payment.t_refund
```sql
t_refund
CREATE TABLE `t_refund` (
`id` bigint NOT NULL COMMENT '退款单ID',
`payment_id` bigint NOT NULL COMMENT '关联支付单ID',
`order_id` bigint NOT NULL COMMENT '订单ID',
`user_id` bigint NOT NULL COMMENT '用户ID',
`refund_no` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '退款单号',
`refund_amount` decimal(10,2) NOT NULL COMMENT '退款金额',
`reason` varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '退款原因',
`status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0-退款中 1-退款成功 2-退款失败 3-退款关闭',
`refund_type` tinyint NOT NULL DEFAULT '1' COMMENT '退款类型：1-仅退款 2-退货退款',
`refund_channel` tinyint NOT NULL DEFAULT '1' COMMENT '退款渠道：1-原路退回 2-退到余额',
`success_at` datetime DEFAULT NULL COMMENT '退款成功时间',
`deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_refund_no` (`refund_no`),
KEY `idx_payment_id` (`payment_id`),
KEY `idx_order_id` (`order_id`),
KEY `idx_user_id` (`user_id`),
KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='退款单表'
```

## MySQL 13309（inventory 库）

### my_xhs_inventory.t_inventory
```sql
t_inventory
CREATE TABLE `t_inventory` (
`id` bigint NOT NULL COMMENT 'ID',
`sku_id` bigint NOT NULL COMMENT 'SKU ID',
`available_stock` int NOT NULL DEFAULT '0' COMMENT '可用库存',
`locked_stock` int NOT NULL DEFAULT '0' COMMENT '锁定库存',
`freezing_stock` int NOT NULL DEFAULT '0' COMMENT 'TCC冻结库存',
`deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
PRIMARY KEY (`id`),
UNIQUE KEY `uk_sku_id` (`sku_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='库存表'
```

### my_xhs_inventory.t_inventory_compensation
```sql
t_inventory_compensation
CREATE TABLE `t_inventory_compensation` (
`id` bigint NOT NULL,
`order_id` bigint NOT NULL,
`sku_id` bigint NOT NULL,
`quantity` int NOT NULL,
`action` varchar(32) NOT NULL,
`status` tinyint DEFAULT '0',
`created_at` datetime DEFAULT CURRENT_TIMESTAMP,
PRIMARY KEY (`id`),
KEY `idx_status` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
```

### my_xhs_inventory.t_inventory_outbox
```sql
t_inventory_outbox
CREATE TABLE `t_inventory_outbox` (
`id` bigint NOT NULL AUTO_INCREMENT,
`order_id` bigint NOT NULL,
`sku_id` bigint NOT NULL,
`quantity` int NOT NULL,
`action` varchar(32) NOT NULL,
`status` tinyint DEFAULT '0',
`created_at` datetime DEFAULT CURRENT_TIMESTAMP,
PRIMARY KEY (`id`),
KEY `idx_status_created` (`status`,`created_at`)
) ENGINE=InnoDB AUTO_INCREMENT=18 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
```

### my_xhs_inventory.t_tcc_fence
```sql
t_tcc_fence
CREATE TABLE `t_tcc_fence` (
`xid` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '全局事务ID',
`branch_id` bigint NOT NULL COMMENT '分支事务ID',
`action_name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'TCC方法名',
`status` tinyint NOT NULL COMMENT '1-已Try 2-已Confirm 3-已Cancel',
`gmt_create` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
`gmt_modified` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
PRIMARY KEY (`xid`,`branch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='TCC防悬挂表'
```

### my_xhs_inventory.t_tcc_freeze_detail
```sql
t_tcc_freeze_detail
CREATE TABLE `t_tcc_freeze_detail` (
`id` bigint NOT NULL,
`xid` varchar(128) NOT NULL,
`branch_id` bigint NOT NULL,
`sku_id` bigint NOT NULL,
`quantity` int NOT NULL,
`status` tinyint DEFAULT '1',
`created_at` datetime DEFAULT CURRENT_TIMESTAMP,
PRIMARY KEY (`id`),
KEY `idx_xid` (`xid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
```

