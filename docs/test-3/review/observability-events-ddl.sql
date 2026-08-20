-- MySQL dump 10.13  Distrib 8.0.45, for Linux (x86_64)
--
-- Host: 21.130.247.89    Database: my_xhs_cart
-- ------------------------------------------------------
-- Server version	8.0.46

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
SET @MYSQLDUMP_TEMP_LOG_BIN = @@SESSION.SQL_LOG_BIN;
SET @@SESSION.SQL_LOG_BIN= 0;

--
-- GTID state at the beginning of the backup 
--

SET @@GLOBAL.GTID_PURGED=/*!80000 '+'*/ '3dc17b61-7b9f-11f1-befe-525400d7fee1:1-1322,
3dc456aa-7b9f-11f1-822d-525400d7fee1:1-274,
3dc78fe8-7b9f-11f1-a092-525400d7fee1:1-955,
3dc78ffe-7b9f-11f1-a2d5-525400d7fee1:1-859347,
aea946e0-9331-11f1-8918-525400d7fee1:1-706908';

--
-- Table structure for table `t_cart_event`
--

DROP TABLE IF EXISTS `t_cart_event`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_cart_event` (
  `id` bigint NOT NULL COMMENT 'ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `sku_id` bigint DEFAULT NULL COMMENT 'SKU ID(全局操作如CHECK_ALL/CLEAR为NULL)',
  `action` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'ADD/UPDATE/DELETE/CHECK/CHECK_ALL/CLEAR',
  `quantity` int NOT NULL DEFAULT '0' COMMENT '数量',
  `checked` tinyint NOT NULL DEFAULT '0' COMMENT '勾选',
  `event_time` datetime NOT NULL COMMENT '事件时间(生产者时钟)',
  `msg_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'MQ消息ID(幂等)',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '落库时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_msg_id` (`msg_id`),
  KEY `idx_user_time` (`user_id`,`event_time`),
  KEY `idx_sku` (`sku_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='购物车事件流水(append-only可观测性)';
/*!40101 SET character_set_client = @saved_cs_client */;
SET @@SESSION.SQL_LOG_BIN = @MYSQLDUMP_TEMP_LOG_BIN;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-08-14 12:27:16
-- MySQL dump 10.13  Distrib 8.0.45, for Linux (x86_64)
--
-- Host: 21.130.247.89    Database: my_xhs_content
-- ------------------------------------------------------
-- Server version	8.0.46

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
SET @MYSQLDUMP_TEMP_LOG_BIN = @@SESSION.SQL_LOG_BIN;
SET @@SESSION.SQL_LOG_BIN= 0;

--
-- GTID state at the beginning of the backup 
--

SET @@GLOBAL.GTID_PURGED=/*!80000 '+'*/ '3dc17b61-7b9f-11f1-befe-525400d7fee1:1-1322,
3dc456aa-7b9f-11f1-822d-525400d7fee1:1-274,
3dc78fe8-7b9f-11f1-a092-525400d7fee1:1-955,
3dc78ffe-7b9f-11f1-a2d5-525400d7fee1:1-859347,
aea946e0-9331-11f1-8918-525400d7fee1:1-706908';

--
-- Table structure for table `t_note_event`
--

DROP TABLE IF EXISTS `t_note_event`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_note_event` (
  `id` bigint NOT NULL COMMENT 'ID',
  `note_id` bigint NOT NULL COMMENT '笔记ID',
  `user_id` bigint NOT NULL COMMENT '作者ID',
  `event_type` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'PUBLISH/AUDIT_PASS/AUDIT_REJECT/OFFLINE',
  `status` tinyint NOT NULL DEFAULT '2' COMMENT '事件时笔记状态(2已发布)',
  `audit_status` tinyint NOT NULL DEFAULT '1' COMMENT '事件时审核状态(1通过)',
  `event_time` datetime NOT NULL COMMENT '事件时间',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '落库时间',
  PRIMARY KEY (`id`),
  KEY `idx_note_time` (`note_id`,`event_time`),
  KEY `idx_type_time` (`event_type`,`event_time`),
  KEY `idx_user_time` (`user_id`,`event_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='笔记状态事件流水(append-only可观测性)';
/*!40101 SET character_set_client = @saved_cs_client */;
SET @@SESSION.SQL_LOG_BIN = @MYSQLDUMP_TEMP_LOG_BIN;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-08-14 12:27:16
-- MySQL dump 10.13  Distrib 8.0.45, for Linux (x86_64)
--
-- Host: 21.130.247.89    Database: my_xhs_product
-- ------------------------------------------------------
-- Server version	8.0.46

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
SET @MYSQLDUMP_TEMP_LOG_BIN = @@SESSION.SQL_LOG_BIN;
SET @@SESSION.SQL_LOG_BIN= 0;

--
-- GTID state at the beginning of the backup 
--

SET @@GLOBAL.GTID_PURGED=/*!80000 '+'*/ '3dc17b61-7b9f-11f1-befe-525400d7fee1:1-1322,
3dc456aa-7b9f-11f1-822d-525400d7fee1:1-274,
3dc78fe8-7b9f-11f1-a092-525400d7fee1:1-955,
3dc78ffe-7b9f-11f1-a2d5-525400d7fee1:1-859347,
aea946e0-9331-11f1-8918-525400d7fee1:1-706908';

--
-- Table structure for table `t_product_behavior`
--

DROP TABLE IF EXISTS `t_product_behavior`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_product_behavior` (
  `id` bigint NOT NULL COMMENT 'ID',
  `user_id` bigint NOT NULL DEFAULT '0' COMMENT '用户ID(匿名=0)',
  `spu_id` bigint NOT NULL COMMENT 'SPU ID',
  `sku_id` bigint DEFAULT NULL COMMENT '浏览目标SKU(详情页首SKU，可为空)',
  `behavior_type` tinyint NOT NULL DEFAULT '1' COMMENT '1-浏览',
  `event_time` datetime NOT NULL COMMENT '事件时间',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '落库时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_time` (`user_id`,`event_time`),
  KEY `idx_spu` (`spu_id`),
  KEY `idx_time` (`event_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品浏览事件流水(append-only可观测性)';
/*!40101 SET character_set_client = @saved_cs_client */;
SET @@SESSION.SQL_LOG_BIN = @MYSQLDUMP_TEMP_LOG_BIN;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-08-14 12:27:16
-- MySQL dump 10.13  Distrib 8.0.45, for Linux (x86_64)
--
-- Host: 21.130.247.89    Database: my_xhs_payment
-- ------------------------------------------------------
-- Server version	8.0.46

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
SET @MYSQLDUMP_TEMP_LOG_BIN = @@SESSION.SQL_LOG_BIN;
SET @@SESSION.SQL_LOG_BIN= 0;

--
-- GTID state at the beginning of the backup 
--

SET @@GLOBAL.GTID_PURGED=/*!80000 '+'*/ '3dc17b61-7b9f-11f1-befe-525400d7fee1:1-1322,
3dc456aa-7b9f-11f1-822d-525400d7fee1:1-274,
3dc78fe8-7b9f-11f1-a092-525400d7fee1:1-955,
3dc78ffe-7b9f-11f1-a2d5-525400d7fee1:1-859347,
aea946e0-9331-11f1-8918-525400d7fee1:1-706908';

--
-- Table structure for table `t_payment_event`
--

DROP TABLE IF EXISTS `t_payment_event`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_payment_event` (
  `id` bigint NOT NULL COMMENT 'ID',
  `payment_no` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '支付流水号',
  `order_id` bigint NOT NULL COMMENT '订单ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `event_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'CREATE/PAY_SUCCESS/PAY_FAIL/REFUND/TIMEOUT',
  `error_code` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '失败原因码',
  `error_msg` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '失败原因',
  `event_time` datetime NOT NULL COMMENT '事件时间',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '落库时间',
  PRIMARY KEY (`id`),
  KEY `idx_payment_no` (`payment_no`),
  KEY `idx_order` (`order_id`),
  KEY `idx_time` (`event_time`),
  KEY `idx_user_time` (`user_id`,`event_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='支付事件流水(append-only可观测性)';
/*!40101 SET character_set_client = @saved_cs_client */;
SET @@SESSION.SQL_LOG_BIN = @MYSQLDUMP_TEMP_LOG_BIN;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-08-14 12:27:16
-- MySQL dump 10.13  Distrib 8.0.45, for Linux (x86_64)
--
-- Host: 21.130.247.89    Database: my_xhs_inventory
-- ------------------------------------------------------
-- Server version	8.0.46

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
SET @MYSQLDUMP_TEMP_LOG_BIN = @@SESSION.SQL_LOG_BIN;
SET @@SESSION.SQL_LOG_BIN= 0;

--
-- GTID state at the beginning of the backup 
--

SET @@GLOBAL.GTID_PURGED=/*!80000 '+'*/ '3dc17b61-7b9f-11f1-befe-525400d7fee1:1-1322,
3dc456aa-7b9f-11f1-822d-525400d7fee1:1-274,
3dc78fe8-7b9f-11f1-a092-525400d7fee1:1-955,
3dc78ffe-7b9f-11f1-a2d5-525400d7fee1:1-859347,
aea946e0-9331-11f1-8918-525400d7fee1:1-723130';

--
-- Table structure for table `t_inventory_prededuct_idem`
--

DROP TABLE IF EXISTS `t_inventory_prededuct_idem`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_inventory_prededuct_idem` (
  `order_id` bigint NOT NULL COMMENT '预扣orderId(pseudoOrderId)',
  `sku_id` bigint NOT NULL COMMENT 'SKU ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次预扣时间',
  PRIMARY KEY (`order_id`,`sku_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='预扣幂等表(MySQL兜底,防Redis key丢失后重复扣减 T-079)';
/*!40101 SET character_set_client = @saved_cs_client */;
SET @@SESSION.SQL_LOG_BIN = @MYSQLDUMP_TEMP_LOG_BIN;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-08-14 15:14:38
