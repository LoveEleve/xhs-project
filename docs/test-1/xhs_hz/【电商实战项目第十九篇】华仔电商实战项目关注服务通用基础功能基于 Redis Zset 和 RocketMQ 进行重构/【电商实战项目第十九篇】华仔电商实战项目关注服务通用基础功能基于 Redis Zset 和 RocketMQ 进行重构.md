大家好，我是**华仔**, 又跟大家见面了。

从今天之后的一段时间内，华仔会带着大家一起从零开始搭建并研发一套高并发的电商实战项目，这里会涉及到很多互联网大厂开发过程中所使用的核心技术和架构设计模式，希望大家学完之后可以用到自己的简历中。

接下来我们会重点架构设计和开发一下「**社交系统**」中的重要服务：「**关注服务**」、「**计数服务**」、「**评论服务**」。

这是第十九篇，本篇我们继续进行电商实战项目社交微服务中「**关注服务**」通用基础功能设计与开发，本篇基于 Redis Zset 来进行「**关注列表**」、「**粉丝列表**」存储。

文章汇总位置：[https://wx.zsxq.com/dweb2/index/columns/51122554151214](https://wx.zsxq.com/dweb2/index/columns/51122554151214)

![](images/Fnl1ueruAt4exbk9U2e5CcbjFHDI.png)

源码授权与获取地址：[https://articles.zsxq.com/id\_1s85grnaae4p.html](https://articles.zsxq.com/id_1s85grnaae4p.html)

![](images/FmkphgNM2yJBSqCq4raWXJA2r2Ah.png)

本章源码地址：[https://gitcode.net/u011359591/huazai-ecshop/-/tree/ecshop-chapter-19](https://gitcode.net/u011359591/huazai-ecshop/-/tree/ecshop-chapter-19)

## **01 前言**

终于要设计与研发电商项目代码了，今天我们要对社交微服务中关注服务通用基础功能设计与开发。

这里需要注意下，我们整个项目目前是不提供前端的，这个后续有时间在搞，主要是进行后端接口以及微服务模块架构设计。

## **02 关注服务架构设计与开发**

在上篇 [【电商实战项目第十八篇】华仔电商实战项目关注服务通用基础功能基于 Redis List 和 RocketMQ 设计与开发](https://articles.zsxq.com/id_rk4je6vvs398.html) 中，我们已经基于 Redis List 结构以及 RocketMQ 异步实现「**博主关注、取关**」、「**粉丝关注、取关**」事件的处理。

但是 Redis List 结构可能会出现重复数据且无法自动排序，所以今天我们打算再来基于 Redis Zset 结构实现一遍，自己可以根据情况来选择， Redis Zset 结构天然支持「**去重**」和 「**排序**」。

这里我们为了支持「**高并发场景**」，通过引入 RocketMQ 来进行流量削峰，「**异步处理**」博主/粉丝关注、取关事件的，以及后续的关注计数。

先把场景说一下，可能大家还分不清。

**粉丝关注/取关功能图如下，即我的粉丝：**

![](images/FpuC4QGDOXZFTmHirU5AoYYYlvvw.png)

**博主关注/取关功能图如下，即我关注的其他博主：**

![](images/FjN8tVfgLq7c4BM6DHMhZqM-tTUd.png)

## **2.1 关注/取关功能设计与开发**

如果要基于 Redis Zset 结构来实现的话，其实我们只需要修改下对应的 ServiceImpl 以及 RocketMQ 「**消费者**」代码实现以及 「**Lua 脚本实现**」即可。

这里为了不改动之前的代码，我们新复制一份代码，我们来分别看下。

###   
**2.1.1 关注/取关功能服务改动**

在上篇，我们主要是使用 Redis List 结构实现，本篇，我们将改成 Redis Zset 结构来实现。

![](images/FpdPWon_YSl0AFF8813w4eYayo80.png)

![](images/FivY3ZfdbiyYYSl6ctfOvyqxpnsj.png)

![](images/FhUsabD7hnUMwSB5tI8H6WGjWffv.png)

  
![](images/FnfQh8F2zAKKiqVswEFrH5UbpZtX.png)

## **2.2 关注/粉丝列表功能设计与开发**

### **2.2.1 关注/粉丝列表功能服务改动**

改动同上，只需要修改下从缓存中读取数据即可。

![](images/Fgg51VzXRoq3FWIRuxwrzoPm9MZ1.png)

![](images/Fiohs_OsomUFExhN56PQ1tZcj-zY.png)

可以看到这里就是简单换了一下 Redis 读取方式即可，其他部分都不用修改。

这里我先来看下 redis key 是如何设计的，从下图看总共有两个 key 来分别存储「**粉丝列表**」和 「**关注列表**」。

  
![](images/Fth6Ibf4WqJb3dv_oJYBB1ZAY5Nq.png)

![](images/FrCLcZxOxSy5Fektj06pkrIvkH6j.png)

![](images/FrmqOZD0aExKRnrvYSVoBsWWJNaL.png)

可以看到都从之前的 List 换成 Zset 结构了。

RocketMQ 消息没有变动，还是跟之前一样，如下：

![](images/FkLn3lsD9AXaGo5hpKQH8CZTdyM4.png)

![](images/FhkQL-htVU9R97OWyahLQ2xcqTHQ.png)

## **2.3 关注/取关功能 MQ 设计**

先来看下 [ConsumerBeanConfig](http://consumerbeanconfig%20/) 是如何初始化 [Consumer](http://consumer%20/) 的，这里分两个 [Consumer](http://consumer/)。

这里为了方便对比，重新复制了「**消费者监听器**」和 「**Lua 脚本**」。

  
![](images/FgD8tw-oruiPgG1TGTrhaFbGvbmO.png)

消费者监听启动变动如下：

![](images/FlnNGqCSeYdlJ2u34tTWZGFFINO4.png)

![](images/FoAZDFjDMSJP7MTqQx4w19dRCqLh.png)

再来看下具体的消费者实现，这里也分为两个消费者，并通过「**监听器**」的方式来进行「**并发消费**」。

###   
**2.3.1 博主关注/取关消费者**

这里改动的就是「**Lua 脚本**」以及「**取关事件移除对应元素**」。

  
![](images/Fjbp2pyxsw5jbn55iqJgNBFqEjOH.png)

![](images/Fst1MXLGdNBtLsMmXDdnszuwWD8M.png)

### **2.3.2 粉丝关注/取关消费者**

这里改动的就是「**Lua 脚本**」以及「**取关事件移除对应元素**」。

![](images/Fi3TBXKDOvKKmsrkGxoWWWWMN4FP.png)

![](images/FrbsOMMCDnfvsfRoxnY_eAuFkEa7.png)

## **2.4 关注/取关功能处理流程图**

![](images/lnsALvQf8Ke-jR6Mzc5xS03hfjWW.png)

## **2.5 关注/取关功能效果测试**

### **2.5.1 博主关注功能**

swagger 执行结果：

  
![](images/Fv6VwEtdwAwLGx39w75ZDDVHtR_n.png)

日志输出结果：

  
![](images/Fhphc3XaBSZxaGFQz9qmJLMwbIK9.png)

  
数据库结果：

有一条关注数据：

![](images/FsXdjpyPNlyTKYPedj7BZP1Fs7o0.png)

关注数此时为1：

![](images/FmUjo_drt2nGn9sYZkicZMQLMEW3.png)

Redis Zset 结果：

  
![](images/Flg4COC-TskZ97DLofvftLkq6gMr.png)

![](images/Fn7kBTnRRzlETFufS3MpAdHRVF9X.png)

![](images/FpVt8vRzM5sEIrITweZPfIsLz37-.png)

此时关注列表有一条数据：

![](images/FhD650Z5YPhj-_GEowU0Rc8HubYA.png)

### **2.5.2 博主取关功能**

swagger 执行结果：

![](images/FjKHUpTfcGuqr_-Jz187D5x88n1K.png)

日志输出结果：

  
![](images/Fg0vg3C-9nmisFwzCtDglyylaL5p.png)

数据库结果为取关：

  
![](images/FkEPcG2BHPaYzEAHmS4jOLpQorVt.png)

此时关注数为0：

![](images/Fkkl_hcU_rn5Cox4VBfkOGKphn0R.png)

Redis Zset 结果，此时已为空：

  
![](images/FvZFL3bZAYoEeewb0JSfIv0sBdeJ.png)

![](images/Flf5BAbS2_s8ZirXYuJOeGRozcWu.png)

![](images/FnuOjqbIQz_UekTAGGfTOfknaq7H.png)

此时关注列表为空：

![](images/FsT7JE_h5jg_8t2DP4R43qIIqJzJ.png)

### **2.5.3 粉丝关注功能**

swagger 执行结果：

![](images/FtLvX9Uo8NBkyrkhkTmQzA0EKYa4.png)

日志输出结果：

![](images/FmQjo-8o-_jvdwHngZijCjuARqso.png)

数据库结果：

![](images/FjFhQTr_LQzjawnHPFguloS3d8VU.png)

此时会新增一条关系数据，然后粉丝数为 1：

![](images/Fi0slz7rr0XVHczGkDp_OESwAzpB.png)

Redis Zset 结果：

![](images/FsUV3YDOV2zvKdSruu0FCy-9eZps.png)

![](images/FpLS4jS9W9LtvkJoBrUPQZAbWrdU.png)

![](images/Fkf3TD3f1xzZ0QehaXe7NfYbw4zW.png)

此时粉丝列表有一条数据：

![](images/FhvcpSOPOGWrLTShQ1Tj7jR6T6nx.png)

### **3.4.5.4 粉丝取关功能**

swagger 执行结果：

![](images/FsaNzS1lJ9R2DY8yyboH6a4iAVtZ.png)

日志输出结果：

![](images/FpJ08stD5_ciJ4vScJGX3Wr9k6Qf.png)

数据库结果：

![](images/FnIzUvWspNqPYyc2TBuK0Rwyy9c4.png)

此时粉丝数为 0：

![](images/FmdT0izMa4DymT4J_8BzRQkPkck-.png)

Redis Zset 结果，此时已为空：

![](images/Fp0JXU147DEMppvEMaKhrBJlJLM2.png)

![](images/Fkg9UOZXQa8scL-IPEbow3uc_EET.png)

### ![](images/FgZs63l9ZQY2M9dBHD6kfM-2R39T.png)

此时粉丝列表为空：

![](images/FvJdjGg9KJRyssA8Oqm-HaWe_F8C.png)

至此，这一版的缓存+数据库读取方案就到此为止了，后续再进行优化和完善。