从今天之后的一段时间内，华仔会带着大家一起从零开始搭建并研发一套高并发的电商实战项目，这里会涉及到很多互联网大厂开发过程中所使用的核心技术和架构设计模式，希望大家学完之后可以用到自己的简历中。

#

\# XXL\-JOB

\# Copyright (c) 2015-present, xuxueli.

CREATE database if NOT EXISTS \`xxl\_job\` default character set utf8mb4 collate utf8mb4\_unicode\_ci;

use \`xxl\_job\`;

SET NAMES utf8mb4;

CREATE TABLE \`xxl\_job\_info\`

(

\`id\` int(11) NOT NULL AUTO\_INCREMENT,

\`job\_group\` int(11) NOT NULL COMMENT '执行器主键ID',

\`job\_desc\` varchar(255) NOT NULL,

\`add\_time\` datetime DEFAULT NULL,

\`update\_time\` datetime DEFAULT NULL,

\`author\` varchar(64) DEFAULT NULL COMMENT '作者',

\`alarm\_email\` varchar(255) DEFAULT NULL COMMENT '报警邮件',

\`schedule\_type\` varchar(50) NOT NULL DEFAULT 'NONE' COMMENT '调度类型',

\`schedule\_conf\` varchar(128) DEFAULT NULL COMMENT '调度配置，值含义取决于调度类型',

\`misfire\_strategy\` varchar(50) NOT NULL DEFAULT 'DO\_NOTHING' COMMENT '调度过期策略',

\`executor\_route\_strategy\` varchar(50) DEFAULT NULL COMMENT '执行器路由策略',

\`executor\_handler\` varchar(255) DEFAULT NULL COMMENT '执行器任务handler',

\`executor\_param\` varchar(512) DEFAULT NULL COMMENT '执行器任务参数',

\`executor\_block\_strategy\` varchar(50) DEFAULT NULL COMMENT '阻塞处理策略',

\`executor\_timeout\` int(11) NOT NULL DEFAULT '0' COMMENT '任务执行超时时间，单位秒',

\`executor\_fail\_retry\_count\` int(11) NOT NULL DEFAULT '0' COMMENT '失败重试次数',

\`glue\_type\` varchar(50) NOT NULL COMMENT 'GLUE类型',

\`glue\_source\` mediumtext COMMENT 'GLUE源代码',

\`glue\_remark\` varchar(128) DEFAULT NULL COMMENT 'GLUE备注',

\`glue\_updatetime\` datetime DEFAULT NULL COMMENT 'GLUE更新时间',

\`child\_jobid\` varchar(255) DEFAULT NULL COMMENT '子任务ID，多个逗号分隔',

\`trigger\_status\` tinyint(4) NOT NULL DEFAULT '0' COMMENT '调度状态：0-停止，1-运行',

\`trigger\_last\_time\` bigint(13) NOT NULL DEFAULT '0' COMMENT '上次调度时间',

\`trigger\_next\_time\` bigint(13) NOT NULL DEFAULT '0' COMMENT '下次调度时间',

PRIMARY KEY (\`id\`)

) ENGINE \= InnoDB

DEFAULT CHARSET \= utf8mb4;

CREATE TABLE \`xxl\_job\_log\`

(

\`id\` bigint(20) NOT NULL AUTO\_INCREMENT,

\`job\_group\` int(11) NOT NULL COMMENT '执行器主键ID',

\`job\_id\` int(11) NOT NULL COMMENT '任务，主键ID',

\`executor\_address\` varchar(255) DEFAULT NULL COMMENT '执行器地址，本次执行的地址',

\`executor\_handler\` varchar(255) DEFAULT NULL COMMENT '执行器任务handler',

\`executor\_param\` varchar(512) DEFAULT NULL COMMENT '执行器任务参数',

\`executor\_sharding\_param\` varchar(20) DEFAULT NULL COMMENT '执行器任务分片参数，格式如 1/2',

\`executor\_fail\_retry\_count\` int(11) NOT NULL DEFAULT '0' COMMENT '失败重试次数',

\`trigger\_time\` datetime DEFAULT NULL COMMENT '调度-时间',

\`trigger\_code\` int(11) NOT NULL COMMENT '调度-结果',

\`trigger\_msg\` text COMMENT '调度-日志',

\`handle\_time\` datetime DEFAULT NULL COMMENT '执行-时间',

\`handle\_code\` int(11) NOT NULL COMMENT '执行-状态',

\`handle\_msg\` text COMMENT '执行-日志',

\`alarm\_status\` tinyint(4) NOT NULL DEFAULT '0' COMMENT '告警状态：0-默认、1-无需告警、2-告警成功、3-告警失败',

PRIMARY KEY (\`id\`),

KEY \`I\_trigger\_time\` (\`trigger\_time\`),

KEY \`I\_handle\_code\` (\`handle\_code\`),

KEY \`I\_jobid\_jobgroup\` (\`job\_id\`,\`job\_group\`),

KEY \`I\_job\_id\` (\`job\_id\`)

) ENGINE \= InnoDB

DEFAULT CHARSET \= utf8mb4;

CREATE TABLE \`xxl\_job\_log\_report\`

(

\`id\` int(11) NOT NULL AUTO\_INCREMENT,

\`trigger\_day\` datetime DEFAULT NULL COMMENT '调度-时间',

\`running\_count\` int(11) NOT NULL DEFAULT '0' COMMENT '运行中-日志数量',

\`suc\_count\` int(11) NOT NULL DEFAULT '0' COMMENT '执行成功-日志数量',

\`fail\_count\` int(11) NOT NULL DEFAULT '0' COMMENT '执行失败-日志数量',

\`update\_time\` datetime DEFAULT NULL,

PRIMARY KEY (\`id\`),

UNIQUE KEY \`i\_trigger\_day\` (\`trigger\_day\`) USING BTREE

) ENGINE \= InnoDB

DEFAULT CHARSET \= utf8mb4;

CREATE TABLE \`xxl\_job\_logglue\`

(

\`id\` int(11) NOT NULL AUTO\_INCREMENT,

\`job\_id\` int(11) NOT NULL COMMENT '任务，主键ID',

\`glue\_type\` varchar(50) DEFAULT NULL COMMENT 'GLUE类型',

\`glue\_source\` mediumtext COMMENT 'GLUE源代码',

\`glue\_remark\` varchar(128) NOT NULL COMMENT 'GLUE备注',

\`add\_time\` datetime DEFAULT NULL,

\`update\_time\` datetime DEFAULT NULL,

PRIMARY KEY (\`id\`)

) ENGINE \= InnoDB

DEFAULT CHARSET \= utf8mb4;

CREATE TABLE \`xxl\_job\_registry\`

(

\`id\` int(11) NOT NULL AUTO\_INCREMENT,

\`registry\_group\` varchar(50) NOT NULL,

\`registry\_key\` varchar(255) NOT NULL,

\`registry\_value\` varchar(255) NOT NULL,

\`update\_time\` datetime DEFAULT NULL,

PRIMARY KEY (\`id\`),

UNIQUE KEY \`i\_g\_k\_v\` (\`registry\_group\`, \`registry\_key\`, \`registry\_value\`) USING BTREE

) ENGINE \= InnoDB

DEFAULT CHARSET \= utf8mb4;

CREATE TABLE \`xxl\_job\_group\`

(

\`id\` int(11) NOT NULL AUTO\_INCREMENT,

\`app\_name\` varchar(64) NOT NULL COMMENT '执行器AppName',

\`title\` varchar(12) NOT NULL COMMENT '执行器名称',

\`address\_type\` tinyint(4) NOT NULL DEFAULT '0' COMMENT '执行器地址类型：0=自动注册、1=手动录入',

\`address\_list\` text COMMENT '执行器地址列表，多地址逗号分隔',

\`update\_time\` datetime DEFAULT NULL,

PRIMARY KEY (\`id\`)

) ENGINE \= InnoDB

DEFAULT CHARSET \= utf8mb4;

CREATE TABLE \`xxl\_job\_user\`

(

\`id\` int(11) NOT NULL AUTO\_INCREMENT,

\`username\` varchar(50) NOT NULL COMMENT '账号',

\`password\` varchar(50) NOT NULL COMMENT '密码',

\`role\` tinyint(4) NOT NULL COMMENT '角色：0-普通用户、1-管理员',

\`permission\` varchar(255) DEFAULT NULL COMMENT '权限：执行器ID列表，多个逗号分割',

PRIMARY KEY (\`id\`),

UNIQUE KEY \`i\_username\` (\`username\`) USING BTREE

) ENGINE \= InnoDB

DEFAULT CHARSET \= utf8mb4;

CREATE TABLE \`xxl\_job\_lock\`

(

\`lock\_name\` varchar(50) NOT NULL COMMENT '锁名称',

PRIMARY KEY (\`lock\_name\`)

) ENGINE \= InnoDB

DEFAULT CHARSET \= utf8mb4;

\## —————————————————————— init data ——————————————————

INSERT INTO \`xxl\_job\_group\`(\`id\`, \`app\_name\`, \`title\`, \`address\_type\`, \`address\_list\`, \`update\_time\`)

VALUES (1, 'xxl-job-executor-sample', '示例执行器', 0, NULL, '2018-11-03 22:21:31');

INSERT INTO \`xxl\_job\_info\`(\`id\`, \`job\_group\`, \`job\_desc\`, \`add\_time\`, \`update\_time\`, \`author\`, \`alarm\_email\`,

\`schedule\_type\`, \`schedule\_conf\`, \`misfire\_strategy\`, \`executor\_route\_strategy\`,

\`executor\_handler\`, \`executor\_param\`, \`executor\_block\_strategy\`, \`executor\_timeout\`,

\`executor\_fail\_retry\_count\`, \`glue\_type\`, \`glue\_source\`, \`glue\_remark\`, \`glue\_updatetime\`,

\`child\_jobid\`)

VALUES (1, 1, '测试任务1', '2018-11-03 22:21:31', '2018-11-03 22:21:31', 'XXL', '', 'CRON', '0 0 0 \* \* ? \*',

'DO\_NOTHING', 'FIRST', 'demoJobHandler', '', 'SERIAL\_EXECUTION', 0, 0, 'BEAN', '', 'GLUE代码初始化',

'2018-11-03 22:21:31', '');

INSERT INTO \`xxl\_job\_user\`(\`id\`, \`username\`, \`password\`, \`role\`, \`permission\`)

VALUES (1, 'admin', 'e10adc3949ba59abbe56e057f20f883e', 1, NULL);

INSERT INTO \`xxl\_job\_lock\` (\`lock\_name\`)

VALUES ('schedule\_lock');

commit;

XXL-Job 执行器是一个运行在目标服务器上的应用程序模块，用于实际执行由调度中心下发的任务。执行器可以看作是任务的“工作节点”，负责接收调度中心发送的任务调度请求并执行具体的任务逻辑。