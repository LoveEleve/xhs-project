大家好，我是**华仔**, 又跟大家见面了。

从今天之后的一段时间内，华仔会带着大家一起从零开始搭建并研发一套高并发的电商实战项目，这里会涉及到很多互联网大厂开发过程中所使用的核心技术和架构设计模式，希望大家学完之后可以用到自己的简历中。

接下来我们会重点架构设计和开发一下「**社交系统**」中的重要服务：「**关注服务**」、「**计数服务**」、「**评论服务**」。

这是第十八篇，本篇我们进行电商实战项目社交微服务中「**关注服务**」通用基础功能设计与开发。

文章汇总位置：[https://wx.zsxq.com/dweb2/index/columns/51122554151214](https://wx.zsxq.com/dweb2/index/columns/51122554151214)

![](images/Fnl1ueruAt4exbk9U2e5CcbjFHDI.png)

源码授权与获取地址：[https://articles.zsxq.com/id\_1s85grnaae4p.html](https://articles.zsxq.com/id_1s85grnaae4p.html)

![](images/FmkphgNM2yJBSqCq4raWXJA2r2Ah.png)

本章源码地址：[https://gitcode.net/u011359591/huazai-ecshop/-/tree/ecshop-chapter-18](https://gitcode.net/u011359591/huazai-ecshop/-/tree/ecshop-chapter-18)

## **01 前言**

终于要设计与研发电商项目代码了，今天我们要对社交微服务中关注服务通用基础功能设计与开发。

这里需要注意下，我们整个项目目前是不提供前端的，这个后续有时间在搞，主要是进行后端接口以及微服务模块架构设计。

## **02 关注服务社交场景介绍**

这里我们主要是以「**小红书**」为例，来看看其「**关注服务**」都有哪些功能。

##   
**2.1 关注服务功能梳理**

下图是小红书中某美食博主的主页展示：

  
![](images/FrADkvasIMB9XobXalXZOXmKHyUz.jpg)

下图我们简称 relation 页，该页展示博主的关系相关信息：

![](images/FjVptSDx3KfDZZzMErxQonFNeIGE.jpg)

从上图可以看出主要功能有：

1.  关注数：展示该博主关注的所有用户的数量。
2.  粉丝数：展示关注该博主的用户数量。
3.  点赞收藏数：展示点赞和收藏过该博主美食笔记的数量。
4.  关注列表：展示该博主关注的所有⽤户列表信息。
5.  粉丝列表：展示关注该博主的所有⽤户列表信息。

在这些页面中，博主可以自己添加/删除关注（attention），即关注某个其他⽤户或者对其他⽤户进行取消关注。

可以删除 (follower)，即取消其他某个用户对自己的关注。

##   
**2.2 关注服务业务特点**

1.  海量的用户数据：百万、千万，甚至亿级的用户数量，每个用户千级的笔记数量，平均千级的粉丝 follower/ 关注 attention 数量。
2.  高访问量：每秒十万量级的平均页面访问，每秒万量级的笔记发布。
3.  用户分布的非均匀：部分博主的笔记数量/follower 数量，相关页面访问数量会超出其他博主⼀到几个数量级。
4.  时间分布的非均匀分布：某个博主可能突然在某个时间成为热点博主，其 follower 可能徒增数个量级。

综上：这是⼀个典型的社交类系统，其特性主要有：「**大数据量**」、「**高访问量**」、「**非均匀性**」。

## **03 关注服务基础功能实现**

这里我们先来开发一个比较通用的功能实现，后面再进行优化和重构。

## **3.1 数据库设计**

\# 这里扩展一张关系表，用来维护用户的关注数、粉丝数，后续可能会迁移到计数服务中

CREATE TABLE \`user\_relation\` (

\`id\` int NOT NULL AUTO\_INCREMENT COMMENT '主键id',

\`user\_id\` int NOT NULL DEFAULT '0' COMMENT '用户id',

\`attention\_count\` int NOT NULL DEFAULT '0' COMMENT '关注数',

\`follower\_count\` int NOT NULL DEFAULT '0' COMMENT '粉丝数',

PRIMARY KEY (\`id\`),

UNIQUE KEY \`uid\` (\`user\_id\`) USING BTREE

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4\_general\_ci COMMENT='用户关系表';

#用户关注表 用来实现关注列表

CREATE TABLE \`user\_attention\` (

\`id\` int NOT NULL AUTO\_INCREMENT COMMENT '主键id',

\`user\_id\` int NOT NULL COMMENT '当前博主用户id',

\`attention\_id\` int NOT NULL COMMENT '关注博主用户id',

\`create\_time\` datetime DEFAULT CURRENT\_TIMESTAMP ON UPDATE CURRENT\_TIMESTAMP COMMENT '创建设计，会按这个字段排序',

\`is\_del\` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否删除，0 正常 1 删除',

PRIMARY KEY (\`id\`),

KEY \`uid\` (\`user\_id\`,\`create\_time\`) USING BTREE

) ENGINE=InnoDB AUTO\_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4\_general\_ci COMMENT='用户关注表';

\# 用户粉丝表，用来实现粉丝列表

CREATE TABLE \`user\_follower\` (

\`id\` int NOT NULL AUTO\_INCREMENT COMMENT '主键id',

\`user\_id\` int NOT NULL COMMENT '博主id',

\`follower\_id\` int NOT NULL COMMENT '粉丝id',

\`create\_time\` datetime DEFAULT NULL COMMENT '创建时间，按时间排序',

\`is\_del\` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否删除 0 正常 1删除',

PRIMARY KEY (\`id\`),

KEY \`uid\` (\`user\_id\`,\`create\_time\`) USING BTREE

) ENGINE=InnoDB AUTO\_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4\_general\_ci COMMENT='粉丝关系表';

## **3.2 社交工程构建和基础功能实现**

### **3.2.1 添加社交工程**

后续社交相关的功能「**关注服务**」、「**计数服务**」、「**评论服务**」都会在这个工程中添加。

![](images/FmmqWU-xWNgHQbv9Ms9_zumG2F_-.png)

创建完就会聚合工程中自动添加 [huazai-](http://huazai-food/)social 的一个 module。

  
![](images/FsIkIsM5nP5BizUtG-KQ6fRxOg4Y.png)

基础目录结构如下：

![](images/Fg5ui4d1giLr6cbjQd_w6Y4Cr8II.png)

### **3.2.2 文件配置**

配置文件配置 [application.yml](http://application.yml/)，这里直接拷贝 [huazai-user](http://huazai-user/) 的，然后修改下启动端口 [900](http://0.0.35.43/)4、应用名称、数据库配置等。

  
![](images/Fq5AMpyJT97IqelJfrQrGb09yDS6.png)

### **3.2.3 添加启动类注解**

![](images/FtKaAgE_V4MpIpUIE9nnMph8QDjs.png)

等这些基础步骤搞完后，就可以开发我们基础功能了。

  
今天这篇我们主要开发「**查询关注数、粉丝数**」、「**博主关注/取关**」、「**用户关注/取关**」、「**关注列表**」、「**粉丝列表**」。

接下来我们分别来看下。

## **3.3 查询关注数、粉丝数**

在「**计数服务**」之前，我们先将「**关注数、粉丝数**」放置到 user 下面，后续再迁移。

关于这个查询我们就不单独写接口，由于它是用户基础信息的一部分，就放到用户详情接口中，之前都封装好了，所以这里只需要改下 [UserServiceImpl#getUserInfoFromDB](http://userserviceimpl/#getUserInfoFromDB) 这个私有方法即可。

第一次从数据库读取，下次直接读缓存了，如果是热点用户会自动续期的。

![](images/FjgPOxyrhJCa_0bWH06fGJOd_XbX.png)

这里不再直接使用 selectOne 方式查询了，会在 mapper 中加方法以及在 mapper xml 中配置 sql 方式，后续之前的也会改成这种方式。

![](images/Fgw5sz_KWGuEkraZPs_N-1Ve59gZ.png)

![](images/FvYl4BanJkXLKvbHqwuuT7TtDwA1.png)

![](images/FhtPk_vCtB7P6ElMh20YOrX3D30N.png)

其他的功能会在 [huazai-social](http://huazai-social/) 工程开发。

## **3.4 博主/粉丝 关注/取关功能设计与开发**

这里我们为了支持「**高并发场景**」，通过引入 RocketMQ 来进行流量削峰，「**异步处理**」博主/粉丝关注、取关事件的，以及后续的关注计数。

先把场景说一下，可能大家还分不清。

**粉丝关注/取关功能图如下，即我的粉丝：**

![](images/Fhi-_YLpin0RYSjsUXTIJbp9lvY6.png)

**博主关注/取关功能图如下，即我关注的其他博主：**

![](images/Fqks-V2CloAgtbyACv_J9iHRna3H.png)

了解完场景功能后，我们来看下具体的开发实现，目前开发的整个目录如下：

![](images/FmHRQvGIn3dPqYSfER1NWTt4NINP.png)

### **3.4.1 关注/取关控制器**

package net.huazai.controller;

import io.swagger.annotations.Api;

import io.swagger.annotations.ApiOperation;

import io.swagger.annotations.ApiParam;

import lombok.extern.slf4j.Slf4j;

import net.huazai.entity.UserAttentionEntity;

import net.huazai.entity.UserFollowerEntity;

import net.huazai.enums.BizCodes;

import net.huazai.service.UserRelationService;

import net.huazai.utils.ApiResult;

import org.checkerframework.checker.units.qual.A;

import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.web.bind.annotation.\*;

/\*\*

\* <p>

\* 用户关系 前端控制器

\* </p>

\*

\* @author huazai

\* @since 2024-09-08

\*/

@Api(tags = "社交微服务账号模块")

@Slf4j

@RestController

@RequestMapping("/api/social/v1/")

public class UserRelationController {

/\*\*

\* 注入用户关系服务

\*/

@Autowired

private UserRelationService userRelationService;

/\*\*

\* 博主关注/取关

\* @return

\*/

@ApiOperation("博主关注")

@PostMapping("/attention")

public ApiResult doAttention(@ApiParam("要关注的用户 uid")

@RequestParam(value = "target\_user\_id") Long target\_user\_id) {

log.info("博主关注，target\_user\_id:{}", target\_user\_id);

ApiResult result \= userRelationService.doAttentionEvent(target\_user\_id);

return result;

}

/\*\*

\* 博主取关

\* @return

\*/

@ApiOperation("博主取关")

@PostMapping("/unattention")

public ApiResult unAttention(@ApiParam("要取关的用户 uid")

@RequestParam(value = "target\_user\_id") Long target\_user\_id) {

log.info("博主取关，target\_user\_id:{}", target\_user\_id);

ApiResult result \= userRelationService.unAttentionEvent(target\_user\_id);

return result;

}

/\*\*

\* 用户关注

\* @return

\*/

@ApiOperation("用户关注")

@PostMapping("/follower")

public ApiResult doFollower(@ApiParam("要关注的博主 uid")

@RequestParam(value = "target\_user\_id") Long target\_user\_id) {

log.info("用户关注，target\_user\_id:{}", target\_user\_id);

ApiResult result \= userRelationService.doFollowerEvent(target\_user\_id);

return result;

}

/\*\*

\* 用户取关

\* @return

\*/

@ApiOperation("用户取关")

@PostMapping("/unfollower")

public ApiResult unFollower(@ApiParam("要取关的博主 uid")

@RequestParam(value = "target\_user\_id") Long target\_user\_id) {

log.info("用户取关，target\_user\_id:{}", target\_user\_id);

ApiResult result \= userRelationService.unFollowerEvent(target\_user\_id);

return result;

}

}

控制器比较简单，目前就 4 个 方法，分别是：

1.  博主关注
2.  博主取关
3.  粉丝关注
4.  粉丝取关

### **3.4.2 关注/取关服务**

服务层是重点开发逻辑，只不过我们这里使用了「**RocketMQ 来异步处理关注、取关事件**」、「**Redis list 来存储粉丝列表和关注列表**」。

package net.huazai.service.impl;

import com.alibaba.fastjson.JSON;

import lombok.extern.slf4j.Slf4j;

import net.huazai.constant.RedisKeyConstant;

import net.huazai.constant.RocketMQConstant;

import net.huazai.entity.LoginUser;

import net.huazai.entity.UserAttentionEntity;

import net.huazai.entity.UserFollowerEntity;

import net.huazai.enums.BizCodes;

import net.huazai.interceptor.LoginInterceptor;

import net.huazai.mq.producer.DefaultProducer;

import net.huazai.redis.RedisCache;

import net.huazai.service.UserRelationService;

import net.huazai.utils.ApiResult;

import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.data.redis.core.BoundListOperations;

import org.springframework.stereotype.Service;

import java.util.ArrayList;

import java.util.List;

/\*\*

\* <p>

\* 用户关系 服务实现类

\* </p>

\*

\* @author huazai

\* @since 2024-09-08

\*/

@Service

@Slf4j

public class UserRelationServiceImpl implements UserRelationService {

/\*\*

\* 注入生产者

\*/

@Autowired

private DefaultProducer defaultProducer;

@Autowired

private RedisCache redisCache;

/\*\*

\* 博主关注操作

\* 1、当前登录用户为博主（粉丝）、targetUserId 为要关注的其他博主

\* 2、MQ 异步更新关注和计数

\* @param targetUserId

\* @return

\*/

@Override

public ApiResult doAttentionEvent(Long targetUserId) {

LoginUser loginUser \= LoginInterceptor.threadLocal.get();

log.info("博主关注，当前博主：{}，其他被关注博主：{}", loginUser.getId(), targetUserId);

// 1、博主自己不能关注自己

if (loginUser.getId() == targetUserId) {

return ApiResult.doResult(BizCodes.USER\_ATTENTION\_NOT\_SELF);

}

// 2、判断是否已经关注过

String key \= RedisKeyConstant.USER\_ATTENTION\_PREFIX + loginUser.getId();

BoundListOperations<String,String> listOps = redisCache.boundListOps(key);

// 获取列表的所有元素

List<String> values = listOps.range(0,-1);

boolean exists \= values.contains(String.valueOf(targetUserId));

if (exists) {

log.info("博主关注，其他被关注博主 uid ：" + targetUserId+ " 已经存在列表中");

return ApiResult.doResult(BizCodes.USER\_ATTENTED);

} else {

log.info("博主关注，其他被关注博主 uid ：" + targetUserId + " 不存在列表中");

UserAttentionEntity userAttentionEntity \= new UserAttentionEntity();

// 设置当前博主 uid

userAttentionEntity.setUserId(loginUser.getId());

// 设置其他被关注博主 uid

userAttentionEntity.setAttentionId(targetUserId);

// 设置关注

userAttentionEntity.setIsDel(0);

// 3、发布关注事件

publishAttentionEvent(userAttentionEntity);

return ApiResult.doResult(BizCodes.USER\_ATTENTION\_SUCCESS);

}

}

/\*\*

\* 博主取关操作

\* 1、当前登录用户为博主（粉丝）、targetUserId为要关注的其他博主

\* 2、MQ 异步更新取关和计数

\* @param targetUserId

\* @return

\*/

@Override

public ApiResult unAttentionEvent(Long targetUserId) {

LoginUser loginUser \= LoginInterceptor.threadLocal.get();

log.info("博主取关，当前博主：{}，其他被关注博主：{}", loginUser.getId(), targetUserId);

// 1、自己不能取关自己

if (loginUser.getId() == targetUserId) {

return ApiResult.doResult(BizCodes.USER\_UN\_ATTENTION\_NOT\_SELF);

}

// 2、判断是否已经取关过

String key \= RedisKeyConstant.USER\_ATTENTION\_PREFIX + loginUser.getId();

BoundListOperations<String,String> listOps = redisCache.boundListOps(key);

// 获取列表的所有元素

List<String> values = listOps.range(0,-1);

boolean exists \= values.contains(String.valueOf(targetUserId));

if (!exists) {

log.info("博主取关，其他被关注博主 uid ：" + targetUserId + " 已经不存在列表中，不可以取关");

return ApiResult.doResult(BizCodes.USER\_UN\_ATTENTED);

} else {

log.info("博主取关，其他被关注博主 uid ：" + targetUserId + " 还存在列表中，可以取关");

UserAttentionEntity userAttentionEntity \= new UserAttentionEntity();

// 设置当前博主 uid

userAttentionEntity.setUserId(loginUser.getId());

// 设置其他被关注博主 uid

userAttentionEntity.setAttentionId(targetUserId);

// 设置取关

userAttentionEntity.setIsDel(1);

// 3、发布取关事件

publishAttentionEvent(userAttentionEntity);

return ApiResult.doResult(BizCodes.USER\_UN\_ATTENTION\_SUCCESS);

}

}

/\*\*

\* 发布关注/取关事件

\* @param userAttentionEntity

\*/

private void publishAttentionEvent(UserAttentionEntity userAttentionEntity) {

// 发消息通知博主关注/取消事件

List<String> keys = new ArrayList<>();

if (userAttentionEntity.getIsDel() == 0) {

// 构建博主关注消息，直接添加到 redis list 最左侧

keys.add(RedisKeyConstant.USER\_ATTENTION\_PREFIX + userAttentionEntity.getUserId());

keys.add(userAttentionEntity.getAttentionId().toString());

keys.add("1"); // 关注 +1

} else {

// 构建博主取关消息，先判断用户是否在 redis 中，如果在 redis 就删除 redis，否则删除数据库

keys.add(RedisKeyConstant.USER\_ATTENTION\_PREFIX + userAttentionEntity.getUserId());

keys.add(userAttentionEntity.getAttentionId().toString());

keys.add("-1"); // 取关 -1

}

String message \= JSON.toJSONString(keys);

log.info("发布博主关注/取关变更信息：{}", message);

// 发送 MQ 消息

defaultProducer.sendMessage(

RocketMQConstant.ATTENTION\_TOPIC,

message,

"发布博主关注/取关变更信息");

}

/\*\*

\* 粉丝关注操作

\* 1、当前登录用户为粉丝、targetUserId 为要关注的博主

\* 2、MQ 异步更新关注和计数

\* @param targetUserId

\* @return

\*/

@Override

public ApiResult doFollowerEvent(Long targetUserId) {

LoginUser loginUser \= LoginInterceptor.threadLocal.get();

log.info("粉丝关注，粉丝：{}，博主：{}", loginUser.getId(), targetUserId);

// 1、自己不能关注自己

if (loginUser.getId() == targetUserId) {

return ApiResult.doResult(BizCodes.USER\_FOLLOWER\_NOT\_SELF);

}

// 2、判断是否已经关注过

String key \= RedisKeyConstant.USER\_FOLLOWER\_PREFIX + targetUserId;

BoundListOperations<String,String> listOps = redisCache.boundListOps(key);

// 获取列表的所有元素

List<String> values = listOps.range(0,-1);

boolean exists \= values.contains(String.valueOf(loginUser.getId()));

if (exists) {

log.info("粉丝关注，粉丝 uid ：" + loginUser.getId() + " 已经存在列表中");

return ApiResult.doResult(BizCodes.USER\_FOLLOWED);

} else {

log.info("粉丝关注，粉丝 uid ：" + loginUser.getId() + " 不存在列表中");

UserFollowerEntity userFollowerEntity \= new UserFollowerEntity();

// 设置博主 uid

userFollowerEntity.setUserId(targetUserId);

// 设置粉丝 uid

userFollowerEntity.setFollowerId(loginUser.getId());

// 设置关注

userFollowerEntity.setIsDel(0);

// 3、发布关注事件

publishFollowerEvent(userFollowerEntity);

return ApiResult.doResult(BizCodes.USER\_FOLLOWER\_SUCCESS);

}

}

/\*\*

\* 粉丝取关操作

\* 1、当前登录用户为粉丝、targetUserId 为要关注的博主

\* 2、MQ 异步更新取关和计数

\* @param targetUserId

\* @return

\*/

@Override

public ApiResult unFollowerEvent(Long targetUserId) {

LoginUser loginUser \= LoginInterceptor.threadLocal.get();

log.info("粉丝取关，粉丝：{}，博主：{}", loginUser.getId(), targetUserId);

// 1、自己不能取关自己

if (loginUser.getId() == targetUserId) {

return ApiResult.doResult(BizCodes.USER\_UN\_FOLLOWER\_NOT\_SELF);

}

// 2、判断是否已经取关过

String key \= RedisKeyConstant.USER\_FOLLOWER\_PREFIX + targetUserId;

BoundListOperations<String,String> listOps = redisCache.boundListOps(key);

// 获取列表的所有元素

List<String> values = listOps.range(0,-1);

boolean exists \= values.contains(String.valueOf(loginUser.getId()));

if (!exists) {

log.info("粉丝取关，粉丝 uid ：" + loginUser.getId() + " 已经不存在列表中，不可以取关");

return ApiResult.doResult(BizCodes.USER\_UN\_FOLLOWED);

} else {

log.info("粉丝取关，粉丝 uid ：" + loginUser.getId() + " 还存在列表中，可以取关");

UserFollowerEntity userFollowerEntity \= new UserFollowerEntity();

// 设置博主 uid

userFollowerEntity.setUserId(targetUserId);

// 设置粉丝 uid

userFollowerEntity.setFollowerId(loginUser.getId());

// 设置取关

userFollowerEntity.setIsDel(1);

// 3、发布取关事件

publishFollowerEvent(userFollowerEntity);

return ApiResult.doResult(BizCodes.USER\_UN\_FOLLOWER\_SUCCESS);

}

}

/\*\*

\* 发布关注/取关事件

\* @param userFollowerEntity

\*/

private void publishFollowerEvent(UserFollowerEntity userFollowerEntity) {

// 发消息通知粉丝关注/取消事件

List<String> keys = new ArrayList<>();

if (userFollowerEntity.getIsDel() == 0) {

// 构建粉丝关注消息，直接添加到 redis list 最左侧

keys.add(RedisKeyConstant.USER\_FOLLOWER\_PREFIX + userFollowerEntity.getUserId());

keys.add(userFollowerEntity.getFollowerId().toString());

keys.add("1"); // 关注 +1

} else {

// 构建粉丝取关消息，先判断用户是否在 redis 中，如果在 redis 就删除 redis，否则删除数据库

keys.add(RedisKeyConstant.USER\_FOLLOWER\_PREFIX + userFollowerEntity.getUserId());

keys.add(userFollowerEntity.getFollowerId().toString());

keys.add("-1"); // 取关 -1

}

String message \= JSON.toJSONString(keys);

log.info("发布粉丝关注/取关变更信息：{}", message);

// 发送 MQ 消息

defaultProducer.sendMessage(

RocketMQConstant.FOLLOWER\_TOPIC,

message,

"发布粉丝关注/取关变更信息");

}

}

这里我先来看下 redis key 是如何设计的，从下图看总共有两个 key 来分别存储「**粉丝列表**」和 「**关注列表**」。

![](images/FmgzQd5jclherTDGIkrEA4ngQY9K.png)

![](images/Fpmh2tqWbsJgp0gRYCO9oi-YAJAz.png)

![](images/Fjq_9Y1OmXJeWu_wnq1pcORLjEog.png)

![](images/FrcJyoVNRrU4hkzSCMszFMwG28Zy.png)

### **3.4.3 关注/取关 MQ**

先来看下 [ConsumerBeanConfig](http://consumerbeanconfig%20/) 是如何初始化 [Consumer](http://consumer%20/) 的，这里分两个 [Consumer](http://consumer/)。

![](images/FgNZ7WM5IgzfIZYyPiSxgtA4UTQM.png)

package net.huazai.mq.consumer;

import net.huazai.constant.RocketMQConstant;

import net.huazai.mq.config.RocketMQProperties;

import net.huazai.mq.consumer.listener.AttentionUpdateListener;

import net.huazai.mq.consumer.listener.FollowerUpdateListener;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;

import org.apache.rocketmq.client.exception.MQClientException;

import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.context.annotation.Bean;

import org.springframework.context.annotation.Configuration;

/\*\*

\* @className: ConsumerBeanConfig

\* @author: huazai，该项目是知识星球：华仔和他的朋友们 的内部项目

\* @date: 2024-09-06 0:26

\* @Version: 1.0

\* @description:

\*/

@Configuration

public class ConsumerBeanConfig {

/\*\*

\* 配置内容对象

\*/

@Autowired

private RocketMQProperties rocketMQProperties;

/\*\*

\* 粉丝关注/取关信息变更消费者

\* @param followerUpdateListener

\* @return

\* @throws MQClientException

\*/

@Bean("followerAsyncUpdateTopic")

public DefaultMQPushConsumer followerAsyncUpdateTopic(FollowerUpdateListener followerUpdateListener) throws MQClientException {

// 实例化消费者客户端

DefaultMQPushConsumer consumer \= new DefaultMQPushConsumer(RocketMQConstant.FOLLOWER\_DEFAULT\_CONSUMER\_GROUP);

// 设置 nameserver 地址

consumer.setNamesrvAddr(rocketMQProperties.getNameServer());

// 订阅 topic

consumer.subscribe(RocketMQConstant.FOLLOWER\_TOPIC, "\*");

// 注册消息监听器

consumer.registerMessageListener(followerUpdateListener);

// 启动消费者

consumer.start();

return consumer;

}

/\*\*

\* 博主关注/取关信息变更消费者

\* @param attentionUpdateListener

\* @return

\* @throws MQClientException

\*/

@Bean("attentionAsyncUpdateTopic")

public DefaultMQPushConsumer attentionAsyncUpdateTopic(AttentionUpdateListener attentionUpdateListener) throws MQClientException {

// 实例化消费者客户端

DefaultMQPushConsumer consumer \= new DefaultMQPushConsumer(RocketMQConstant.ATTENTION\_DEFAULT\_CONSUMER\_GROUP);

// 设置 nameserver 地址

consumer.setNamesrvAddr(rocketMQProperties.getNameServer());

// 订阅 topic

consumer.subscribe(RocketMQConstant.ATTENTION\_TOPIC, "\*");

// 注册消息监听器

consumer.registerMessageListener(attentionUpdateListener);

// 启动消费者

consumer.start();

return consumer;

}

}

再来看下具体的消费者实现，这里也分为两个消费者，并通过「**监听器**」的方式来进行「**并发消费**」。

### **3.4.3.1 博主关注/取关消费者**

package net.huazai.mq.consumer.listener;

import com.alibaba.fastjson.JSON;

import lombok.extern.slf4j.Slf4j;

import net.huazai.entity.UserAttentionEntity;

import net.huazai.entity.UserFollowerEntity;

import net.huazai.mapper.UserAttentionMapper;

import net.huazai.mapper.UserFollowerMapper;

import net.huazai.redis.RedisCache;

import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;

import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;

import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;

import org.apache.rocketmq.common.message.MessageExt;

import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.core.io.ClassPathResource;

import org.springframework.data.redis.core.BoundListOperations;

import org.springframework.data.redis.core.script.DefaultRedisScript;

import org.springframework.scripting.support.ResourceScriptSource;

import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

import java.util.Arrays;

import java.util.List;

/\*\*

\* 博主关注/取关信息变更监听器

\*/

@Slf4j

@Component

public class AttentionUpdateListener implements MessageListenerConcurrently {

/\*\*

\* 注入 redis

\*/

@Autowired

private RedisCache redisCache;

/\*\*

\* 注入粉丝 mapper

\*/

@Autowired

private UserAttentionMapper userAttentionMapper;

private DefaultRedisScript<Long> script;

@PostConstruct

public void init(){

script = new DefaultRedisScript<Long>();

//返回值为Long

script.setResultType(Long.class);

script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/Attentions.lua")));

}

/\*\*

\* 并发消费关注/取关消息

\* @param msgList

\* @param context

\* @return

\*/

@Override

public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> msgList, ConsumeConcurrentlyContext context) {

try {

for (MessageExt messageExt : msgList) {

String msg \= new String(messageExt.getBody());

// 解析粉丝关注信息

List<String> message = JSON.parseArray(msg, String.class);

List<String> keys = Arrays.asList(message.get(0));

String args \= message.get(1); // 其他被关注博主

int index \= message.get(0).indexOf(":");

// 获取当前博主 userId

String userId \= message.get(0).substring(index + 1);

log.info("博主 MQ 异步更新-执行数据异步更新，消息内容：{}，博主 uid：{}，其他博主 uid：{}, keys：{}， args：{}", message, userId, message.get(1), keys, args);

// 判断是关注 +1 还是取消关注 -1

if (message.get(2).equals("1")) {

// 新增关注

// 执行 lua 脚本

redisCache.executeDefault(script, keys, args);

UserAttentionEntity userAttentionEntity \= userAttentionMapper.getAttention(Integer.valueOf(userId), Integer.valueOf(message.get(1)));

if (userAttentionEntity == null) {

// 新增关注

userAttentionMapper.addAttention(Integer.valueOf(userId), Integer.valueOf(message.get(1)));

} else {

// 存在 重新关注

userAttentionMapper.delAttention(Integer.valueOf(userId), Integer.valueOf(message.get(1)), 0);

}

// TODO 关注计数

} else {

// 取消关注

// 不管是否存在 redis 直接删除

BoundListOperations<String,String> listOps = redisCache.boundListOps(message.get(0));

// 打印原始列表

log.info("博主 MQ 异步更新-其他博主原始 uid 列表：" + listOps.range(0,-1));

// 删除指定值

Long removedCount \= listOps.remove(0, message.get(1));

if (removedCount != null && removedCount > 0) {

log.info("博主 MQ 异步更新-成功删除其他博主 uid：" + message.get(1));

} else {

log.info("博主 MQ 异步更新-未找到其他博主 uid：" + message.get(1) + "，未执行删除操作");

}

// 打印删除后的列表

log.info("博主 MQ 异步更新-删除后的其他博主 uid 列表：" + listOps.range(0,-1));

// 数据库取消关注

userAttentionMapper.delAttention(Integer.valueOf(userId), Integer.valueOf(message.get(1)), 1);

// TODO 取关计数

}

}

} catch (Exception e) {

// 本次消费失败，下次重新消费

log.error("博主 MQ 异步更新 consume error, 更新数据消费失败", e);

return ConsumeConcurrentlyStatus.RECONSUME\_LATER;

}

log.info("博主 MQ 异步更新-数据消费成功, result: {}", ConsumeConcurrentlyStatus.CONSUME\_SUCCESS);

return ConsumeConcurrentlyStatus.CONSUME\_SUCCESS;

}

}

### **3.4.3.2 粉丝关注/取关消费者**

package net.huazai.mq.consumer.listener;

import com.alibaba.fastjson.JSON;

import lombok.extern.slf4j.Slf4j;

import net.huazai.entity.UserFollowerEntity;

import net.huazai.mapper.UserFollowerMapper;

import net.huazai.redis.RedisCache;

import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;

import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;

import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;

import org.apache.rocketmq.common.message.MessageExt;

import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.data.redis.core.BoundListOperations;

import org.springframework.stereotype.Component;

import org.springframework.core.io.ClassPathResource;

import org.springframework.data.redis.core.script.DefaultRedisScript;

import org.springframework.scripting.support.ResourceScriptSource;

import javax.annotation.PostConstruct;

import java.util.Arrays;

import java.util.List;

/\*\*

\* 粉丝关注/取关信息变更监听器

\*/

@Slf4j

@Component

public class FollowerUpdateListener implements MessageListenerConcurrently {

/\*\*

\* 注入 redis

\*/

@Autowired

private RedisCache redisCache;

/\*\*

\* 注入粉丝 mapper

\*/

@Autowired

private UserFollowerMapper userFollowerMapper;

private DefaultRedisScript<Long> script;

@PostConstruct

public void init(){

script = new DefaultRedisScript<Long>();

//返回值为Long

script.setResultType(Long.class);

script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/Followers.lua")));

}

/\*\*

\* 并发消费关注/取关消息

\* @param msgList

\* @param context

\* @return

\*/

@Override

public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> msgList, ConsumeConcurrentlyContext context) {

try {

for (MessageExt messageExt : msgList) {

String msg \= new String(messageExt.getBody());

// 解析粉丝关注信息

List<String> message = JSON.parseArray(msg, String.class);

List<String> keys = Arrays.asList(message.get(0));

String args \= message.get(1);

int index \= message.get(0).indexOf(":");

// 获取 userId

String userId \= message.get(0).substring(index + 1);

log.info("粉丝 MQ 异步更新-执行数据异步更新，消息内容：{}，博主 uid：{}，粉丝 uid：{}, keys：{}， args：{}", message, userId, message.get(1), keys, args);

// 判断是关注 +1 还是取消关注 -1

if (message.get(2).equals("1")) {

// 新增关注

// 执行 lua 脚本

redisCache.executeDefault(script, keys, args);

UserFollowerEntity userFollowerEntity \= userFollowerMapper.getFollower(Integer.valueOf(userId), Integer.valueOf(message.get(1)));

if (userFollowerEntity == null) {

// 新增关注

userFollowerMapper.addFollower(Integer.valueOf(userId), Integer.valueOf(message.get(1)));

} else {

// 存在 重新关注

userFollowerMapper.delFollower(Integer.valueOf(userId), Integer.valueOf(message.get(1)), 0);

}

// TODO 关注计数

} else {

// 取消关注

// 不管是否存在 redis 直接删除

BoundListOperations<String,String> listOps = redisCache.boundListOps(message.get(0));

// 打印原始列表

log.info("粉丝 MQ 异步更新-粉丝原始 uid 列表：" + listOps.range(0,-1));

// 删除指定值

Long removedCount \= listOps.remove(0, message.get(1));

if (removedCount != null && removedCount > 0) {

log.info("粉丝 MQ 异步更新-成功删除粉丝 uid：" + message.get(1));

} else {

log.info("粉丝 MQ 异步更新-未找到粉丝 uid：" + message.get(1) + "，未执行删除操作");

}

// 打印删除后的列表

log.info("粉丝 MQ 异步更新-删除后的粉丝 uid 列表：" + listOps.range(0,-1));

// 数据库取消关注

userFollowerMapper.delFollower(Integer.valueOf(userId), Integer.valueOf(message.get(1)), 1);

// TODO 取关计数

}

}

} catch (Exception e) {

// 本次消费失败，下次重新消费

log.error("粉丝 MQ 异步更新 consume error, 更新数据消费失败", e);

return ConsumeConcurrentlyStatus.RECONSUME\_LATER;

}

log.info("粉丝 MQ 异步更新-数据消费成功, result: {}", ConsumeConcurrentlyStatus.CONSUME\_SUCCESS);

return ConsumeConcurrentlyStatus.CONSUME\_SUCCESS;

}

}

### **3.4.3.3 lua 脚本实现**

\-- 从 Redis 中获取粉丝用户 ID

local followerId \= ARGV\[1\]

\-- 判断用户 ID 是否存在 KEYS\[1\] = user\_follower:uid

\-- local exists \= redis.call('EXISTS', KEYS\[1\])

\-- if exists \=\= 0 then

\-- 粉丝用户 ID 不存在，返回0

\-- redis.call('LPUSH', KEYS\[1\], followerId)

\-- else

\-- 粉丝用户 ID 存在，将其添加到列表最左侧

redis.call('LPUSH', KEYS\[1\], followerId)

\-- 判断列表长度是否大于等于 200

local listsize \= redis.call('LLEN', KEYS\[1\])

if listsize >= 200 then

\-- 列表长度超过200，从右侧弹出一个元素

redis.call('RPOP', KEYS\[1\])

end

\-- 返回列表当前长度

return listsize

\-- end

### **3.4.4 关注/取关 mapper 实现**

package net.huazai.mapper;

import net.huazai.entity.UserAttentionEntity;

import net.huazai.entity.UserFollowerEntity;

import net.huazai.model.UserAttentionDO;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/\*\*

\* <p>

\* 用户关注表 Mapper 接口

\* </p>

\*

\* @author net/huazai

\* @since 2024-09-09

\*/

public interface UserAttentionMapper extends BaseMapper<UserAttentionDO> {

/\*\*

\* 获取关注列表

\* @param user\_id

\* @return

\*/

List<Integer> getAttentions(@Param(value = "user\_id") Integer user\_id);

/\*\*

\* 获取单个关注

\* @param user\_id

\* @param attention\_id

\* @return

\*/

UserAttentionEntity getAttention(@Param(value = "user\_id") Integer user\_id, @Param(value = "attention\_id") Integer attention\_id);

/\*\*

\* 新增博主关注

\* @param user\_id

\* @param attention\_id

\*/

void addAttention(@Param(value = "user\_id") Integer user\_id, @Param(value = "attention\_id") Integer attention\_id);

/\*\*

\* 删除博主关注

\* @param user\_id

\* @param attention\_id

\*/

void delAttention(@Param(value = "user\_id") Integer user\_id, @Param(value = "attention\_id") Integer attention\_id, @Param(value = "is\_del") Integer is\_del);

}

package net.huazai.mapper;

import net.huazai.entity.UserFollowerEntity;

import net.huazai.model.UserFollowerDO;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/\*\*

\* <p>

\* 粉丝关系表 Mapper 接口

\* </p>

\*

\* @author net/huazai

\* @since 2024-09-09

\*/

public interface UserFollowerMapper extends BaseMapper<UserFollowerDO> {

/\*\*

\* 获取粉丝列表

\* @param user\_id

\* @return

\*/

List<String> getFollowers(@Param(value = "user\_id") Integer user\_id);

/\*\*

\* 获取单个粉丝

\* @param user\_id

\* @param follower\_id

\* @return

\*/

UserFollowerEntity getFollower(@Param(value = "user\_id") Integer user\_id, @Param(value = "follower\_id") Integer follower\_id);

/\*\*

\* 新增粉丝关注

\* @param user\_id

\* @param follower\_id

\*/

void addFollower(@Param(value = "user\_id") Integer user\_id, @Param(value = "follower\_id") Integer follower\_id);

/\*\*

\* 删除粉丝关注

\* @param user\_id

\* @param follower\_id

\*/

void delFollower(@Param(value = "user\_id") Integer user\_id, @Param(value = "follower\_id") Integer follower\_id, @Param(value = "is\_del") Integer is\_del);

}

package net.huazai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import net.huazai.model.UserRelationDO;

import org.apache.ibatis.annotations.Param;

/\*\*

\* <p>

\* 用户关系表 Mapper 接口

\* </p>

\*

\* @author huazai

\* @since 2024-09-08

\*/

public interface UserRelationMapper extends BaseMapper<UserRelationDO> {

/\*\*

\* 修改 user\_id 对应的粉丝数

\* @param userId

\* @return

\*/

int updateUserFollowerCount(@Param(value = "user\_id") Integer userId, @Param(value = "follower\_count") Integer follower\_count);

/\*\*

\* 修改 user\_id 对应的关注数

\* @param userId

\* @return

\*/

int updateUserAttentionCount(@Param(value = "user\_id") Integer userId, @Param(value = "attention\_count") Integer attention\_count);

/\*\*

\* 查询 user\_id 是否存在

\* @param userId

\* @return

\*/

int getUserById(@Param(value = "user\_id") Integer userId);

/\*\*

\* 新增关系数据

\* @param user\_id

\* @param follower\_count

\* @param attention\_count

\*/

void addRelation(@Param(value = "user\_id") Integer user\_id, @Param(value = "follower\_count") Integer follower\_count, @Param(value = "attention\_count") Integer attention\_count);

}

mapper xml 配置：

<?xml version="1.0" encoding="UTF-8"?>

<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">

<mapper namespace="net.huazai.mapper.UserAttentionMapper">

<!-- 通用查询映射结果 -->

<resultMap id="BaseResultMap" type="net.huazai.model.UserAttentionDO">

<id column="id" property="id" />

<result column="user\_id" property="userId" />

<result column="attention\_id" property="attentionId" />

<result column="create\_time" property="createTime" />

<result column="is\_del" property="isDel" />

</resultMap>

<!-- 通用查询结果列 -->

<sql id="Base\_Column\_List">

id, user\_id, attention\_id, create\_time, is\_del

</sql>

<select id="getAttentions" resultType="java.lang.Integer">

select attention\_id

from user\_attention

where user\_id=#{user\_id} and is\_del = 0

</select>

<select id="getAttention" parameterType="int" resultType="net.huazai.entity.UserAttentionEntity">

select \* from user\_attention where user\_id=#{user\_id} and attention\_id=#{attention\_id}

</select>

<insert id="addAttention" parameterType="int">

insert into user\_attention(user\_id,attention\_id) values(#{user\_id},#{attention\_id})

</insert>

<update id="delAttention" parameterType="int">

update user\_attention set is\_del=#{is\_del} where user\_id=#{user\_id} and attention\_id=#{attention\_id}

</update>

</mapper>

<?xml version="1.0" encoding="UTF-8"?>

<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">

<mapper namespace="net.huazai.mapper.UserFollowerMapper">

<!-- 通用查询映射结果 -->

<resultMap id="BaseResultMap" type="net.huazai.model.UserFollowerDO">

<id column="id" property="id" />

<result column="user\_id" property="userId" />

<result column="follower\_id" property="followerId" />

<result column="create\_time" property="createTime" />

<result column="is\_del" property="isDel" />

</resultMap>

<!-- 通用查询结果列 -->

<sql id="Base\_Column\_List">

id, user\_id, follower\_id, create\_time, is\_del

</sql>

<select id="getFollowers" resultType="java.lang.String">

select follower\_id

from user\_follower

where user\_id=#{user\_id} and is\_del = 0

</select>

<select id="getFollower" parameterType="int" resultType="net.huazai.entity.UserFollowerEntity">

select \* from user\_follower where user\_id=#{user\_id} and follower\_id=#{follower\_id}

</select>

<insert id="addFollower" parameterType="int">

insert into user\_follower(user\_id,follower\_id) values(#{user\_id},#{follower\_id})

</insert>

<update id="delFollower" parameterType="int">

update user\_follower set is\_del=#{is\_del} where user\_id=#{user\_id} and follower\_id=#{follower\_id}

</update>

</mapper>

<?xml version="1.0" encoding="UTF-8"?>

<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">

<mapper namespace="net.huazai.mapper.UserRelationMapper">

<!-- 通用查询映射结果 -->

<resultMap id="BaseResultMap" type="net.huazai.model.UserRelationDO">

<id column="id" property="id" />

<result column="user\_id" property="userId" />

<result column="attention\_count" property="attentionCount" />

<result column="follower\_count" property="followerCount" />

</resultMap>

<!-- 通用查询结果列 -->

<sql id="Base\_Column\_List">

id, user\_id, attention\_count, follower\_count

</sql>

<!-- 更新某用户的关注数 -->

<update id="updateUserAttentionCount" parameterType="int">

update user\_relation set attention\_count=#{attention\_count} where user\_id=#{user\_id}

</update>

<!-- 更新某用户的粉丝数 -->

<update id="updateUserFollowerCount" parameterType="int">

update user\_relation set follower\_count=#{follower\_count} where user\_id=#{user\_id}

</update>

<!-- 查询某用户是否存在 -->

<select id="getUserById" resultType="java.lang.Integer">

select count(1)

from user\_relation

where user\_id=#{user\_id}

</select>

<!--插入用户关系数据-->

<insert id="addRelation" parameterType="int">

insert into user\_relation(user\_id,attention\_count,follower\_count) values(#{user\_id},#{attention\_count},#{follower\_count})

</insert>

</mapper>

### **3.4.5 关注/取关功能效果测试**

### **3.4.5.1 博主关注功能**

swagger 执行结果：

![](images/Fi2rj5fxgSsDJOc4Z5xJS7DrLHGA.png)

日志输出结果：

![](images/FlpGyOgC-gpahB4g5AOZBvyF-YC8.png)

数据库结果：

![](images/Fna_QssiT4r4kPuUkWxhllVD6TJD.png)

Redis List 结果：

![](images/Fv3Yiu_TNWOS0bxi8WECTq7s5wtM.png)

![](images/Fp5Jdw5qmdrjuCLwfM8ED8fzP3W2.png)

![](images/FmuW_2A-dcOKxb8B2PqH3guzoGV_.png)

### **3.4.5.2 博主取关功能**

swagger 执行结果：

![](images/FttOqB5AwEYJA0jEdtc9NiTRrnJ5.png)

日志输出结果：

![](images/FnuZJFttpKvipCARDvMLh8bFuI_N.png)

数据库结果：

![](images/FsiRIiQSH4Qku6ZbUoY1mlw65BEx.png)

Redis List 结果，此时已为空：

![](images/Ft3yctD1ejYG29P7Y26XIPkVLtgr.png)

![](images/FikBvS8Q2sJuocN8-35fB7LaFqce.png)

![](images/FiWxD4swoVfe2I8d1fp2RCEcLm8d.png)

### **3.4.5.3 粉丝关注功能**

swagger 执行结果：

![](images/FraF4GVvr-DWNlGGVd1Y14xfuj86.png)

日志输出结果：

![](images/FlQ6UFDxiGDa6iBBieUTFfbL2XHr.png)

数据库结果：

![](images/Fmh4T5XC3couDHx6KAWlOqdkKjJ1.png)

Redis List 结果：

![](images/FkPI7wO3r-4MjfVF3VZILkkKqqnD.png)

![](images/FkTjNJ9oEj3FwaPOTEWmjmkK17Z2.png)

![](images/FrIeLsIxuqXEqM_Mdl-GeAxpUHA0.png)

### **3.4.5.4 粉丝取关功能**

swagger 执行结果：

![](images/FuZ8NLQn4ncLLjchMkxZR6RaDfNS.png)

日志输出结果：

![](images/FqyZNNAZ0obRVeDvEjuxdb6jzvLq.png)

数据库结果：

![](images/FmyVZX-1_Wfc0bAY2uYeKFjZR_2J.png)

Redis List 结果，此时已为空：

![](images/Fv7Q16fYeUb4ZlvjDRDeygB4081K.png)

![](images/FseKDiq8lDJcEOHa5x7u2lvuxEhs.png)

### ![](images/Fk6vor81AHL4ZH60zVmnoH_vrV6a.png)

### **3.4.6 关注/取关功能处理流程图**

![](images/lnPU3iLElh1KxeWr_wteXXZHieyF.png)

## **3.5 关注列表/粉丝列表功能设计与开发**

/\*\*

\* 获取博主的粉丝列表

\* @param targetUserId

\* @return

\*/

@Override

public List<UserFollowerListVO> getFollowerList(Long targetUserId) {

// TODO 热 key 检测

// 先从缓存中获取粉丝列表

List<UserFollowerListVO> list = getFollowerListFromCache(targetUserId);

if (list == null || list.size() == 0) {

// 从数据库获取粉丝列表

list = getFollowerListFromDB(targetUserId);

}

if (list == null || list.size() == 0) {

// 如果都为空直接返回空 list

list = new ArrayList<>();

}

return list;

}

/\*\*

\* 从缓存中获取粉丝列表信息

\* @param targetUserId

\* @return

\*/

private List<UserFollowerListVO> getFollowerListFromCache(Long targetUserId) {

// redis 缓存key

String key \= RedisKeyConstant.USER\_FOLLOWER\_PREFIX + targetUserId;

BoundListOperations<String,String> listOps = redisCache.boundListOps(key);

// 获取列表的所有元素

List<String> userFollowerInfo = listOps.range(0,-1);

log.info("社交服务模块-从缓存中获取博主粉丝信息，targetUserId：{}，userFollowerInfo：{}", targetUserId, userFollowerInfo);

/\*\*

\* 如果不为空

\*/

if (userFollowerInfo != null && userFollowerInfo.size() > 0) {

// 获取粉丝详情信息

List<String> userKeys = new ArrayList<String>();

for (int i \= 0; i < userFollowerInfo.size(); i++) {

// 拼装用户信息缓存 key

userKeys.add(RedisKeyConstant.USER\_INFO\_PREFIX + userFollowerInfo.get(i));

}

// 批量获取

List<UserFollowerListVO> userInfos = redisCache.mget(userKeys);

log.info("社交服务模块-从缓存中获取粉丝基础信息，userKeys：{}，userInfos：{}", userKeys, userInfos);

return userInfos;

}

return null;

}

/\*\*

\* 从数据库中获取粉丝列表信息

\* @param targetUserId

\* @return

\*/

private List<UserFollowerListVO> getFollowerListFromDB(Long targetUserId) {

// 从数据库中获取粉丝列表信息

List<String> userFollowerInfo = userFollowerMapper.getFollowers(targetUserId.intValue());

log.info("社交服务模块-从数据库中获取博主信息，userFollowerInfo：{}", userFollowerInfo);

if (userFollowerInfo != null && userFollowerInfo.size() > 0) {

// 获取粉丝详情信息

List<String> userKeys = new ArrayList<String>();

for (int i \= 0; i < userFollowerInfo.size(); i++) {

// 拼装用户信息缓存 key

userKeys.add(RedisKeyConstant.USER\_INFO\_PREFIX + userFollowerInfo.get(i));

}

// 批量获取

List<UserFollowerListVO> userInfos = redisCache.mget(userKeys);

log.info("社交服务模块-从缓存中获取博主对应粉丝基础信息，userKeys：{}，userInfos：{}", userKeys, userInfos);

if (userInfos == null && userInfos.size() == 0) {

// TODO 这里需要从用户中台获取

}

// 设置缓存

String key \= RedisKeyConstant.USER\_FOLLOWER\_PREFIX + targetUserId;

redisCache.lPushAll(key, userFollowerInfo);

return userInfos;

}

return null;

}

/\*\*

\* 获取博主的关注列表

\* @return

\*/

@Override

public List<UserAttentionListVO> getAttentionList() {

// TODO 热 key 检测

LoginUser loginUser \= LoginInterceptor.threadLocal.get();

log.info("博主取关，当前博主：{}", loginUser.getId());

// 先从缓存中获取关注列表

List<UserAttentionListVO> list = getAttentionListFromCache(loginUser.getId());

if (list == null || list.size() == 0) {

// 从数据库获取关注列表

list = getAttentionListFromDB(loginUser.getId());

}

if (list == null || list.size() == 0) {

// 如果都为空直接返回空 list

list = new ArrayList<>();

}

return list;

}

/\*\*

\* 从缓存中获取粉丝列表信息

\* @param userId

\* @return

\*/

private List<UserAttentionListVO> getAttentionListFromCache(Long userId) {

// redis 缓存key

String key \= RedisKeyConstant.USER\_ATTENTION\_PREFIX + userId;

BoundListOperations<String,String> listOps = redisCache.boundListOps(key);

// 获取列表的所有元素

List<String> userAttentionInfo = listOps.range(0,-1);

log.info("社交服务模块-从缓存中获取博主关注列表信息，targetUserId：{}，userAttentionInfo：{}", userId, userAttentionInfo);

/\*\*

\* 如果不为空

\*/

if (userAttentionInfo != null && userAttentionInfo.size() > 0) {

// 获取粉丝详情信息

List<String> userKeys = new ArrayList<String>();

for (int i \= 0; i < userAttentionInfo.size(); i++) {

// 拼装用户信息缓存 key

userKeys.add(RedisKeyConstant.USER\_INFO\_PREFIX + userAttentionInfo.get(i));

}

// 批量获取

List<UserAttentionListVO> userInfos = redisCache.mget(userKeys);

log.info("社交服务模块-从缓存中获取关注列表基础信息，userKeys：{}，userInfos：{}", userKeys, userInfos);

return userInfos;

}

return null;

}

/\*\*

\* 从数据库中获取关注列表信息

\* @param userId

\* @return

\*/

private List<UserAttentionListVO> getAttentionListFromDB(Long userId) {

// 从数据库中获取关注列表信息

List<String> userAttentionInfo = userFollowerMapper.getFollowers(userId.intValue());

log.info("社交服务模块-从数据库中获取博主信息，userAttentionInfo：{}", userAttentionInfo);

if (userAttentionInfo != null && userAttentionInfo.size() > 0) {

// 获取粉丝详情信息

List<String> userKeys = new ArrayList<String>();

for (int i \= 0; i < userAttentionInfo.size(); i++) {

// 拼装用户信息缓存 key

userKeys.add(RedisKeyConstant.USER\_INFO\_PREFIX + userAttentionInfo.get(i));

}

// 批量获取

List<UserAttentionListVO> userInfos = redisCache.mget(userKeys);

log.info("社交服务模块-从缓存中获取博主对应粉丝基础信息，userKeys：{}，userInfos：{}", userKeys, userInfos);

if (userInfos == null && userInfos.size() == 0) {

// TODO 这里需要从用户中台获取

}

// 设置缓存

String key \= RedisKeyConstant.USER\_ATTENTION\_PREFIX + userId;

redisCache.lPushAll(key, userAttentionInfo);

return userInfos;

}

return null;

}

对于一个海量用户应用来说，读取用户的「**关注列表**」和「**粉丝列表**」属于「**高并发读**」场景。

因此我们在设计用户关注功能时，都会限制每个用户的「**最大关注数量**」，这也是为了防止用户滥用关注功能进行刷粉、刷流量等影响用户体验。

我们都知道微博是限制一个用户「**最多可关注 2000 人**」，意味着用户关注列表的长度不会超过「**2000**」人。因此我们可以把用户的关注列表全量缓存到 Redis 中。

而「**粉丝列表**」则不同，对用户拥有多少粉丝是没有限制的，这就意味着粉丝列表的长度可能达到数百万人、上千万人甚至上亿人，Redis 无法全量缓存这些数据。

不过，对于粉丝量巨大的大 V 来说，大部分用户只会简单地查看粉丝列表的前几页，很少有用户会专门耗费大量时间查看全部粉丝。所以对于「**粉丝列表**」仍然可以使用 Redis 缓存，只不过仅缓存用户最近的「**10000**」个粉丝。

对于查询最近「**10000**」个粉丝的用户请求，会优先查询 Redis 缓存，而对于查询「**10000**」个以外粉丝的用户请求，才会查询数据库。由于前者在读取粉丝列表的请求中占绝大多数，所以缓存最近「**10000**」个粉丝已经足以应对高并发的请求量。

如果有黑客恶意发起大量请求拉取最近「**10000**」个粉丝以外的粉丝列表，那么这些请求会统统访问数据库，可能造成数据库宕机。我们可以采用「**限流**」方式来解决这个问题。

比如在收到读取粉丝列表的请求时，关系服务先检查此请求查询的数据是否在最近「**10000**」个粉丝之外，如果是，则检查此时的请求量是否已达到限流阈值，对于超过限流阈值的请求拒绝执行。

接下来，我们来分析下「**关注列表**」和「**粉丝列表**」缓存更新以及失效问题。

对于频繁发生「**关注/取关事件**」就会造成缓存被频繁的生成和删除，那么缓存也没什么意义了，因此，对于「**粉丝列表**」来说，其缓存更新策略不适合「**先更新数据库，再删除缓存**」。而是缓存总是存在的，所以更适合随着数据库更新而更新，这里我们只需在消费者端将其存入 Redis 即可。

而对于「**关注列表**」来，采取「**先更新数据库，再删除缓存**」是没有问题的，毕竟某一个用户的关注列表相对比较稳定，这里为了方便就统一处理了。

综上，在「**关注/取关事件**」发生后，为了应对「**缓存失效**」问题，「**关注列表**」缓存可以被删除，而「**粉丝列表**」缓存更适合总是存在，随数据库变更而变更。

这一版的缓存+数据库读取方案就到此为止了，后续再进行优化和完善。测试效果如下：

  
![](images/Fh4Czb0IRC2_gxoejEclCEoxxkui.png)

![](images/Fph3DD7ggmIOztl_SolFXnYDcm3U.png)