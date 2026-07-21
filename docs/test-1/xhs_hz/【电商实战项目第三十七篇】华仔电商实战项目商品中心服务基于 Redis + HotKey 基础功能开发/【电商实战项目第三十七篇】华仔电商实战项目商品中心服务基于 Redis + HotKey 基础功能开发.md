## 【电商实战项目第三十七篇】华仔电商实战项目商品中心服务基于 Redis + HotKey 基础功能开发

从今天之后的一段时间内，华仔会带着大家一起从零开始搭建并研发一套高并发的电商实战项目，这里会涉及到很多互联网大厂开发过程中所使用的核心技术和架构设计模式，希望大家学完之后可以用到自己的简历中。

接下来我们会重点架构设计和开发一下我们高并发电商实战项目中的「**商品中心服务**」。

这是第三十七篇，本篇我们继续进行电商实战项目设计与开发，本篇对「**商品中心服务**」中基础功能开发。

文章汇总位置：[https://wx.zsxq.com/dweb2/index/columns/51122554151214](https://wx.zsxq.com/dweb2/index/columns/51122554151214)

![](images/Fnl1ueruAt4exbk9U2e5CcbjFHDI.png)

源码授权与获取地址：[https://articles.zsxq.com/id\_1s85grnaae4p.html](https://articles.zsxq.com/id_1s85grnaae4p.html)

![](images/FmkphgNM2yJBSqCq4raWXJA2r2Ah.png)

本章源码地址：[https://gitcode.net/u011359591/huazai-ecshop/-/tree/ecshop-chapter-37](https://gitcode.net/u011359591/huazai-ecshop/-/tree/ecshop-chapter-37)

## **01 前言**

终于要设计与研发电商项目代码了，今天我们主要对「**商品中心服务**」基础功能开发。

这里需要注意下，我们整个项目目前是不提供前端的，这个后续有时间在搞，主要是进行后端接口以及微服务模块架构设计。

## **02 商品中心服务基础功能开发**

关于「**商品添加/修改**」及相关 「**品牌**」、「**分类**」、「**属性**」等管理开发都属于「**运营后台**」的功能，等后续开发「**后台**」时再进行开发。

这里我们只处理 「**C 端**」业务的「**商品列表**」、「**商品搜索**」、「**商品详情页**」、「**品牌列表**」、「**分类搜索**」、「**属性列表**」功能开发。

本篇我们就先来开发基础相关功能，后续「**商品列表**」、「**商品搜索**」、「**商品详情页**」都会进行「**百万级甚至千万级流量架构设计**」。

## **2.1 新建工程**

新建工程，跟之前一样。

  
![](images/Fj3dz5MDz1bTo-4zlBMXuYHAOKpT.png)

添加依赖：

<dependencies>

<dependency>

<groupId>net.huazai</groupId>

<artifactId>huazai-common</artifactId>

<version>1.0-SNAPSHOT</version>

</dependency>

<dependency>

<groupId>org.apache.rocketmq</groupId>

<artifactId>rocketmq-client</artifactId>

<version>5.3.0</version>

<scope>compile</scope>

</dependency>

</dependencies>

##   
**2.2 商品服务基础功能开发**

对于商品服务来说，主要功能如下：

1.  添加/修改/删除/管理商品：这是运营后台的功能，这里暂不展开，等后续开发后台时再看。
2.  商品分类列表：前台只需查看分类列表即可，至于管理分类也属于运营后台的功能。
3.  商品品牌列表：前台只需查看品牌列表即可，至于管理品牌也属于运营后台的功能。
4.  商品列表：分页查询商品列表，支持关键词或者固定查询条件进行查询。
5.  商品详情页：浏览商品详情页，根据不同规则组合 SKU 展示不同的商品详情页功能。
6.  商品库存：这块会放到下单时进行架构设计与开发，这里暂不展开。
7.  商品价格服务：对于大型电商或者个性化服务来说，商品价格都会单独成立一个服务的，我们这里就暂不拆开了，后续看需要再拆。

  
分别来看下。

### **2.2.1 商品分类列表**

由于「**小红书**」（左图）的电商比较简单，只有「**商品列表**」、「**购物车**」、「**订单列表**」功能，这里我们以「**京东**」（右图）为例。

  
![](images/FiNMxKMVMl96laAtNvzDngBvjn4M.jpg)![](images/FpwVYs_aTdqK1snsMkTFnPyX01ro.png)

我在数据库添加了一些数据，如下：

  
![](images/FhANUK9mnIr3cbntE2GixMK52Y2e.png)

### **2.2.1.1 一级商品分类列表**

由于分类信息基本上变化不大，这里我们优先从「**本地热 key 缓存**」+ 「**分布式 Redis 缓存**」读取，不存在再从 「**DB**」读取。

  
![](images/FjsI7UabIKzUl8EsLU0mbuwLqbAH.png)

![](images/FswP-4N_Wz5pNKfP5bam0sdWlCBP.png)

![](images/Fob-ZaCforNz368Hp_TpIUEFyvqv.png)

效果如下：

  
![](images/FhnaPh4HcUsWENNzOmLBdgqhwpBj.png)

从日志可以看出，第一次读取缓存是没有的，直接从数据库获取。

![](images/FhnP_echKbymjpG_ptUIiWWXxLDX.png)

第二次再次读取时就有了，直接从缓存中返回结果集。

![](images/FgbjrPDyGsd9ATuSbUNubZ6O9uTz.png)

Redis 缓存数据：

![](images/Ft6pIAPp47KqpWqIZdCV1UjQtdq0.png)

### **2.2.1.2 二级商品分类列表**

二三级商品分类相对比较复杂，先来看下 SQL：

![](images/FgjQxgNAIlxqLtTmQgZWBnZAvxkZ.png)

这里也一样，分类信息基本上变化不大，这里我们优先从「**本地热 key 缓存**」+ 「**分布式 Redis 缓存**」读取，不存在再从 「**DB**」读取。

  
![](images/FpQ4i9luMxSdjM-H7FC0SvLHI7W7.png)

![](images/FnWQO8_O73JSDm-VBPsOPgyAv0BQ.png)

![](images/FhxFJteVCcs57VxFCPuGTi1NV5wx.png)

Redis 缓存数据：

![](images/Fk9HILTVHHslZkTqHX12RvIC-rOg.png)

### **2.2.2 品牌列表**

由于品牌信息基本上变化不大，这里我们优先从「**本地热 key 缓存**」+ 「**分布式 Redis 缓存**」读取，不存在再从 「**DB**」读取。

  
![](images/FgoFVZGQYAGEtAFBWLsHGASh6N7b.png)

![](images/FusCg1oPwx8IQu-zMahQ8FehGLZN.png)

![](images/Foq0fob7B5WPxhRvlz5DXFCQiWh5.png)

效果如下：

![](images/FlRMhNNIKmdbHgFQe19mxLGg0sWO.png)

从日志可以看出，第一次读取缓存是没有的，直接从数据库获取。

![](images/FvC-yT7TYyVjFYsQsJO-8qTConJ2.png)

第二次再次读取时就有了，直接从缓存中返回结果集。

![](images/FsRk14ifYqxNLi9RtXMLy9mEhcN2.png)

Redis 缓存数据：

![](images/Fr_WZ_Eqsckdf3QuWpNENnn0BOqt.png)

### **2.2.3 商品列表**

通过前面两篇，我们知道「**商品**」分为「**SPU**」和「**SKU**」，其中 「**SPU**」包括一个或者多个「**SKU**」，即可以组合 「**SKU**」。

这里的商品列表以「**SPU**」为主，会返回 「**SPU**」基础信息及对应的 「**SKU**」id 列表，如果需要其他信息也可以补充。

  
![](images/FrvHA5c9bENR0fnAByqls6ak9cvv.png)

测试效果：

![](images/FrPRfxqIEjTCwbOYq9vcjAfi-iVm.png)

当然也可以直接以 「**SKU**」为主，这里就简单实现了，后续会引入「**ElasticSearch**」进行百万级商品搜索架构改造。

  
![](images/FlAPOaIZMGiwyN6fGZX1snt8J57A.png)

测试效果：

  
![](images/Fm3ythv9tqGLW-tr7lBrmfu_w0qz.png)

### **2.2.4 商品详情页**

这里也简单的实现下，后续篇章会参考「**京东商品详情页**」进行「**亿级流量架构设计**」。

  
![](images/FsoSb9XcNvniaJVZT2zpsv2ZLptz.png)

测试效果：

![](images/FlfxP-lWZSYdvugyXV9rUCjGGuUz.png)

![](data:image/png;base64,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)

扫码加入星球

查看更多优质内容

https://wx.zsxq.com/mweb/views/joingroup/join\_group.html?group\_id=51122554151214