从今天之后的一段时间内，华仔会带着大家一起从零开始搭建并研发一套高并发的电商实战项目，这里会涉及到很多互联网大厂开发过程中所使用的核心技术和架构设计模式，希望大家学完之后可以用到自己的简历中。

通过整整8个月时间，小红书社交+电商高并发实战项目即将收官，接下来，我会最后一个重要的模块「**小红书社交+电商业务性能压测**」。

这是第九十九篇，上篇中讲到我们会通过 Skywalking 来进行监控，本篇我们就来进行新一代分布式链路追踪 Skywalking 监控实战。

文章汇总位置：[https://wx.zsxq.com/dweb2/index/columns/51122554151214](https://wx.zsxq.com/dweb2/index/columns/51122554151214)

![](images/FqgC3fDHLD480-K5OK5geHxvO40a.png)

源码授权与获取地址：[https://articles.zsxq.com/id\_1s85grnaae4p.html](https://articles.zsxq.com/id_1s85grnaae4p.html)

![](images/FmkFIO0oDlZqGFay4ffQR-OfSOhI.png)

本章源码地址：[https://gitcode.net/u011359591/huazai-ecshop/-/tree/ecshop-chapter-99](https://gitcode.net/u011359591/huazai-ecshop/-/tree/ecshop-chapter-99)

## **01 前言**

上篇针对整个「**小红书社交+电商项目**」， 讲解了大厂如何生成海量请求进行性能压测：

[【电商实战项目第九十八篇】华仔电商实战项目之大厂如何生成海量请求进行性能压测](https://articles.zsxq.com/id_b94nsn0xgf5q.html)

从今天开始，我们就来进行最后一个模块的介绍，在上篇中讲到我们会通过 Skywalking 来进行监控，本篇我们就来进行新一代分布式链路追踪 Skywalking 监控实战。

##   
**02 Skywalking 整体架构及部署**

[【电商实战项目第四十九篇】华仔电商实战项目接入微服务全家桶之应用监控与链路跟踪 SkyWalking](https://articles.zsxq.com/id_mjeiahr5c0o9.html)

在前面，我们通过在物理机上部署 Skywalking 进行了实操，今天我们再通过 Rancher 进行部署，将监控链路进行完善。

## **2.1 Skywalking 整体架构**

SkyWalking 的架构图如下所示：

  
![](images/FkJCGfZsVTGRBvqfa_nogitL3SW2.png)

SkyWalking 分为三个核心部分：

1.  Agent（探针）：Agent 运行在各个服务实例中，负责采集服务实例的 Trace 、Metrics 等数据，然后通过 gRPC 方式上报给 SkyWalking OAP 后端。
2.  OAP：SkyWalking 的后端服务，其主要责任有两个。
3.  一个是负责接收 Agent 上报上来的 Trace、Metrics 等数据，交给 Analysis Core （涉及 SkyWalking OAP 中的多个模块）进行流式分析，最终将分析得到的结果写入持久化存储中。SkyWalking 可以使用 ElasticSearch、H2、MySQL 等作为其持久化存储，一般线上使用 ElasticSearch 集群作为其后端存储。
4.  另一个是负责响应 SkyWalking UI 界面发送来的查询请求，将前面持久化的数据查询出来，组成正确的响应结果返回给 UI 界面进行展示。
5.  UI 界面：SkyWalking 前后端进行分离，该 UI 界面负责将用户的查询操作封装为 GraphQL 请求提交给 OAP 后端触发后续的查询操作，待拿到查询结果之后会在前端负责展示。

需要部署的组件：

1.  ElasticSearch 7.X （前面已部署完成）
2.  Skywalking-OAP-Server
3.  Skywalking UI
4.  Skywalking-Agent（需要在项⽬中引⼊）

## **2.2 Rancher 2.5.16 部署 Skywalking 10.2.0 最新版**

同样本次部署跟之前物理机部署一样， SkyWalking 最新发布的 10.2.0 版本。

这里需要部署两个组件：

1.  Skywalking-OAP-Server。
2.  Skywalking UI。

###   
**2.2.1 Rancher 2.5.16 部署 Skywalking-OAP-Server**

镜像地址：[https://hub.docker.com/r/apache/skywalking-oap-server/tags](https://hub.docker.com/r/apache/skywalking-oap-server/tags)

![](images/Fo2oKovF-54n6kU7cTt54V8aaNiP.png)

> 我们使用最新版：apache/skywalking-oap-server:10.2.0
> 
> 端⼝：12800:12800 11800:11800
> 
> 环境变量：
> 
> TZ=Asia/Shanghai
> 
> SW\_ES\_PASSWORD=elastic
> 
> SW\_ES\_USER=elastic
> 
> SW\_STORAGE=elasticsearch
> 
> SW\_STORAGE\_ES\_CLUSTER\_NODES=172.20.8.220:9200

跟之前一样，需要先新增工作负载：

  
![](images/Foz7JW-cjRX_nZyxfESf6W29i1hl.png)

![](images/ForfnXnMNLlVNjvT25bJgK7lkRTT.png)

![](images/Fvoja0ca6R9H5UFYgfXw7TmsEEVC.png)

点击启动，静静等待一会：

  
![](images/Fl0gavHauMbmSH2N6dw0o21gCzu5.png)

###   
**2.2.2 Rancher 2.5.16 部署 Skywalking-UI**

镜像地址：[https://hub.docker.com/r/apache/skywalking-ui/tags](https://hub.docker.com/r/apache/skywalking-ui/tags)

![](images/FhqTRP2Y7Q_KZq3kLMhn2zu-8WLZ.png)

> 我们使用最新版镜像：apache/skywalking-ui:10.2.0
> 
> 端⼝：8083:8083 (左边是容器端⼝，右边是宿主机端⼝)
> 
> 环境变量（skywalking-oap是上⾯定义的容器服务名称）
> 
> SW\_OAP\_ADDRESS=skywalking-oap:12800
> 
> SW\_OAP\_HEALTH\_CHECK\_ENABLE=true
> 
> SW\_OAP\_HEALTH\_CHECK\_ENDPOINT=v3/health
> 
> SW\_SERVER\_PORT=8083
> 
> TZ=Asia/Shanghai

同理，需要先新增工作负载：

![](images/Foz7JW-cjRX_nZyxfESf6W29i1hl.png)

![](images/FqHE4pZgNxztcP7EE6Fr3en0uLRX.png)

![](images/FrADZN_5mHBQdkRDDd2Ms0l8KKdc.png)

点击启动，静静等待一会：

  
![](images/Fnf5qPzR36hzk5GSidGcnoFlexOb.png)

启动成功后，就可以直接访问了：[http://ip:8083/](http://ip:8083/%20) , 我的地址是：[http://172.20.8.220:8083/](http://172.20.8.220:8083/%20)

![](images/Fn99Ry_ckppGGU7kbI112EJhTQh4.png)

查看 ES 索引：

![](images/FjGjh9XF5ta2uaftN6o3ncbsBu5W.png)

等后续接入服务后，就可以看到具体的仪表盘监控数据了。

### **2.2.3 实战项目整合 Skywalking-Agent**

这里的 agent 需要放到项目中进行打包才行，下载地址：[https://skywalking.apache.org/downloads/](https://skywalking.apache.org/downloads/)

### **2.2.3.1 Skywalking-Agent 下载**

![](images/FmXVEzDmWIQ-flKmw11qNZC7K6aZ.png)

这里 agent 最新版是：[https://www.apache.org/dyn/closer.cgi/skywalking/java-agent/9.4.0/apache-skywalking-java-agent-9.4.0.tgz](https://www.apache.org/dyn/closer.cgi/skywalking/java-agent/9.4.0/apache-skywalking-java-agent-9.4.0.tgz)，也可以直接点击链接进行下载。

### **2.2.3.2 Skywalking-Agent 目录文件介绍**

skywalking-agent ⽬录⽂件的介绍:

![](images/FtNjuRft3XWc1Honu5iybguGMMiA.png)

### **2.2.3.3 Skywalking-Agent 添加到项目**

复制 skywalking-apm 的 agent 目录到项⽬当中，这里我以「**首页服务**」为例，其他服务按照同样步骤操作即可。

  
![](images/FtQ_XK4zlAJMV_cJljgz95EZeSgl.png)

![](images/Ft8XpNKpjOKOPvbIYSyk1GW1Ee3n.png)

注意坑来啦：

1.  .gitignore 默认不提交 \*.jar 相关⽂件，注意 .gitignore 是隐藏⽂件，配置电脑显示隐藏⽂件。
2.  删除掉 \*.jar ，才可以成功把 jar 提交上去。

  
![](images/Fi48n7mqgbsC2rEUPJiR-Kq9o5H3.png)

  
![](images/FlxjuEtN79iM-F_vfcI6nqAmMzVP.png)

### **2.2.3.4 Skywalking-Agent 镜像打包发布**

还记得前面我们搞的「**CI/CD**」吗？

通过编写「**Dockerfile**」，然后 「**Jenkins**」进行镜像打包，最后 「**Rancher**」进行部署。

来看下添加 Skywalking-agent 后的 「**Dockerfile**」如何修改：

  
![](images/FqQ7KgBbH2VI2CAIDyZC025VpxWq.png)

另外需要修改下 [skywalking-agent/config/agent.config](http://skywalking-agent/config/agent.config) 文件的 oap 服务地址：

![](images/FrmtGrWo4IidK8_SH6DBe8EMzYFk.png)

![](images/Fq2jqreULs9NPlGJ_65ktUQvRo1k.png)

然后修改顶层 pom.xml, 只保留 huazai-home 和 huazai-common，提交代码后，通过 「**Jenkins**」进行镜像打包即可：

  
![](images/FtisDqzBldHI881bRWQH0y1LmaP3.png)

点击立即构建，静静等待一会：

![](images/Fg5ioKYc8LmI53daRr7LSGyV5BrR.png)

![](images/FuPiJVAj1WtUcZIVToz92TyDcDUa.png)

![](images/Ft1NFG_ODVTncOm8fQ4AtDc5tTP-.png)

接下来我们来访问下对应的接口，让 skywalking 来收集相关数据：

![](images/Fvs8u-KewXQ-D4FIMO8cGzdKiV5_.png)

![](images/FhnAohYPR36MF73lV18VJ-WOVLWA.png)

![](images/Fvd_nyI5FHmREin1dxgJWRzXjaN-.png)