从今天之后的一段时间内，华仔会带着大家一起从零开始搭建并研发一套高并发的电商实战项目，这里会涉及到很多互联网大厂开发过程中所使用的核心技术和架构设计模式，希望大家学完之后可以用到自己的简历中。

接下来我们会重点架构设计和开发一下我们高并发电商实战前端 Web 项目的相关功能 。

这是第六十七篇，本篇我们继续进行电商实战项目设计与开发，本篇我们正式接入前端 Web 项目中的「**商品**」、「**购物车**」、「**卡券**」功能。

文章汇总位置：[https://wx.zsxq.com/dweb2/index/columns/51122554151214](https://wx.zsxq.com/dweb2/index/columns/51122554151214)

![](images/Fnl1ueruAt4exbk9U2e5CcbjFHDI.png)

源码授权与获取地址：[https://articles.zsxq.com/id\_1s85grnaae4p.html](https://articles.zsxq.com/id_1s85grnaae4p.html)

![](images/FmkphgNM2yJBSqCq4raWXJA2r2Ah.png)

本章源码地址：[https://gitcode.net/u011359591/huazai-ecshop/-/tree/ecshop-chapter-67](https://gitcode.net/u011359591/huazai-ecshop/-/tree/ecshop-chapter-67)

## **01 前言**

前面几篇中，我们已经将后管服务中的「**优惠券**」相关的业务铺垫的差不多了，接下来我们正式接入前端 Web 项目中的「**商品**」、「**购物车**」、「**卡券**」功能。

前端功能比较费时间，这里先占位，晚点更新。

##   
**02 商品服务接入**

## **2.1 商品分类接入**

效果如下，内容比较多，有的展示不全，凑合看：

![](images/Fqu6-bDQbZyoBLW5igQ9H0iQCQIf.png)

点击「**推荐**」会出现「**顶级分类**」，点击「**顶级分类**」会出现「**二三级分类**」，点击「**二三级分类**」之后会去搜索对应的商品列表。

前端代码修改位置：

  
![](images/Fru4OQXzrT17nqPVVDZYWlpGCly6.png)

##   
**2.2 商品列表接入**

这里我们会通过 ES 进行搜索，之前已经搞好了 ES 的「**全文检索**」、「**结构化检索**」接口：[【电商实战项目第四十四篇】华仔电商实战项目商品中心服务Elasticsearch 10W商品搜索及suggest接口开发](https://articles.zsxq.com/id_76xgx3mkx3z4.html)

目前无法直接将搜索结构下放到前端接口，需要后端接口通过简单的封装再进行「**ES 结构化检索**」处理，代码如下：

![](images/FmitFIkB_r4y2pA3wNSgJcF3BGc6.png)

前端页面效果：

![](images/Fj2Y939KLNWDFZ_GZm_rjgu9M0bp.png)

## **2.3 商品详情接入**

前端代码修改位置：

  
![](images/FmQspcM9JQkduFbPtKcJYMXCrVoe.png)

这里就不做那么复杂的商详了，简单搞一个弹框来展示，效果如下：

![](images/FgLRzB2GG0djvo7ZZAxmx-EX5lCN.png)

当添加购物车成功会进行如下提示：

![](images/FjLhQDZgthPUZgSUwU8Qd98TGyCb.png)

这里会展示「**商详轮播图**」、「**商详详情图**」、「**品牌**」、「**分类**」、「**商品价格**」、「**优惠券**」等信息。最后可以点击「**加入购物车**」操作将对应商品加入到购物车中。

我们来看下详情页展示的代码，也比较简单，这里通过「**缓存**」来扛读并发，「**缓存**」不存在会存数据库中渲染。

![](images/FmysT-GAV7KUgDQx8UsAx80mGUxr.png)

![](images/Fmcx6VyYW1OmC32sjgu8_cTjEN4b.png)

![](images/FnVz1QWRtjSEQMPgDU6rQuQRGtUW.png)

## **03 购物车服务接入**

前端代码修改位置：

  
![](images/Foj8nNE_atQz5QmkBbW8-XsJcdTv.png)

## **3.1 添加购物车**

上一节已经将商品「**加入购物车**」了，之前的代码只支持一次增加一个购买数，这里我们放开了，不过单商品最大支持添加 10 个。

修改代码如下：

  
![](images/FveAhr_VivefOcacqRyo24vGfcC3.png)

  
![](images/FueoelElkYweWkWgEBqi5qU0iTA3.png)  

  
![](images/FpeItF_Ng2ea9YJkbTtRbglEOG1l.png)

这样通过 MQ 异步添加到数据库中的购物车数据如下：

![](images/FsAk3RhfzzEEuAqQEtrlA4wDJpwW.png)

## **3.2 购物车列表**

这里购物车列表数据直接从「**Redis 缓存**」中进行读取，前端进行分页处理，效果如下：

![](images/Flv1jizp7ENIbLWksi8mfmx45EBs.png)

首先这里分页默认不是中文的，需要做语言支持，如下：

![](images/Fm5g6K0GCSqsB4OksMoz3c9enU74.png)

## **3.2 删除购物车、清空购物车**

这里支持「**单个删除**」、「**批量删除**」购物车，「**清空购物车**」等操作，效果如下：

  
![](images/Fl06WMIApd6LsZ52ia6btxhmdhOr.gif)

![](images/Fl_UsfRxioy8iLPUQxbEy_K3p9ch.gif)

## **3.3 修改购物车**

这里修改暂且只支持修改「**购买数**」，这个比较简单，前端只需算好当前是该 +1 还是 -1。

  
![](images/FnDBJ-e6nEmJc0XKGIE5mAtN3DrV.png)

  
![](images/FkUGbqYESXooFNwoBBMgcB1rb_Da.gif)

## **04 卡券服务接入**

这里我们重点是前端的实现，关于后端「**优惠券**」的「**高并发兑换/领取**」、「**高并发查询可用/不可用优惠券**」会在后面篇章单独介绍。

## **4.1 商详展示优惠券**

首先在后台创建两个「**指定商品的优惠券模板**」，如下：

  
![](images/Fuu3spCP07OiadXI6P4QmaJKMtbS.png)

![](images/FvE1Qwnjz7tmujyixJ_obgS5PlGB.png)

前端会在「**商详页**」展示对应的「**优惠券列表**」，如下：

![](images/Fts5hmARED2WtRgDmwQFlWOppwBH.png)

这里实现的后端代码比较简单，后续会加上「**缓存处理**」：

![](images/FnyYlDX8k3QVDrslTGNrbxxGhCRm.png)

这里还处理了当前用户是否已经领取过该优惠券了，如下：

![](images/FsOU0An12ixWS8PEt0PBei6GyioQ.png)

前端核心代码如下：

![](images/FhaljZpH3Kg_Y3Ae3cj8bBkD9U0m.png)

![](images/Fij1hXAFXwc9qCWxQ6LOttbw67xz.png)

## **4.2 领取优惠券**

当展示完所关联的「**优惠券列表**」后，点击对应的「**优惠券**」可以直接领取，如下：

  
![](images/Fmtc5sJv_KDA6OVHtH_UrQFx8Tco.png)

![](images/FjjyL_WRCyh5nRvFsieGe8VEhMJN.gif)

这里的商品优惠价会通过「**优惠券**」的最大减额进行 js 重新计算：

  
![](images/FgXgL4fAsMZf_YBnHDI3QV2X1hed.png)

加入购物车时也通过这个优惠价进去重新计算：

![](images/FmXMEIuyS4ixK9zcRcYlrBrwOuz_.png)

代码实现如下：

![](images/Fo18xY95mZvgQXxyJhpbcth5xLFT.png)

## **4.3 优惠券列表**

当「**领取优惠券**」完成后，可以在「**卡券菜单**」查看我已领取的 「**优惠券列表**」，列表目前实现比较简单，直接从数据库读取，后续会增加「**缓存层**」的优化处理，如下：

  
![](images/Fjj3_OjUEeSEWgDtozWNYCXt0UNX.png)

至此，整个「**优惠券**」之前的前端功能就大致搞完了，后续会增加「**订单**」模块的开发。