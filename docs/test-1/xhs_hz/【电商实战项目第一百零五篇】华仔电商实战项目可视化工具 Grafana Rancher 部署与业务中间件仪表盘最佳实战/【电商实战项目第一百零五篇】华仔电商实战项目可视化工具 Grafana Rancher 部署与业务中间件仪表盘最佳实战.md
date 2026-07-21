从今天之后的一段时间内，华仔会带着大家一起从零开始搭建并研发一套高并发的电商实战项目，这里会涉及到很多互联网大厂开发过程中所使用的核心技术和架构设计模式，希望大家学完之后可以用到自己的简历中。

之前一期还剩几个架构师必备的模块没有更新，接下来会更新「**大厂监控体系**」、「**全链路压测改造**」 这两个模块。

这是第一百零五篇，本篇我们就对「**可视化工具 Grafana 部署与最佳实战**」。

文章汇总位置：[https://wx.zsxq.com/dweb2/index/columns/51122554151214](https://wx.zsxq.com/dweb2/index/columns/51122554151214)

![](images/Fnl1ueruAt4exbk9U2e5CcbjFHDI.png)

源码授权与获取地址：[https://articles.zsxq.com/id\_1s85grnaae4p.html](https://articles.zsxq.com/id_1s85grnaae4p.html)

![](images/FmkphgNM2yJBSqCq4raWXJA2r2Ah.png)

## **01 前言**

上篇带大家「**Prometheus 整合 springboot 微服务监控实战**」：[【电商实战项目第一百零四篇】华仔电商实战项目之 Prometheus 整合 Springboot 微服务监控实战](https://articles.zsxq.com/id_mwrriir8q70u.html)， 今天我们就对「**可视化工具 Grafana 部署与最佳实战**」。

## **02 可视化工具 Grafana 介绍、部署、实战**

## **2.1 可视化工具 Grafana 介绍**

官方地址：[https://grafana.com/](https://grafana.com/)

中文文档地址：[https://grafana.com/zh-cn/grafana/](https://grafana.com/zh-cn/grafana/)

Grafana是一个开源的可视化和监控工具，广泛用于数据分析和系统监控。它可以通过连接多个数据源，实时地展示数据，并允许用户创建交互式的仪表板（Dashboard）。

Grafana支持多种数据源，包括Prometheus、Graphite、InfluxDB、Elasticsearch等，用户可以通过 Grafana 将这些数据源的数据整合在一起，进行统一的可视化展示和分析。

Grafana 提供了丰富的图表类型和插件，用户可以根据自己的需求定制各种图表和仪表盘，以便更直观地了解数据的趋势和变化。此外，Grafana 还支持告警功能，用户可以设置告警规则并及时收到通知，以便及时处理问题。

它主要有以下六大特点：

1.  展示方式：快速灵活的客户端图表，面板插件有许多不同方式的可视化指标和日志，官方库中具有丰富的仪表盘插件，比如热图、折线图、图表等多种展示方式。
2.  数据源：Graphite，InfluxDB，OpenTSDB，Prometheus，Elasticsearch，CloudWatch 和 KairosDB 等。
3.  通知提醒：以可视方式定义最重要指标的警报规则，Grafana将不断计算并发送通知，在数据达到阈值时通过 Slack、PagerDuty 等获得通知。
4.  混合展示：在同一图表中混合使用不同的数据源，可以基于每个查询指定数据源，甚至自定义数据源。
5.  注释：使用来自不同数据源的丰富事件注释图表，将鼠标悬停在事件上会显示完整的事件元数据和标记。
6.  过滤器：Ad-hoc过滤器允许动态创建新的键/值过滤器，这些过滤器会自动应用于使用该数据源的所有查询。

Grafana 常用于以下场景：

1.  系统监控：通过与 Prometheus、InfluxDB ...等数据源结合，监控服务器、容器、网络设备的运行状态。
2.  业务指标监控：结合应用程序的数据源（如 :MySQL、Elasticsearch...等等），监控业务指标，如：用户增长、销售额...等。
3.  日志分析：通过与 Loki 、或 Elasticsearch 集成，实时分析日志数据，定位问题。
4.  云服务监控：通过集成 AWS、Azure、Google Cloud ...等平台的监控数据，管理和优化云资源。

## **2.2 可视化工具 Grafana 部署**

官方 docker 仓库地址：[https://hub.docker.com/r/grafana/grafana](https://hub.docker.com/r/grafana/grafana)

![](images/FjOM58IvsY5_PhIRpLUxQyM2Fy3Q.png)

### **2.2.1 可视化工具 Grafana Rancher 部署**

我们这里使用 Rancher 来部署：

![](images/FhQQgBZc1SR9aMv2J6lnteCiuuPe.png)

创建持久化目录：

cd /home/data

sudo mkdir -p grafana/data

sudo mkdir -p grafana/conf

tree grafana/

grafana/

├── conf

└── data

\# 创建配置文件

cd grafana/conf && touch grafana.ini

\# 授权

sudo chown -R 472:472 grafana/\*

部署成功后，如下：

![](images/FiGJIin2DOU_VmizWIXDZV8Kmaxg.png)

卷名:grafana-data

主机路径:/docker/grafana/data

容器路径:/var/lib/grafana

卷名:grafana-conf

主机路径:/docker/grafana/conf

容器路径:/etc/grafana

![](images/FoLYpLIPgjxkr9KPEVArbaR-qk06.png)

执行启动，静静等待一会：

  
![](images/FkL5SA7B6O1W0khqAAhlmPfquL5q.png)

### **2.2.2 访问可视化工具 Grafana**

网络安全组一定要开通下对应的端口，启动成功后访问地址：[http://localhost:3000/login](http://localhost:3000/login)

用户名：admin 密码：admin，输入之后让修改密码，这里就不修改了，还是使用 admin。

![](images/FpqqGHZNB0t3UBM35vGp_w47Aohq.png)

### **2.2.3 创建数据源**

创建第一个数据源，指定 Prometheus 地址，两种创建方式：

![](images/FgIRPkhyFadBaMTVaPGE2jF-ClCX.png)

![](images/FmYHJXPvwAFXpQWwH1HzD_hmgcsw.png)

进入后，点击 Prometheus：

![](images/Fh5iCPMMQNz6aO75tosiDRuMNOJn.png)

这里只需添加 2 处地方，然后点击保存与测试按钮：

![](images/FqNJG55AftQOKQQAj0ZfLV1VIvN0.png)

![](images/FnXBecpMYhSbpcxTYTor68yv0SSZ.png)

创建完成，从这里查看即可：

![](images/FluTr6qYF6KdEX_RwD2pErc_LVt-.png)

### **2.2.4 Grafana 常用菜单介绍**

### **2.2.4.1 用户和组织**

在公司中会有多个项目组，每个项目都会有对应的监控，因此就可以靠用户和组织来操作。

用户：

1.  Grafana 里面用户有三种角色 admin、editor、viewer。
2.  admin 权限最高，可以执行任何操作，包括创建用户，新增 Datasource、DashBoard。
3.  editor 角色不可以创建用户，不可以新增 Datasource，只可以创建 DashBoard。
4.  viewer 角色仅可以查看 DashBoard。

  
![](images/FjR9AH-qAbSCwVySXMupYPn21t93.png)

创建用户：

![](images/FsOxuMgx9VnQ3EL2oEL2Upaac-3E.png)

![](images/Fp2OPJtov1ipgNjsIifTWuyN9eY1.png)

组织：

1.  每个用户可以拥有多个 Organization，用户登录后可以在不同的 Organization 之间切换。
2.  不同的 Organization 之间完全不一样，包括 datasource，dashboard 等都不一样。
3.  创建一个 Organization 就相当于开了一个全新的视图，所有的 datasource，dashboard 等都要再重新开始创建。

同旧版 UI 不同，这里的组织跟用户是分开的，如下：

![](images/FnzIbwanlIiNM-p75F9w8_tMKWae.png)

![](images/Ft9ClDCO2cL_ooUFwrhn6xuMnPKl.png)

![](images/FqCBhYrV8XcijKyru8EeKbm_2x3o.png)

![](images/FsoD7SUqS9z1YCMtrmdrAb3VKBQS.png)

![](images/FgYBz85gcEqobrMEJqejwQhdt11E.png)

![](images/FsJd1bZsUjwMTQ617SZd1Zvjnw6E.png)

### **2.2.4.2 数据源（DataSource）**

Grafana 支持多种不同的时序数据库数据源，对每种数据源提供不同的查询方法，而且能很好的支持每种数据源的特性。可以将多个数据源的数据合并到一个单独的仪表板上。

前面讲过，我们再来看下：

![](images/FpXhfAVufuSMlZKZeo5eCfLkEb_W.png)

### **2.2.4.3 探索（Explore）**

Grafana 中的 Explore 跟 Prometheus 中的 Explore 是类似的：

![](images/FnU6GI4jB93yl-SfbHeT-6tzoKrg.png)

![](images/Fl-P6dRf4NpBLV5nPG9k-T_CbfRf.png)

![](images/FroqBVPNY3udXX5KGAJOifm3WU2M.png)

![](images/FmjgxGrNrZMhF-3vot9HDEtVpqY7.png)

### **2.2.4.5 仪表盘（dashboard）**

最重要 UI 界面就是仪表盘，通过数据源定义好可视化的数据来源，Dashboard 来组织和管理数据可视化图表。仪表盘可以视为一组一个或多个面板组成的一个集合，来展示各种各样的面板。

在一个 Dashboard 中一个最基本的可视化单元为一个 Panel (面板)，通过 Panel 的 Query Editor(查询编辑器)为每一个 Panel 添加查询的数据源以及数据查询方式，每一个Panel 都是独立的。

下图演示如何创建一个新的 dashboard：

![](images/lj9BlfLppOq5aO6IJLJ6a89B_-dp.gif)

创建完成后，目前还没有数据，怎么才能有数据呢？

![](images/lv5XTEDsAjk4M0bPWIinv5eKWw36.gif)

## **2.3 可视化工具 Grafana 应用市场最佳实战**

Grafana支持很多数据源，但是不同中间件太多，每个 panel 又很多选项。一个个配置对于开发新手和老手都是比较麻烦的，能不能达到复用呢?

### **2.3.1 Grafana 应用市场**

官方地址: [https://grafana.com/grafana/dashboards/](https://grafana.com/grafana/dashboards/)

![](images/Fq2BuN4Fjda7-xcFZ1vbmKc0gq6t.png)

它是 Grafana 社区和其他用户分享的可装载的仪表板和面板集合，当安装应用程序市场模板时，Grafana 会自动安装和配置自定义仪表板面板，并可以自动设置相关数据源。

应用市场模板可以是来自 Grafana仓库、别人的 GitHub仓库、开源项目或个人创建的。Grafana 模板使得共享可装载的仪表板变得容易，从而帮助用户减少了工作量，并促进了最佳设置和最佳配置的使用

### **2.3.2 Grafana 仪表盘案例实战**

利用应用市场 dashboard 模板，里面有多个 panel，通过快速导入达到举一反三的效果。

> 需要注意的是：下面几个仪表盘需要先启动我们的小红书微服务后再来添加哦，否则可能监控不到数据

### **2.3.2.1 JVM 监控仪表盘**

![](images/Fp8a8reoS6O1Et3D50Y6sI0OECY5.png)

然后点击进去：[https://grafana.com/grafana/dashboards/4701-jvm-micrometer/](https://grafana.com/grafana/dashboards/4701-jvm-micrometer/)

![](images/Fr6AZZDCXufqh6y7EXDuc8w8YCQl.png)

然后回到 grafana 的 dashboard 界面，右上角点击 New 会出现如下所示，点击 import:

![](images/FglPm5NnOpdWEPGOnV0XenefZCgr.png)

这里我们使用 json 导入，然后静静的等待一会：

![](images/lkyoBIInFU8NzS4cx2mQnysMG0hf.gif)

![](images/FggYaJtt9QB94ONGbQAMEsXgZ_4g.png)

### **2.3.2.2 MySQL 监控仪表盘**

同理，搜索 mysql：

![](images/Fs-5De2K_US0g5_zmirSVTQUJqPU.png)

然后点击进去：[https://grafana.com/grafana/dashboards/7362-mysql-overview/](https://grafana.com/grafana/dashboards/7362-mysql-overview/)

![](images/Fg5xSixxtX4QlL98MvERKUKpOpYe.png)

然后回到 grafana 的 dashboard 界面，右上角点击 New 会出现如下所示，点击 import:

![](images/FglPm5NnOpdWEPGOnV0XenefZCgr.png)

这里我们使用 json 导入，然后静静的等待一会：

![](images/luHLUCr3-CUUWGzUhZj6e7fCXTyM.gif)

### **2.3.2.3 Redis 监控仪表盘**

同理，搜索 redis：

![](images/FnapoEJUw3VL3tIAO4CE2Lz81td_.png)

然后点击进去：[https://grafana.com/grafana/dashboards/18345-redis-overview/](https://grafana.com/grafana/dashboards/18345-redis-overview/)

![](images/Frdtic0maZTzRbXIRUPdJlLyWgaF.png)

然后回到 grafana 的 dashboard 界面，右上角点击 New 会出现如下所示，点击 import:

![](images/FglPm5NnOpdWEPGOnV0XenefZCgr.png)

这里我们使用 json 导入，然后静静的等待一会：

![](images/ln-DOJRIVtjqKYAu22YPBV8TVXR9.gif)

### **2.3.2.4 ElasticSearch 监控仪表盘**

同理，搜索 elasticsearch：

![](images/FjsWUARO5G9EwF6jpzfOTsmUcABq.png)

然后点击进去：[https://grafana.com/grafana/dashboards/14191-elasticsearch-overview/](https://grafana.com/grafana/dashboards/14191-elasticsearch-overview/)

![](images/Fm1YiKUtt-ch97591E39iS50BuuL.png)

然后回到 grafana 的 dashboard 界面，右上角点击 New 会出现如下所示，点击 import:

![](images/FglPm5NnOpdWEPGOnV0XenefZCgr.png)

这里我们使用 json 导入，然后静静的等待一会：

![](images/lkHqd-PMp-0sXnPt15kCu8KsWbqP.gif)

### **2.3.2.5 RocketMQ 监控仪表盘**

同理，搜索 rocketmq：

![](images/FoXjJrh8KEXOdq7NgA0pKIv2tuvX.png)

然后点击进去：[https://grafana.com/grafana/dashboards/14612-rocketmq/](https://grafana.com/grafana/dashboards/14612-rocketmq/)

![](images/Fs-61zt_Hopt_xT-9xoG5iSCoInX.png)

然后回到 grafana 的 dashboard 界面，右上角点击 New 会出现如下所示，点击 import:

![](images/FglPm5NnOpdWEPGOnV0XenefZCgr.png)

这里我们使用 json 导入，然后静静的等待一会：

![](images/FnBYLZzofIB0v9OmX03HmW-S5gmb.gif)