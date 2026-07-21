从今天之后的一段时间内，华仔会带着大家一起从零开始搭建并研发一套高并发的电商实战项目，这里会涉及到很多互联网大厂开发过程中所使用的核心技术和架构设计模式，希望大家学完之后可以用到自己的简历中。

接下来我们会重点架构设计和开发一下我们高并发电商实战前端 Web 项目的相关功能 。

这是第七十九篇，本篇我们继续进行电商实战项目设计与开发，本篇我们实战下「**订单服务接入分库分表功能**」。

文章汇总位置：[https://wx.zsxq.com/dweb2/index/columns/51122554151214](https://wx.zsxq.com/dweb2/index/columns/51122554151214)

![](images/Fnl1ueruAt4exbk9U2e5CcbjFHDI.png)

源码授权与获取地址：[https://articles.zsxq.com/id\_1s85grnaae4p.html](https://articles.zsxq.com/id_1s85grnaae4p.html)

![](images/FmkphgNM2yJBSqCq4raWXJA2r2Ah.png)

本章源码地址：[https://gitcode.net/u011359591/huazai-ecshop/-/tree/ecshop-chapter-79](https://gitcode.net/u011359591/huazai-ecshop/-/tree/ecshop-chapter-79)

## **01 前言**

通过前面几篇的「**订单业务介绍**」、「**订单架构设计**」、「**订单核心链路场景功能实现**」等，订单服务相关功能已经接近尾声了， 会在接入前端项目时进行「**订单列表**」、「**订单详情**」 功能实现。

  
[【电商实战项目第七十三篇】华仔电商实战订单服务业务场景介绍与架构设计](https://articles.zsxq.com/id_7n1znf4mnd0b.html)

[【电商实战项目第七十四篇】华仔电商实战订单服务核心链路状态机设计与功能实现](https://articles.zsxq.com/id_fzcjo8ib47j7.html)

[【电商实战项目第七十五篇】华仔电商实战订单服务核心链路面临的技术挑战与解决方案](https://articles.zsxq.com/id_vn41o1wlhmgs.html)

[【电商实战项目第七十六篇】华仔电商实战订单服务核心链路生单流程基于 RocketMQ 事务消息架构设计与功能实现](https://articles.zsxq.com/id_euh6q0p89qu9.html)

[【电商实战项目第七十七篇】华仔电商实战订单服务核心链路取消订单流程基于 RocketMQ 事务消息架构设计与功能实现](https://articles.zsxq.com/id_cn8e9rarxown.html)

[【电商实战项目第七十八篇】华仔电商实战订单服务核心链路超时关单基于RocketMQ延时消息与定时任务架构设计与功能实现](https://articles.zsxq.com/id_0r2a8c5lv2pj.html)

今天我们来重点实战下「**订单服务分库分表功能**」。

##   
**02 分库分表理论知识**

## **2.1 业务场景分析**

在开始之前，我们先来剖析下业务场景，在我们的「**订单服务**」的业务场景中，主要有两个场景需要处理「**分库分表**」：

1.  「**创建订单**」场景主要是用户来创建的，按照市面流行的淘宝、京东、拼多多非官方数据统计，用户数量已有近几亿。这里我们假设每个用户买家平均会下 100 单，那订单列表预估会接近几百亿数据量。
2.  「**用户查看订单**」场景主要是 C 端用户来查询的，对于我们千万级的用户量来说，「**用户查询订单记录**」数据量也是非常大的，因此也需要进行分库。
3.  「**商家查看订单**」场景主要是 B 端商家来查询的，对于我们千万级的商家量来说，「**商家查询订单记录**」数据量也是非常大的，因此也需要进行分库。

##   
**2.2 分库分表概述**

简单的来说，分库就是是将原本的单库拆分为多库，分表是将原来的单表拆分为多表。

![](images/FtsFtdbekeR38AhPkK0eDufLPyGx.png)

如图所示：

分库有两种模式：

1.  垂直分库：按业务模块将数据库拆分成多个独立的数据库，比如电商库，当业务拆分后就是用户库、订单库、商品库。垂直分库把一个库的压力分摊到多个库，提升了一些数据库性能，但并没有解决由于单表数据量过大导致的性能问题，所以就需要配合后边的分表来解决。![](images/FjZ3h0sTYKipKXC-TGjZP4FUYmai.png)
2.  水平分库：它把同一个表按一定规则拆分到不同的数据库中，每个库可以位于不同的服务器上，以此实现水平扩展，是一种常见的提升数据库性能的方式。比如订单库，分库之后就是订单库\_0，订单库\_1, 订单库\_2。![](images/Fp46zzMsKAnfd6ZMRTjKcg0-DA6W.png)

分表也有两种模式：

1.  垂直分表：针对业务上字段比较多的大表进行的，一般是把业务宽表中比较独立的字段，或者不常用的字段拆分到单独的数据表中，是一种大表拆小表的模式。比如订单表，拆分后就是订单表、订单扩展表。![](images/Fp1q0_fw1-5nWi2mRmxRNmiJ6KSv.png)
2.  水平分表：是在同一个数据库内，把一张大数据量的表按一定规则，切分成多个结构完全相同表，而每个表只存原表的一部分数据。比如订单表，拆分后就是订单表\_0，订单表\_1，订单表\_2，...., 订单表\_10。![](images/FiXWCWs6tLpF_R-ZzR-JCrM7PFlv.png)

那么垂直拆分与水平拆分的优缺点又有哪些呢？ 我们来总结下：

垂直拆分的优点：

1.  提高性能：每个数据库专注于特定的业务模块。
2.  降低耦合度：不同业务模块之间互不影响。

垂直拆分的缺点：

1.  跨库关联查询困难：需要通过中间件或服务层处理跨库关联。
2.  事务管理复杂：跨库事务难以保证一致性。

水平拆分的优点：

1.  提高并发能力：每个分片可以独立处理请求。
2.  降低单表压力：避免单表数据量过大导致的性能问题。

水平拆分的缺点：

1.  数据一致性难保证：跨分片事务难以管理。
2.  查询复杂度增加：需要聚合多个分片的结果。

### **2.2.1 什么时候开始分表**

当出现以下三种情况的时候，我们需要考虑分表：

1.  单表的数据量过大，比如单表要保持在 2000 万以下。
2.  单表存在较高的写入场景，可能引发行锁竞争，比如用户高频领券场景。
3.  当表中包含大量的 TEXT、LONGTEXT 或 BLOB 等大字段。

分表的数量通常按照「**数据的存量**」、「**每年的增量**」来预估的。假如我们的存量数据为 1 亿，每年的增量大概 1000 万，那么我们的分表数量要在 10 张以上，才能保证最近 10 年不需要考虑重复分表的情况。

分表数量计算公式 = （存量数据 + 每年的增量 \* 期望保存的年数）/ 2000 万 === 向上取最近的 2 的幂。

### **2.2.2 什么时候开始分库**

当出现以下两种情况时，我们需要考虑通过分库提升整体系统的性能：

1.  当单库支持的连接数已经不足以满足业务客户端需求。
2.  当数据量已经超过单库实例的处理能力。

### **2.2.3 什么时候既分库又分表**

当出现以下两种场景下，需要进行分库又分表：

1.  **高并发写入场景**：当业务面临高并发的写入请求时，单库可能无法满足写入压力，就可以将数据按照一定规则拆分到多库中，每个数据库处理部分数据的写入请求，从而提高写入性能。
2.  **海量数据场景**：随着数据量的不断增加，单库的存储和查询性能可能逐渐下降。就将数据按照一定的规则拆分到多表中，每个表存储部分数据，从而分散数据的存储压力，提高查询性能。

## **2.3 分库分表设计**

这里需要考虑两种情况。

### **2.3.1 如何选择分片键**

1.  数据均匀性：分片键应该保证数据的均匀分布在各个分片上，避免出现热点数据集中在某个分片上的情况。
2.  业务关联性：分片键应该与业务关联紧密，这样可以避免跨分片查询和跨库事务的复杂性。
3.  数据不可变：一旦选择了分片键，它应该是不可变的，不能随着业务的变化而频繁修改。

### **2.3.2 分库分表算法有哪些**

分库分表的算法会根据业务的不同而变化，在业界常用的有三种：

1.  范围分片（Range Sharding）：按某个字段的范围进行拆分。
2.  按用户 ID 的范围将数据拆分为 user\_0（ID 1-1000）、user\_1（ID 1001-2000）等。
3.  按时间范围将日志数据拆分为 log\_202301, log\_202302 等。![](images/FmYy_ART2z35sb7Fbex_GBx-7uDh.png)
4.  哈希取模分片（Hash Sharding）: 按某个字段的哈希值进行拆分。
5.  用户 ID 的哈希值决定数据存入哪个分片。
6.  商品 ID 的哈希值决定数据存入哪个分片。![](images/FgXR4_x9gNBUNS_K4xfZ2mq0Vwwb.png)
7.  一致性哈希（Consistent Hashing）：一种更高级的哈希策略，能够有效避免数据倾斜和热点问题。
8.  使用一致性哈希算法将用户 ID 映射到特定的分片。
9.  当新增或删除分片时，只需要重新映射少量数据。

## **2.4 分库分表框架选择**

市面上目前支持「**分库分表**」的开源框架主要有两款：

1.  Mycat：[https://gitee.com/MycatOne/Mycat2](https://gitee.com/MycatOne/Mycat2)
2.  ShardingSphere：[https://shardingsphere.apache.org](https://shardingsphere.apache.org/)

这里我们选择使用 [ShardingSphere](http://shardingsphere/) 来作为我们项目的「**分库分表**」框架，主要是它是 Apache 旗下的项目，有较大的社区支持，得到了广泛的认可和使用。它的社区活跃度较高，更新和新功能的开发较为迅速。最重要的是： [ShardingSphere](http://shardingsphere/) 提供了更为灵活和丰富的分库分表策略，支持广泛的分片规则，包括范围、哈希、复合分片等。

shardingsphere 特点：

1.  适用于任何基于Java的ORM框架，如：JPA, Hibernate, Mybatis, Spring JDBC Template或直接使用JDBC
2.  基于任何第三方的数据库连接池，如：DBCP, C3P0, BoneCP, Druid, HikariCP等
3.  支持任意实现JDBC规范的数据库。目前支持MySQL，Oracle，SQLServer和PostgreSQL

其架构图如下：

![](images/Fp7v_okAZkkBpJPcnEd09Yc6dOyi.png)

![](images/Fqc3Aj5geYUf49MeuAS8Cla7NEcY.png)

## **2.5 用户/商户查询订单列表如何分库分表**

其实整个项目从「**单库单表**」改造成「**分库分表**」，要改造的工程量还是比较大的，需要将对应服务的「**主键/分片键**」进行统一处理，前面已经进行「**优惠券服务的分库分表**」的改造，今天我们来进行「**订单服务的分库分表**」的改造。

###   
**2.5.1 订单表如何分库分表**

这里有两张表需要拆分，订单表和订单流水日志表：

CREATE TABLE \`trade\_order\` (

\`id\` bigint NOT NULL AUTO\_INCREMENT COMMENT '主键id',

\`biz\_identifier\` varchar(128) COLLATE utf8mb4\_general\_ci NOT NULL DEFAULT '' COMMENT '幂等号',

\`order\_id\` bigint unsigned NOT NULL DEFAULT '0' COMMENT '订单号',

\`buyer\_id\` bigint unsigned NOT NULL DEFAULT '0' COMMENT '买家id',

\`seller\_id\` bigint unsigned NOT NULL DEFAULT '0' COMMENT '卖家id',

\`order\_status\` tinyint unsigned NOT NULL DEFAULT '1' COMMENT '订单状态 1:已创建, 2:已确认, 3:已支付 4:已履约 5:出库中, 6:配送中, 7:已签收, 8:已取消, 9:已拒收, 127:无效订单',

\`close\_type\` tinyint unsigned NOT NULL DEFAULT '0' COMMENT '关单类型 1:超时关单 2:用户主动关闭/取消 ',

\`sku\_id\` bigint unsigned NOT NULL DEFAULT '0' COMMENT '商品id',

\`sku\_name\` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4\_general\_ci DEFAULT '' COMMENT '商品标题',

\`sku\_main\_url\` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4\_general\_ci DEFAULT '' COMMENT '商品封面图',

\`sku\_price\` decimal(16,2) NOT NULL COMMENT '商品单价',

\`sku\_count\` smallint unsigned NOT NULL DEFAULT '0' COMMENT '商品数量',

\`coupon\_id\` bigint unsigned NOT NULL DEFAULT '0' COMMENT '优惠券id',

\`coupon\_name\` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4\_general\_ci NOT NULL DEFAULT '' COMMENT '优惠券名称',

\`order\_amount\` decimal(16,2) NOT NULL COMMENT '订单金额',

\`pay\_amount\` decimal(16,2) NOT NULL COMMENT '交易支付金额',

\`pay\_type\` tinyint unsigned NOT NULL DEFAULT '0' COMMENT '支付方式 1:微信支付 2:支付宝支付',

\`pay\_time\` datetime DEFAULT CURRENT\_TIMESTAMP COMMENT '支付时间',

\`pay\_trade\_no\` varchar(128) COLLATE utf8mb4\_general\_ci NOT NULL DEFAULT '' COMMENT '支付流水号',

\`order\_confirmed\_time\` datetime DEFAULT CURRENT\_TIMESTAMP COMMENT '订单确认时间',

\`order\_finished\_time\` datetime DEFAULT CURRENT\_TIMESTAMP COMMENT '订单完结时间',

\`order\_close\_time\` datetime DEFAULT CURRENT\_TIMESTAMP COMMENT '关单时间',

\`delete\_status\` tinyint unsigned NOT NULL DEFAULT '0' COMMENT '删除状态 0:未删除 1:已删除',

\`lock\_version\` int unsigned NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',

\`snapshot\_version\` int unsigned NOT NULL DEFAULT '0' COMMENT '快照版本号',

\`create\_time\` datetime DEFAULT CURRENT\_TIMESTAMP COMMENT '订单创建时间',

\`update\_time\` datetime DEFAULT CURRENT\_TIMESTAMP ON UPDATE CURRENT\_TIMESTAMP COMMENT '订单更新时间',

PRIMARY KEY (\`id\`),

UNIQUE KEY \`idx\_order\_id\` (\`order\_id\`,\`biz\_identifier\`) USING BTREE,

KEY \`idx\_identifier\_buyer\` (\`biz\_identifier\`,\`buyer\_id\`) USING BTREE,

KEY \`idx\_create\_time\` (\`create\_time\`) USING BTREE

) ENGINE\=InnoDB AUTO\_INCREMENT\=1 DEFAULT CHARSET\=utf8mb4 COLLATE=utf8mb4\_general\_ci COMMENT\='订单表';

CREATE TABLE \`trade\_order\_snapshot\` (

\`id\` bigint NOT NULL AUTO\_INCREMENT COMMENT '主键id',

\`snapshot\_identifier\` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4\_general\_ci NOT NULL DEFAULT '' COMMENT '快照幂等号',

\`order\_id\` bigint unsigned NOT NULL DEFAULT '0' COMMENT '订单号',

\`snapshot\_type\` tinyint unsigned NOT NULL COMMENT '订单快照类型',

\`snapshot\_json\` longtext COLLATE utf8mb4\_general\_ci NOT NULL COMMENT '订单快照内容',

\`snapshot\_version\` int unsigned NOT NULL DEFAULT '0' COMMENT '订单快照版本号',

\`create\_time\` datetime DEFAULT CURRENT\_TIMESTAMP COMMENT '订单创建时间',

\`update\_time\` datetime DEFAULT CURRENT\_TIMESTAMP ON UPDATE CURRENT\_TIMESTAMP COMMENT '订单更新时间',

PRIMARY KEY (\`id\`),

UNIQUE KEY \`idx\_identifier\` (\`order\_id\`,\`snapshot\_identifier\`,\`snapshot\_type\`) USING BTREE

) ENGINE\=InnoDB AUTO\_INCREMENT\=1 DEFAULT CHARSET\=utf8mb4 COLLATE=utf8mb4\_general\_ci COMMENT\='订单快照流水表';

ALTER TABLE \`huazai\_order\`.\`trade\_order\_snapshot\`

ADD COLUMN \`buyer\_id\` bigint(20) NOT NULL DEFAULT 0 COMMENT '买家id' AFTER \`snapshot\_identifier\`,

ADD COLUMN \`seller\_id\` bigint(20) NOT NULL DEFAULT 0 COMMENT '卖家id' AFTER \`buyer\_id\`;

### **2.5.1.1 订单分库分表总体规划**

![](images/FgIWDWMWF_xuagLk1u5IvjVJMJV_.png)

这里我们需要区分「**C 端买家场景**」和 「**B 端商家场景**」，进而分为 「**C 端买家库**」和 「**B 端商家库**」，由于我们目前做的主要是 「**C 端买家场景**」，这里我们只使用 「**buyer\_id、 order\_id**」来作为我们「**C 端买家库分库分表**」的最终「**分片键**」。至于 「**B 端商家场景**」查询的话可以通过 binlog + canal 同步数据到 「**B 端商家库**」，然后再通过 「**seller\_id、order\_id**」作为 「**分片键**」进行查询。

###   
**2.5.1.2 订单买家库表具体的分片规则**

### **1、 订单买家表（trade\_order）**

> actualDataNodes: ds\_${0..1}.trader\_order\_${0..31}
> 
> 分库算法：
> 
> 分库值 = buyer\_id.hashCode().abs() % 2
> 
> 分库策略表达式：ds\_${分库值}
> 
> 分表算法：
> 
> 分表值 = (buyer\_id.hashCode().abs() % 32)
> 
> 分表策略表达式：trader\_order\_${分表值}
> 
> 数据分布示例：
> 
> buyer\_id=20005 → hashCode=20005 → 库：20005 %2=1 → 表：20005 %32=5 → ds\_1.trader\_order\_5

### **2、 订单买家流水表（trade\_order\_snapshot）**

同订单表规则一致，如下：

> actualDataNodes: ds\_${0..1}.trade\_order\_snapshot\_${0..15}
> 
> 分库算法：
> 
> 分库值 = buyer\_id.hashCode().abs() % 2
> 
> 分库策略表达式：ds\_${分库值}
> 
> 分表算法：
> 
> 分表值 = (buyer\_id.hashCode().abs() % 16)
> 
> 分表策略表达式：trade\_order\_snapshot\_${分表值}
> 
> 数据分布示例：
> 
> buyer\_id=20005 → hashCode=20005 → 库：20005 %2=1 → 表：20005 %32=5 → ds\_1.trade\_order\_snapshot\_5

目前该表暂时没有加 「**buyer\_id、 seller\_id**」，接下来需要加一下。

### **2.5.2 拆分后的数据库表结构**

通过上面的分析，我们最后将 **trade\_order 和 trade\_order\_snapshot 表拆分为 32 个。**

如图所示：

![](images/FqoTCEZdP1B_sRG4ZA9q66N16MWf.png)

## **2.6 ShardingSphere 订单项目实战**

在我们整个电商项目中，目前已经接入「**分库分表**」的服务有「**优惠券服务 huazai-coupon**」、「**推送服务 huazai-push**」、「**后管服务 huazai-admin**」，今天我们将「**订单服务 huazai-order**」进行「**分库分表**」，除此之外其他的服务暂时先不接入「**分库分表**」。

### **2.6.1 公共服务分库分表依赖加载**

在 [【电商实战项目第六十九篇】华仔电商实战基于 ShardingSphere 5.5.2 最新版本实战优惠券服务分库分表功能](https://articles.zsxq.com/id_d71i9nfclp5d.html) 这篇中，已经升级了「**shardingsphere 版本**」到最新版 5.5.2。

后面统一使用了最新版的 [shardingsphere-jdbc](https://mvnrepository.com/artifact/org.apache.shardingsphere/shardingsphere-jdbc) 来作为后续分库分表底层实现库，在 common 包中统一添加依赖。

  
![](images/FiCPdsk3ak2IKl5_ggpCl2UO_pG-.png)

### **2.6.2 订单分库分表实战**

### **1、启动配置适配**

首先需要做启动配置的 [shardingsphere](http://shardingsphere/) 适配，之前是直接使用 JDBC 来接管的，这里需要改成 [shardingsphere](http://shardingsphere/) 来接管。

  
![](images/FiGuo3TRKtxIvRvs6La9TI_HwoLi.png)  

  
![](images/Fmg4khotR1vSbHelEjG8Kvbdz5d_.png)

创建 ShardingSphere 自定义分片配置文件 [shardingsphere-config.yaml](http://shardingsphere-config.yaml/) 配置内容如下，同之前不同的是这里要支持多字段复合分片：

\# 数据源集合

dataSources:

\# 逻辑数据源名称

ds\_0:

\# 数据源类型

dataSourceClassName: com.zaxxer.hikari.HikariDataSource

\# 数据库驱动

driverClassName: com.mysql.cj.jdbc.Driver

\# 数据库连接

jdbcUrl: jdbc:mysql://127.0.0.1:3306/huazai\_buyer\_order\_0?useUnicode=true&characterEncoding=UTF-8&rewriteBatchedStatements=true&allowMultiQueries=true&serverTimezone=Asia/Shanghai

username: root

password: 123456

connectionTimeout: 30000

maximumPoolSize: 20

minimumIdle: 5

idleTimeout: 600000

maxLifetime: 1800000

ds\_1:

\# 数据源类型

dataSourceClassName: com.zaxxer.hikari.HikariDataSource

\# 数据库驱动

driverClassName: com.mysql.cj.jdbc.Driver

\# 数据库连接

jdbcUrl: jdbc:mysql://127.0.0.1:3306/huazai\_buyer\_order\_1?useUnicode=true&characterEncoding=UTF-8&rewriteBatchedStatements=true&allowMultiQueries=true&serverTimezone=Asia/Shanghai

username: root

password: 123456

connectionTimeout: 30000

maximumPoolSize: 20

minimumIdle: 5

idleTimeout: 600000

maxLifetime: 1800000

\# ShardingSphere 规则配置，包含：数据分片、数据加密、读写分离等

rules:

\- !SHARDING # 关键：使用标准 sharding 块

tables: # 需要分片的数据库表集合

trade\_order: # 订单表

\# 真实存在数据库中的物理表

actualDataNodes: ds\_${0..1}.trade\_order\_${0..15} # 由数据源名 + 表名组成

databaseStrategy: # 分库策略

complex: # 多分片键分库

shardingColumns: buyer\_id,order\_id # 分片键

shardingAlgorithmName: trade\_order\_database\_mod # 库分片算法名称，对应 rules\[0\].shardingAlgorithms

tableStrategy: # 分表策略

complex: # 多分片键分表

shardingColumns: buyer\_id,order\_id # 分片键

shardingAlgorithmName: trade\_order\_table\_mod # 表分片算法名称，对应 rules\[0\].shardingAlgorithms

keyGenerateStrategy:

column: id

keyGeneratorName: cosId-snowflake

trade\_order\_snapshot:

actualDataNodes: ds\_${0..1}.trade\_order\_snapshot\_${0..31}

databaseStrategy:

complex: \# 多分片键分库

shardingColumns: buyer\_id,order\_id

shardingAlgorithmName: trade\_order\_snapshot\_database\_mod

tableStrategy:

complex: \# 多分片键分库

shardingColumns: buyer\_id,order\_id

shardingAlgorithmName: trade\_order\_snapshot\_table\_mod

keyGenerateStrategy:

column: id

keyGeneratorName: cosId-snowflake

shardingAlgorithms: # 分片算法定义集合

trade\_order\_database\_mod: # 订单分库算法定义

type: CLASS\_BASED # 根据自定义库分片算法类进行分片

props: # 分片相关属性

\# 自定义库分片算法Class

algorithmClassName: net.huazai.sharding.algorithm.DBComplexHashModShardingAlgorithm

sharding-count: 2 # 分片总数量

strategy: complex # 分片类型，单字段分片

\# 新增算法类型标识（兼容不同版本）

algorithm-type: HASH\_MOD

mainColum: buyer\_id

trade\_order\_table\_mod: # 订单分库算法定义

type: CLASS\_BASED # 根据自定义库分片算法类进行分片

props: # 分片相关属性

\# 自定义表分片算法Class

algorithmClassName: net.huazai.sharding.algorithm.TableComplexHashModShardingAlgorithm

sharding-count: 32 # 分片总数量

strategy: complex # 分片类型，单字段分片

\# 新增算法类型标识（兼容不同版本）

algorithm-type: HASH\_MOD

mainColum: buyer\_id

trade\_order\_snapshot\_database\_mod:

type: CLASS\_BASED

props:

algorithmClassName: net.huazai.sharding.algorithm.DBComplexHashModShardingAlgorithm

sharding-count: 2

strategy: complex # 分片类型，单字段分片

\# 新增算法类型标识（兼容不同版本）

algorithm-type: HASH\_MOD

mainColum: buyer\_id

trade\_order\_snapshot\_table\_mod:

type: CLASS\_BASED

props:

algorithmClassName: net.huazai.sharding.algorithm.TableComplexHashModShardingAlgorithm

sharding-count: 32

strategy: complex # 分片类型，单字段分片

\# 新增算法类型标识（兼容不同版本）

algorithm-type: HASH\_MOD

mainColum: buyer\_id

keyGenerators: # 分布式序列算法配置

cosId-snowflake:

type: SNOWFLAKE # 雪花ID生成算法

props:

epoch: 1477929600000 # 自定义起始时间戳，毫秒值

worker-id: 333 # $->{SHARDING\_WORKER\_ID} # 机器ID 会在 SnowFlakeWorkIdConfig 进行设置

as-string: false # 是否返回字符串

auditors:

sharding\_key\_required\_auditor:

type: DML\_SHARDING\_CONDITIONS

props:

\# 配置 ShardingSphere 默认打印 SQL 执行语句

sql-show: true

query-with-cipher-column: true

\# 添加以下配置强制使用ArrayList

default-list-executor-type: arraylist

\# 开启全链路日志（定位分片算法是否触发）

log:

transaction: trace

query: trace

  
**这个分支会对之前的功能进行分片键的处理，包括列表、详情查询、更新、删除、新增等功能改造。**

**目前发现官方文档 5.5.2 版本并没有实现 cosId-snowflake 算法，也是醉了**，如下：

![](images/FvbKFKvLBlVUzXZ4eGP1M7nSLq9G.png)

而 5.3.1 版本之前才有这个 cosId-snowflake 实现，那就先这样。

![](images/FgDUE9TjRJDNzdlUHfxSD6bq5hqz.png)

所以目前分布式主键算法还是使用 「**SNOWFLAKE**」，后续如果官方支持 「**COSID\_SNOWFLAKE**」再进行更新。

接下来我们来编写下订单分库分表算法。

###   
**2.6.2 公共服务订单分库分表算法**

因为我们「**订单服务分库分表**」的 「**分片键**」是两个字段的，所以之前「**优惠券服务分库分表**」算法就不支持，需要使用 shardingsphere 的 [complex复合分片算法](https://shardingsphere.apache.org/document/5.5.1/cn/user-manual/common-config/builtin-algorithm/sharding/)：它主要用于处理使用多键作为分片键进行分片的场景，包含多个分片键的逻辑较复杂，需要应用开发者自行处理其中的复杂度。

![](images/FpEhlqC3KJKGLDahOV95dNc-lW6K.png)

### **2.6.2.1 公共服务订单分库算法**

package net.huazai.sharding.algorithm;

import com.alibaba.fastjson.JSON;

import lombok.extern.slf4j.Slf4j;

import net.huazai.sharding.distribution.DistributeID;

import org.apache.shardingsphere.sharding.api.sharding.complex.ComplexKeysShardingAlgorithm;

import org.apache.shardingsphere.sharding.api.sharding.complex.ComplexKeysShardingValue;

import org.springframework.util.CollectionUtils;

import java.util.\*;

import java.util.stream.Collectors;

/\*\*

\* @className: DBComplexHashModShardingAlgorithm

\* @author: huazai，该项目是知识星球：华仔和他的朋友们 的内部项目

\* @date: 2025-03-06 22:38

\* @Version: 1.0

\* @description: 多分片复杂分库分表算法

\*/

@Slf4j

public class DBComplexHashModShardingAlgorithm implements ComplexKeysShardingAlgorithm<Long> {

/\*\*

\* 分库数量

\*/

private int shardingCount;

/\*\*

\* 默认主分片键

\*/

private String mainColum;

/\*\*

\* 默认主分片键

\*/

private static final String PROP\_MAIN\_COLUM \= "mainColum";

/\*\*

\* 获取算法类型

\* @return

\*/

@Override

public String getType() {

return "HASH\_MOD";

}

/\*\*

\* 初始化

\* @param props

\*/

@Override

public void init(Properties props) {

log.info("分库配置信息：{}", JSON.toJSONString(props));

this.shardingCount = Integer.parseInt(props.getProperty("sharding-count"));

this.mainColum = props.getProperty(PROP\_MAIN\_COLUM);

}

/\*\*

\* 分库算法

\* @param availableTargetNames 所有分片库的集合

\* @param complexKeysShardingValue 分片键的值，SQL 中解析出来的分片值

\* @return

\*/

@Override

public Collection<String> doSharding(Collection<String> availableTargetNames, ComplexKeysShardingValue<Long> complexKeysShardingValue) {

// 增加空值校验

if (complexKeysShardingValue == null || complexKeysShardingValue.getColumnNameAndShardingValuesMap().isEmpty()) {

log.warn("分片键值为空，将路由到所有节点");

return availableTargetNames;

}

log.info("进入数据库多分片 availableTargetNames:{}, preciseShardingValue:{}", JSON.toJSONString(availableTargetNames), JSON.toJSONString(complexKeysShardingValue));

Collection<String> result = new HashSet<>();

// 获取分片键的值

Collection<Long> mainColums = complexKeysShardingValue.getColumnNameAndShardingValuesMap().get(mainColum);

/\*\*

\* 当主分片键存在时，直接根据主分片键进行分片

\* 例如：SELECT \* FROM trade\_order WHERE buyer\_id = 123

\*/

if (!CollectionUtils.isEmpty(mainColums)) {

// 遍历主分片键的值

for (Long colum : mainColums) {

// 根据主分片键的值计算分库目标

String shardingTarget \= calculateShardingTarget(colum);

log.info("进入数据库多分片，根据主分片键的值计算分库目标：{}", JSON.toJSONString(shardingTarget));

result.add(shardingTarget);

}

log.info("进入数据库多分片，根据主分片获取的分库结果：{}", JSON.toJSONString(result));

// 计算分库目标

return getMatchedDbs(result, availableTargetNames);

}

/\*\*

\* 当主分片键不存在时，根据其他分片键进行分片

\* 例如：SELECT \* FROM trade\_order WHERE order\_id = 1343432432432432432 AND buyer\_id = 123

\*/

Collection<String> otherColums = complexKeysShardingValue.getColumnNameAndShardingValuesMap().keySet();

if (!CollectionUtils.isEmpty(otherColums)) {

// 遍历其他分片键的值

for (String colum : otherColums) {

// 获取其他分片键的值

Collection<Long> otherColumValues = complexKeysShardingValue.getColumnNameAndShardingValuesMap().get(colum);

// 根据其他分片键的值计算分库目标

for (Long value : otherColumValues) {

String shardingTarget \= extractShardingTarget(value);

log.info("进入数据库多分片，根据其他分片键的值计算分库目标：{}", JSON.toJSONString(shardingTarget));

result.add(shardingTarget);

}

}

log.info("进入数据库多分片，根据其他分片获取的分库结果：{}", JSON.toJSONString(result));

// 计算分库目标

return getMatchedDbs(result, availableTargetNames);

}

return null;

}

/\*\*

\* 根据分片键的值计算分库目标

\* @param results

\* @param availableTargetNames

\* @return

\*/

private Collection<String> getMatchedDbs(Collection<String> results, Collection<String> availableTargetNames) {

// 计算分表目标

Collection<String> matchedDbs = new HashSet<>();

for (String result : results) {

// 遍历所有分片表，找到匹配的分片表

// 例如：trade\_order\_0，trade\_order\_1，trade\_order\_2，trade\_order\_3 result 为 1，那么匹配的分片表为 trade\_order\_1

matchedDbs.addAll(availableTargetNames.parallelStream().filter(each -> each.endsWith(result)).collect(Collectors.toSet()));

}

log.info("进入数据库多分片，根据分片键的值计算分库目标：{}", JSON.toJSONString(matchedDbs));

return matchedDbs;

}

/\*\*

\* 根据卖家id、分库数计算分库目标

\* @param buyerId

\* @return

\*/

private String calculateShardingTarget(Long buyerId) {

return DistributeID.getShardingTable(buyerId, shardingCount);

}

/\*\*

\* 根据订单id计算分表目标

\* @param orderId

\* @return

\*/

private String extractShardingTarget(Long orderId) {

return DistributeID.getShardingTable(orderId);

}

}

核心逻辑：

1.  优先使用主分片键（如 buyer\_id）的哈希值计算分库位置。
2.  若主分片键不存在，则使用其他分片键（如 order\_id）计算。
3.  最终匹配库名的后缀（如 db\_0 的后缀 0）。

### **2.6.2.2 公共服务订单分表算法**

package net.huazai.sharding.algorithm;

import com.alibaba.fastjson.JSON;

import lombok.extern.slf4j.Slf4j;

import net.huazai.sharding.distribution.DistributeID;

import org.apache.shardingsphere.sharding.api.sharding.complex.ComplexKeysShardingAlgorithm;

import org.apache.shardingsphere.sharding.api.sharding.complex.ComplexKeysShardingValue;

import org.springframework.util.CollectionUtils;

import java.util.\*;

import java.util.stream.Collectors;

/\*\*

\* @className: TableComplexHashModShardingAlgorithm

\* @author: huazai，该项目是知识星球：华仔和他的朋友们 的内部项目

\* @date: 2025-03-06 22:38

\* @Version: 1.0

\* @description: 分表分表算法

\*/

@Slf4j

public class TableComplexHashModShardingAlgorithm implements ComplexKeysShardingAlgorithm<Long> {

/\*\*

\* 分表数量

\*/

private int shardingCount;

/\*\*

\* 默认主分片键

\*/

private String mainColum;

/\*\*

\* 默认主分片键

\*/

private static final String PROP\_MAIN\_COLUM \= "mainColum";

/\*\*

\* 获取算法类型

\* @return

\*/

@Override

public String getType() {

return "HASH\_MOD";

}

/\*\*

\* 初始化

\* @param props

\*/

@Override

public void init(Properties props) {

log.info("分表配置信息：{}", JSON.toJSONString(props));

this.shardingCount = Integer.parseInt(props.getProperty("sharding-count"));

this.mainColum = props.getProperty(PROP\_MAIN\_COLUM);

}

/\*\*

\* 分表算法

\* @param availableTargetNames 所有分片库的集合

\* @param complexKeysShardingValue 分片键的值，SQL 中解析出来的分片值

\* @return

\*/

@Override

public Collection<String> doSharding(Collection<String> availableTargetNames, ComplexKeysShardingValue<Long> complexKeysShardingValue) {

// 增加空值校验

if (complexKeysShardingValue == null || complexKeysShardingValue.getColumnNameAndShardingValuesMap().isEmpty()) {

log.warn("分片键值为空，将路由到所有节点");

return availableTargetNames;

}

log.info("进入数据库多分片 availableTargetNames:{}, preciseShardingValue:{}", JSON.toJSONString(availableTargetNames), JSON.toJSONString(complexKeysShardingValue));

Collection<String> result = new HashSet<>();

// 获取分片键的值

Collection<Long> mainColums = complexKeysShardingValue.getColumnNameAndShardingValuesMap().get(mainColum);

/\*\*

\* 当主分片键存在时，直接根据主分片键进行分片

\* 例如：SELECT \* FROM trade\_order WHERE buyer\_id = 123

\*/

if (!CollectionUtils.isEmpty(mainColums)) {

// 遍历主分片键的值

for (Long colum : mainColums) {

// 根据主分片键的值计算分表目标

String shardingTarget \= calculateShardingTarget(colum);

log.info("进入数据库多分片，根据主分片键的值计算分表目标：{}", JSON.toJSONString(shardingTarget));

result.add(shardingTarget);

}

log.info("进入数据库多分片，根据主分片获取的分表结果：{}", JSON.toJSONString(result));

// 计算分表目标

return getMatchedTables(result, availableTargetNames);

}

/\*\*

\* 当主分片键不存在时，根据其他分片键进行分片

\* 例如：SELECT \* FROM trade\_order WHERE order\_id = 1343432432432432432 AND buyer\_id = 123

\*/

Collection<String> otherColums = complexKeysShardingValue.getColumnNameAndShardingValuesMap().keySet();

if (!CollectionUtils.isEmpty(otherColums)) {

// 遍历其他分片键的值

for (String colum : otherColums) {

// 获取其他分片键的值

Collection<Long> otherColumValues = complexKeysShardingValue.getColumnNameAndShardingValuesMap().get(colum);

// 根据其他分片键的值计算分表目标

for (Long value : otherColumValues) {

String shardingTarget \= extractShardingTarget(value);

log.info("进入数据库多分片，根据其他分片键的值计算分表目标：{}", JSON.toJSONString(shardingTarget));

result.add(shardingTarget);

}

}

log.info("进入数据库多分片，根据其他分片获取的分表结果：{}", JSON.toJSONString(result));

// 计算分表目标

return getMatchedTables(result, availableTargetNames);

}

return null;

}

/\*\*

\* 根据分片键的值计算分表目标

\* @param results

\* @param availableTargetNames

\* @return

\*/

private Collection<String> getMatchedTables(Collection<String> results, Collection<String> availableTargetNames) {

// 计算分表目标

Collection<String> matchedTables = new HashSet<>();

for (String result : results) {

// 遍历所有分片表，找到匹配的分片表 根据表名最后的下划线分割部分进行匹配

// 例如：trade\_order\_0，trade\_order\_1，trade\_order\_2，trade\_order\_3 result 为 1，那么匹配的分片表为 trade\_order\_1

matchedTables.addAll(availableTargetNames.parallelStream()

.filter(each -> {

// 将表名按最后一个下划线分割，取数字部分进行比较（如 trade\_order\_2 -> \["trade\_order", "2"\]）

String\[\] parts = each.split("\_(?=\[^\_\]+$)");

return parts.length > 1 && parts\[1\].equals(result);

})

.collect(Collectors.toSet()));

}

log.info("进入数据库多分片，根据分片键的值计算分表目标：{}", JSON.toJSONString(matchedTables));

return matchedTables;

}

/\*\*

\* 根据卖家id、分表数计算分表目标

\* @param buyerId

\* @return

\*/

private String calculateShardingTarget(Long buyerId) {

return DistributeID.getShardingTable(buyerId, shardingCount);

}

/\*\*

\* 根据订单id计算分表目标

\* @param orderId

\* @return

\*/

private String extractShardingTarget(Long orderId) {

return DistributeID.getShardingTable(orderId);

}

}

核心逻辑：

1.  优先使用主分片键（如 buyer\_id）的哈希值计算分表位置。
2.  若主分片键不存在，则使用其他分片键（如 order\_id）计算。
3.  最终匹配表名的后缀（如 trade\_order\_0 的后缀 0）。

整个算法的处理流程如下：

![](images/FtmwF1G4UFt0TJ15J-nVxq2g83mK.jpg)

### **2.6.3 订单分库分表测试**

### **2.6.3.1 订单列表测试**

这里我简单的写了一个获取订单列表的方法，主要用来测试「**分库分表**」效果。

  
![](images/FghJMdgFW5qXcqa8Wq0IIfZhsmtv.png)

测试效果：

![](images/FiDcLshZppJjGHA1kAg0Mrrp-NYZ.png)

### **2.6.3.2 订单号生成规则**

在前面，订单号是通过 UUID 来生成的，当我们进行「**分库分表**」后需要根据需求重新生成「**订单号**」。

  
![](images/FqRphBYXSk9bP2sbtb2hmfjThuHg.png)

![](images/Fj0Fsjn8UBiT0nDbegS2b2L0P5Ma.png)

测试效果如下：

![](images/Fuyr45Hb8MY7hx1UQGxyaCj-_ohM.png)

![](images/FqTE-M5dcd8U8-b8B6HS0XUdKGQW.png)

### **2.6.3.3 生成订单**

搞完「**订单号**」之后，我们来看下「**生单流程**」，除了重新生成了 「**订单号**」，「**订单流水**」需要做下「**分片键**」处理，「**优惠券服务**」 锁定优惠券修改状态也需要添加「**分片键**」，这里我们直接测试下「**生单流程**」即可。

{

"buyerId": 0,

"couponConsumeRule": "",

"couponId": 32,

"couponName": "童装店铺满减优惠券",

"identifier": "",

"orderAmount": 10,

"sellerId": "0",

"skuCount": 1,

"skuId": 99463,

"skuMainUrl": "string",

"skuName": "string",

"skuPrice": 437,

"skuStock": 0,

"snapshotVersion": 0

}

创建订单流水时「**分片键**」处理：

![](images/Fnj8z5lmxt2ZXRSg1iiSayAFImz-.png)

修改订单确认状态及创建订单确认流水时「**分片键**」处理：

![](images/Fq1BdQgSucEWPRyztc5Ia0dDcSYF.png)

![](images/FrLEpz01uQ83zAuswSXY7ejKKlfn.png)

![](images/Fqses7OMnkdJpr8YqIlAMLkbfJs8.png)

同理，优惠券锁定「**分片键**」处理：

![](images/Fqdc3Y-loAUjxitR-gBhMiprWl4Q.png)

测试效果：

![](images/Fok693kpqtmIbW0K9ngQIU43AlfK.png)

![](images/FjRYhSfH1PMEHKvE6C1Z2iq22jF0.png)

![](images/FrwCTAmTdfy7lEuLUCkHN8K7hL2f.png)

购物车清理操作日志：

![](images/FoQM0vAHnXwrRWWqM0ues_2Cjre6.png)

库存预扣减操作日志：

![](images/FlE9RjXbX1fwDD8ZyoTvMRbkmL3Z.png)

优惠券锁定操作日志：

![](images/FuIqkjLg3uFW1S7dNH25wgzxkiPT.png)

订单表插入，通过日志可以看到根据 「**buyer\_id = 2**」最终路由到 「**分库 huazai\_buyer\_order\_0 分表 trader\_order\_2**」:

![](images/FuTGWQff-3fXGetldN34L3KbY1-E.png)

订单流水有两条数据，一条是「**创建订单流水**」，一条是「**确认订单流水**」，同样路由到 「**分库 huazai\_buyer\_order\_0 分表 trade\_order\_snapshot\_2**」:

![](images/FkI7i_Z3e_rwungKALgsf7Vw1u5f.png)

同理，「**优惠券锁定**」更新状态路由到「**分库 huazai\_coupon\_0 分表 user\_coupon\_2**」:

  
![](images/FrEBoMDg3oJqtBohoHjjJeVloqbi.png)

### **2.6.3.4 取消订单**

「**生单流程**」搞完后，我们接着来看下「**取消订单流程**」，同理也需要做下相关「**分片键处理**」。

  
![](images/FnKQrWK_20A_NBsnm2PL0Wz3gqSE.png)

![](images/FpBSwGHoavynsStXLNUYwqtBlfLI.png)

![](images/FkN_fRuIa4cr_TBCCl8GyN2_Aru3.png)

![](images/FkdR_d2GSDuCtFxJOlYwLZaq400m.png)

同理，「**优惠券释放**」也需要做分片键处理。

![](images/FgAnFo6Ie--yYomnausUWI7OGlst.png)

请求参数：

{

"bizIdentifier": "f6759f1bfded417b83781c8eb5dd9504",

"operateTime": "2025-04-24T08:26:43.185Z",

"operator": 2,

"operatorType": "CUSTOMER",

"orderId": "1019151918868240002"

}

测试效果：

「**订单取消和订单取消流水创建**」测试日志如下：

![](images/Fi2DyiQ4IExrA3hLs_c_gYgvocnM.png)

「**订单取消和订单取消流水创建**」数据库如下，通过日志可以看到根据 「**buyer\_id = 2**」最终路由到 「**分库 huazai\_buyer\_order\_0 分表 trader\_order\_2**」:

  
![](images/FpCBIsdBylK9EoMEtwk83CjeX9Nu.png)

订单流水会创建「**取消订单流水**」，同样路由到 「**分库 huazai\_buyer\_order\_0 分表 trade\_order\_snapshot\_2**」:

  
![](images/FhJIiOwEGwPpQlerYL7aUurjPNm4.png)

「**优惠券释放**」测试日志如下：

  
![](images/FpTQDbJCe6ALkgQ9YoNIhwYn5mpA.png)

同理，「**优惠券释放**」更新状态路由到「**分库 huazai\_coupon\_0 分表 user\_coupon\_2**」:

  
![](images/FihTxnDtPWHbSBhovh4EVZgpkbP7.png)

### **2.6.3.4 超时关单**

最后我们来看下「**超时关单**」的处理，这里我们使用了「**模板方法**」来处理「**订单取消**」、「**订单超时关单**」、「**订单完成**」等操作，「**订单创建**」、「**订单确认**」目前没改，感兴趣可以自行修改。

因此，「**超时关单**」通过「**RocketMQ 延时消息**」操作就不需要修改了，这里我们只需做下「**XXL-Job 定时任务**」 的「**分库分表**」扫描处理。

  
![](images/FlAn1vb0t2MFJMMsRaE2sR9hdgV4.png)

这块暂时未解决，还是出现了 UNION ALL，后续有时间在搞。