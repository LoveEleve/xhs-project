从今天之后的一段时间内，华仔会带着大家一起从零开始搭建并研发一套高并发的电商实战项目，这里会涉及到很多互联网大厂开发过程中所使用的核心技术和架构设计模式，希望大家学完之后可以用到自己的简历中。

接下来我们会重点架构设计和开发一下我们高并发电商实战前端 Web 项目的相关功能 。

这是第九十四篇，本篇我们继续进行电商实战项目设计与开发，本篇我们来实操「**Rancher 2.5.16 部署小红书社交+电商业务中间件服务**」。

文章汇总位置：[https://wx.zsxq.com/dweb2/index/columns/51122554151214](https://wx.zsxq.com/dweb2/index/columns/51122554151214)

![](images/Fnl1ueruAt4exbk9U2e5CcbjFHDI.png)

源码授权与获取地址：[https://articles.zsxq.com/id\_1s85grnaae4p.html](https://articles.zsxq.com/id_1s85grnaae4p.html)

![](images/FmkphgNM2yJBSqCq4raWXJA2r2Ah.png)

本章源码地址：[https://gitcode.net/u011359591/huazai-ecshop/-/tree/ecshop-chapter-94](https://gitcode.net/u011359591/huazai-ecshop/-/tree/ecshop-chapter-94)

## **01 前言**

上篇，我们重点进行了「**Rancher 2.5.16 部署小红书社交+电商微服务实战**」， 今天我们重点来进行实操下「**Rancher 2.5.16 部署小红书社交+电商业务中间件实战**」。

上篇部署中，大部分服务在启动的时候都会报错，比如 「**Redis 连不上**」、「**RocketMQ 连不上**」、「**XXL-Job 没启动**」等等。

## **02 Racher 2.5.16 部署业务中间件实战**

> 注意：在互联⽹企业⾥⾯的中间件，运维⼯程师负责搭建的，开发⼈员使⽤即可。部分组件可以选择直接部署：容器、源码编译，并⾮⼀定要某个⽅式，⽐如某些组件需要特定的版本，但是平台存在不兼容或者配置⽅式不⽀持等

这里为了演示都使用 Rancher 来部署，另外这里部署中间件端口可能还需要重新改下：

![](images/FjeQJk2VIxlMJTMxgl0t3VCxSlUC.png)

![](images/FgNRoRsuyBMm7zqBDK5nNtW0oOKV.png)

## **2.1 Racher 2.5.16 部署 Redis 7.0**

### **2.1.1 redis 7.0 配置及注意事项**

跟上篇操作类似，另外如果是云上环境，记得网络安全组开放对应端口。

1、镜像：redis:7.4.3

2、端口：6379:6379

3、数据卷：

/home/redis/data

/data

4、入口命令

redis-server --requirepass 123456

### **2.1.2 redis 7.0 部署**

![](images/FpEMRlDGnCUKYCLJle9oRH9rPd4_.png)

![](images/FmhzdLq9bJrqOaq0YPmu5-W0D1hY.png)

另外 redis 要是想配置密码，该如何操作呢？

其实在 rancher 中设置的，redis 密码不是在「**环境变量**」中配置，⽽是「**命令入口**」中配置，如下：

![](images/FuF_aykkbljgCCHtUHZJt5ZfDZeO.png)

点击启动后，静静的等待一会，发现拉取成功：

![](images/Fpd8iAgKcgXS25kM0_IaTS1XkGZ4.png)

### **2.1.3 redis 7.0 验证及 Nacos 配置修改**

查看日志：

![](images/Fq8RMYizFJlHEDAp0qBRvAzWIasY.png)

测试 redis-cli 操作：

![](images/FrQ-37rOCfwO4j8DjiM2aHzbZTEn.png)

![](images/FtBhD8H6qZqDmOir8Mh1xyMijpzi.png)

另外将 Nacos 中所有的微服务对应的 Redis IP 改为 rancher 注册的 IP：

![](images/Ftr2AP_lzK0o5txVUB_M1Na4DSx9.png)

## **2.2 Racher 2.5.16 部署 MySQL 8.0**

### **2.2.1 MySQL 8.0 配置及注意事项**

生产环境中，这里是需要部署两类 MySQL:

1.  供 Nacos 和 Xxl-Job 使⽤ 名称: midware-mysql 端口 3307:3306。
2.  供业务微服务使⽤，如果有需要可以每个微服务部署⼀个节点。 名称:service-mysql 端⼝: 3306:3306。

这里我们都使用同一个 MySQL 来提供服务。名称：midware-mysql 端⼝: 3306:3306。

镜像：mysql:8.0

环境变量：MYSQL\_ROOT\_PASSWORD=123456

数据卷路径映射，需要添加2个

/home/data/mysql/data

/var/lib/mysql:rw

/etc/localtime

/etc/localtime:ro

> 注意：在创建 Docker 容器时，加上 “-v /etc/localtime:/etc/localtime:ro” 参数让容器使用宿主机的时间，容器时间与宿主机时间同步,其中 :ro 指定该 volume 为只读

### **2.2.2 MySQL 8.0 部署**

同样，新增工作负载：

![](images/FtIyjEpALI3vcTN36oGxpxpLBagE.png)

设置「**环境变量**」、「**主机调度**」配置如下：

![](images/FuPsjUVI4RnxQtSkW-ufFzXaiLGP.png)

设置「**数据卷**」配置，这里需要添加两个数据卷：

1.  数据持久化目录配置。
2.  容器时间需要跟宿主机一致。

![](images/FnLhfxQdBFF6yOLDJQJu_A37BlzN.png)

然后点击启动，静静等待：

![](images/FjVhgWddGe0VA42ZYejXGJd77spz.png)

### **2.2.3 MySQL 8.0 验证及 Nacos 配置、本地分库分表配置修改**

简单测试：

![](images/FhEQGvCOYKF1lz1L_fhyRbjvi98j.png)

修改 nacos 所有微服务及 项目中 shardingsphere mysql 连接配置为 172.20.8.220 ：

![](images/FlOfjkXY7Kz21Eo1vWVVcr4Xm2n1.png)

如果本地代码修改完需要重新通过 jenkins 打包，然后 rancher 进行重新部署才行：

![](images/FnaKXb7hGtX_rEH1TJQtLJoHVn72.png)

### **2.2.4 导入原先的表结构及数据**

通过 mysql 工具连接 rancher 部署的 msyql 并导入相关数据：

![](images/FnIEVTPNSkLFrwuRnCUMPHWVMjWt.png)![](images/Fp8MIEGz-WHgvWC9nQZMXJbRFIs_.png)

## **2.3 Racher 2.5.16 部署 XXL-Job**

之前我们是通过 IDEA 打开源码的方式来启动 XXL-Job 的，这里我们使用 rancher 部署下对应的 XXL-Job 3.1.0。

### **2.3.1 XXL-Job 配置及注意事项**

> http://ip:8099/xxl-job-admin
> 
> admin / 123456

![](images/FrkPOnoirfKz_CaZYBn1SCu6VKSG.png)

镜像：xuxueli/xxl-job-admin:3.1.0

端口：8099:8099

环境变量：

PARAMS=--spring.datasource.url=jdbc:mysql://172.20.8.220:

3306/xxl\_job?Unicode=true&characterEncoding=UTF8&autoReconnect=true&serverTimezone=Asia/Shanghai

\--spring.datasource.username=root

\--spring.datasource.password=123456

\--xxl.job.accessToken=ab3dE5fG7hJ9kL0mN1pQ2rS3tU4vW5xYz

### **2.3.2 XXL-Job 部署**

同样，新增工作负载：

![](images/FkQAH9ABoFLnEO14mL6KcXdimmkc.png)

[https://hub.docker.com/r/xuxueli/xxl-job-admin/tags](https://hub.docker.com/r/xuxueli/xxl-job-admin/tags)

![](images/FoYixZaWdB30tgRQnTNpXttnEadH.png)

然后点击启动，静静等待：

![](images/FiiJ_BHrykK2SotFvTPVoLbpsC3U.png)

## **2.4 Rancher 2.5.16 部署 ElasticSearch 7.9.3**

之前是在物理机上部署，本地我们使用 Rancher 进行部署同版本 ES。关于原生安装请参考：[【电商实战项目第三十九篇】华仔电商实战项目商品中心服务手把手安装 ElasticSearch 7.9.3 版本及分词器](https://articles.zsxq.com/id_wp5i9us9k7d4.html)

镜像位置与版本：

![](images/Fv1r4DM436q9pjxcAOi-GgPwWXO2.png)

### **2.4.1 添加 es 配置映射**

位置如下：

![](images/Fp-iRY_TS4ITWfs6u8cAXwBHRPzg.png)

添加配置映射 es7:

elasticsearch.yml

cluster.name: "docker-cluster"

network.host: 0.0.0.0

xpack.security.enabled: true

xpack.license.self\_generated.type: basic

xpack.security.transport.ssl.enabled: true

\# 这条配置表示开启xpack认证机制

xpack.security.enabled: true

![](images/FiQ1gH4wD3w8oGKpQ1mZQEWRlWj_.png)

### **2.4.3 部署 es 7**

这里我们采用「**导入 YAML**」方式来部署 es7:

![](images/FkpZieJYWirKx5TVJCnao3XpCMJK.png)

![](images/FkxPIvElNoRDjqzjIOONPYxBrSLG.png)

代码已放代码，这里如果你们自己部署的话，需要修改：

1.  172.20.8.220 替换为自己的。
2.  huazai-ecshop-es7 替换为自己的。
3.  elasticsearch:7.9.3 镜像版本需要修改的话替换为自己想要的版本。
4.  /home/es/data，/home/es/plugins 这两个位置需要替换，也可以替换为自己的。

![](images/FqnuXlLb4YkKVScyyuFPI6kVKL96.png)

然后点击导入后就会进行构建，但是这样还不行，我们选择升级：

![](images/Fn5WI1cdiMZbA7avia19sBB2EC9Q.png)

有几个地方需要修改一下：

![](images/Fj_kz8CP1mmH8IYFDTzTsyjWk1DV.png)

### **1、JVM 参数配置修改为 1G**

![](images/FlE4MRlNZEA2ps_-x3qxVJLTGGn7.png)

### **2、配置主机调度**

这里默认为空，选择指定的主机，并到主机下的 /home 给 es ⽂件夹添加 777 权限。

chmod 777 -R /home/es

![](images/FtBVTvTXLOIfp9Adi1XBlnBYlFDB.png)

![](images/FvZyO9yYNo5vcfl_7PJack5keS2E.png)

![](images/FrY6GQ_0YSmSYnZeJHvoA_4hGg-x.png)

![](images/FtoGZ05uyYmlx6cfOnTGu41Wi0R9.png)

![](images/FkrNdSwNPbasegKnengFay1LCDXS.png)

如上操作完成后，点击升级，静静等待，从主机看目录已经映射过来了：

![](images/FthCi4tPiecycbgx_wuFd-ve055J.png)

但是发现会启动失败：

![](images/Fv5ZMaKfIVBfuNWEtbd5wckat5uW.png)

![](images/FmlDz7cmhNZUUcDoXXvYaoHmhwyA.png)

再来重新修改权限，刚开始没有递归创建下面的目录，需要重新赋值下：

![](images/Fj9tc-wp2nRSp3lHY7KOShSUC16L.png)

然后再来升级一下，等待一会：

![](images/Fv0aGKSszk8zV0-gtrVtvPT3j3xv.png)

### **3、启动后配置密码**

此时还不能使用，需要再进行「**配置密码**」：

![](images/FpdNiep11lJqTM3FwTM23jSVYiGT.png)

命令⾏进⼊，输入以下命令：

bin/elasticsearch-setup-passwords interactive

按回⻋输⼊ y，回⻋⼀直输⼊密码 elastic 即可：

![](images/FkG8CnWz81zIWg0sJ8REMqCUn9Tk.png)

验证并访问：[http://172.20.8.220:9200/\_cat/nodes?v=true&pretty](http://172.20.8.220:9200/_cat/nodes?v=true&pretty)

![](images/FsHQzf_XuLuYAvwqB4wFy7MROpNb.png)

![](images/FqSLZ3qbmOy7fZB6xsc0RZ5Timy3.png)

![](images/FkQT6UbGIXVyg8fsoM-Ak6uYHIZD.png)

如果能访问到如上图所示表示已经部署成功了。

## **2.5 Rancher 2.5.16 部署 RocketMQ 5.3.3**

之前是在物理机上部署，本地我们使用 Rancher 进行部署同版本 RocketMQ 最新版。关于原生安装请参考：[【入门实战系列第三篇】RocketMQ 安装入门实战](https://articles.zsxq.com/id_o92x7jv3p0tu.html)

这里我们还是通过导入 YAML 的方式来进行部署，对应的代码文件已经放到项目 rancher 目录下：

![](images/Fh4fl7jCvLjmzEtfTRd-yhWbZNA4.png)

### **2.5.1 部署 rocketmq 5.3.3**

这里我们采用「**导入 YAML**」方式来部署 rocketmq 5.3.3:

![](images/FkpZieJYWirKx5TVJCnao3XpCMJK.png)

![](images/FviTm_alIBgx8VZlrI3_FXHybtiw.png)

然后点击导入，静静等待一会：

![](images/Fn5ZxF_GOjOdUq9Y2Cz5Q_yfwVya.png)

![](images/FuKIo-rmdk28WnqOwUYfg5lQKNnm.png)

![](images/FvASmM5Dn6bq6ngfED-jnF9G3GyB.png)

### **2.5.2 访问 rocketmq 5.3.3**

通过 dashboard 访问如下：

![](images/Fivpsd8L7Iloscyku6QlOQcdBQsW.png)

## **2.6 Rancher 2.5.16 部署新的 etcd**

这里我们还是通过导入 YAML 的方式来进行部署 etcd 供 hotkey 底层使用，对应的代码文件已经放到项目 rancher 目录下：

![](images/Fl4Kg4O1BT-EHCqqn8b-7Q7b3f9d.png)

### **2.6.1 部署 etcd**

这里我们采用「**导入 YAML**」方式来部署 etcd:

![](images/Fjr1nyyP_ToIBz6v_ZBe6ABFkYlE.png)

然后点击导入，静静等待一会：

![](images/Fmv1gHv-iSi66wyMtML9yttRlF1u.png)

### **2.6.2 验证访问 etcd**

这里需要修改下 Nacos 对应的 hotkey 中 etcd 的访问地址：

![](images/FsTxNlyrWL4EW8TKcVsUAzpUW9-s.png)

以「**美食服务**」 为例来测试：

![](images/FqYQjOenQ3zi3NK3JmHtxjhM8eGV.png)

至此，整个业务中间件部署就到此结束了，接下来会更新项目总结及压测模块。

## **2.7 项目微服务接口验证**

最后，我们挨个来测试下整个项目的每个微服务的接口情况，还是通过「**Gateway**」来调度服务进行访问。

### **2.7.1 前端 IP、端口修改**

修改对应 IP:

![](images/FiR2ebTu9th_C8X8eSH8OOzNLTUU.png)

![](images/FhbkYzHLIY9LE5toBdZ08jNElFdx.png)

然后通过 gitbash 进入到 huazai-front 目录，执行 npm run dev 即可启动前端。不会启动的可以点击参考：[【电商实战项目第五十五篇】华仔电商实战正式接入前端 Web 项目](https://articles.zsxq.com/id_nd9vefnbiosk.html)

### **2.7.2 首页服务**

![](images/Fi1o_Af5pZIJYREV6cHeWKWo9Wb2.png)

我们来调用接口生成下对应的数据：

![](images/FgIlIW-igjYLJ0dUd3Hr6hSPLoxh.png)

再来访问发现列表：

![](images/FmsTEpWBwLDBa5WT06QqfkS-Y0HI.png)

我们查看新的 redis 服务，这里接口我没有改动，大家感兴趣可以改为从数据库读取（目前是 mook 数据）：

![](images/FiDE6_uKA_a9mVS6DTUqo8WnxKKi.png)

### **2.7.3 用户服务**

新地址需要重新登录：

![](images/FoT3rxBZmcqfog4kjw8942UXS8m-.png)

登录后查看用户详情：

![](images/Fi4hFSxsNLFlMPJVj4Cfri8kilU4.png)

### **2.7.4 美食服务**

登录后，我们来看下美食服务的相关接口情况，首先需要修改下 RocketMQ 的访问地址， 其他的配置请参考 git 代码：

![](images/Frkv9L7fz0ucOV8wAbfKQvhHyeQC.png)

![](images/FnT3BEm6HmFHGTTRz0YLgDM23ycn.png)

![](images/FuDUoQP82kMecLYAbwFy5NckSvrQ.png)

测试一下 RocketMQ 效果，点击喜欢、收藏发现发送 MQ 失败：

![](images/FkhptzJMkJKSMzFNkBzle3HA0vru.png)

这个错误 [No route info of this topic: food\_counter\_topic](http://No%20route%20info%20of%20this%20topic:%20food_counter_topic) 表示 RocketMQ 找不到指定主题的路由信息。这通常是由于以下原因之一造成的：

### 问题分析

1.  ​主题未创建​：food\_counter\_topic 主题尚未在 RocketMQ 中创建。
2.  ​Broker 未正确注册​：Broker 没有成功向 NameServer 注册。
3.  ​网络分区问题​：NameServer 和 Broker 之间的通信有问题。
4.  ​Broker 配置问题​：Broker 的配置文件不正确。

这里参考下原来部署的 broker 配置：

![](images/FrR17mJy3D9z48xBj03iUtFijYEm.png)

修改下 broker 的部署命令：

\# 启动命令不变如下

sh -c 'mkdir -p /home/rocketmq/store/config && /home/rocketmq/rocketmq-5.3.3/bin/mqbroker -n rocketmq-nameserver.storage.svc.cluster.local:9876'

这里添加数据卷配置：

![](images/Frxm5x2W_tSrRhz8EJlZfljsA7S3.png)

![](images/FrQK62oSYor6EfT-N6WR2nLYkhNX.png)

![](images/Fnd6NVa-cLBAmh3O1Fol5buITtzC.png)

brokerClusterName = DefaultCluster

brokerName = broker-a

brokerId = 0

deleteWhen = 04

fileReservedTime = 48

brokerRole = ASYNC\_MASTER

flushDiskType = ASYNC\_FLUSH

autoCreateTopicEnable=true

timerMaxDelaySec=5184000

![](images/Fr75eL8Ra871YRggTBg7arxhiizI.png)

点击升级完成后再次测试发送 MQ 消息：

![](images/FlsMRjcus0Zci5I7IAmFJuybAIXQ.png)

![](images/FiCfYCe2v_NadRUauajpLlPZDVZI.png)

![](images/FhXZhXWCKIaMH3Jywi_JD9E3k3a9.png)

### **2.7.5 社交服务**

![](images/FgL32Ghrh78OlPYwVyQc8xiDACBW.png)

![](images/Ft8Rlc7SheQjzBh6x-QednAkQwww.png)

### **2.7.6 购物车服务**

![](images/FlEoSmEgzcNSURVcZchnlAP3ty7f.png)

### **2.7.7 优惠券服务**

![](images/FqLQZ8nl3i5pqjzpNR9F_D09cfa1.png)

### **2.7.8 订单服务**

![](images/Fm1IVuP786gV7eddNhhzvKRT_Pts.png)

![](images/FgppBnHIbbfo5A8r_6RX3OQ5oBp0.png)

## **2.8 Rancher 2.5.16 部署 Nacos 最新版**

由于之前部署 Nacos 启动老是有问题，这里重新来部署下：

![](images/Fnic0ZZHpZGeeOrQkd2bEzei8UBz.png)

然后配置环境变量：

![](images/Fjv7Q9IBz5rXvcOhWT7PskJsk3RN.png)

接着配置数据卷，这里挂载 logs、conf、data：

![](images/FqNVC19fF8lfFPf1_0foWosVp8Rr.png)

![](images/FnsZgGcyedBBzAMqa_rZ7Ld_TNqZ.png)

然后启动发现报错，如果不挂载主机目录，直接部署可以成功，如果挂载就会报错，因为主机目录下 conf 不存在对应的配置：

![](images/FjZzuHOeweuk-s8IU521CZbQmyYS.png)

这里从本地 / AI 实战项目最后一篇云部署 copy 一份：

![](images/Fu5ICIjSNihd7Z2H7rnl9-iemQxZ.png)

另外copy一份配置文件：

![](images/Fi1Ybt7CTLKDYGxOwJIV2XlSoIQz.png)

github地址：[https://github.com/alibaba/nacos/releases](https://github.com/alibaba/nacos/releases) 从源码包中复制一份也可以：

![](images/FgmMM7Bm6A7AfhutFVb6sgosXdGg.png)

然后启动发现成功了：

![](images/FrBweSDtHFx6AjuNbOYyMfl3bJbq.png)

![](images/FqY1dawAcM32pO4R_8e9njKR5ENi.png)

此时需要导入数据库表，可以直接使用 github 源码包中 mysql-schema.sql，导入即可。访问并添加相关配置：

![](images/FiLgmPNnlOD3SgI05MJ7jBhMLUSB.png)

启动项目代码，这里只测试其中一个，其他服务都一样：

![](images/FkxvFtwHWpMex_o6hLMY8g84d4PD.png)

![](images/FihK-Yge05Yguxbh5hGo_XRTeYAw.png)

上图中添加了 config:import 后启动会提示成功/失败：

![](images/FpHZPE0DWym9GonrpQWHcDRqxxFw.png)

看到可以正常拉取 Nacos 配置了，关于 Nacos 控制台的配置添加可以直接复制本地部署的 Nacos 控制台配置即可，然后进行相应配置的修改。