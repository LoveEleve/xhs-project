从今天之后的一段时间内，华仔会带着大家一起从零开始搭建并研发一套高并发的电商实战项目，这里会涉及到很多互联网大厂开发过程中所使用的核心技术和架构设计模式，希望大家学完之后可以用到自己的简历中。

之前一期还剩几个架构师必备的模块没有更新，接下来会更新「**大厂监控体系**」、「**全链路压测改造**」 这两个模块。

这是第一百零三篇，本篇我们就对监控软件「**Prometheus 进行部署及业务中间件 exporter 组件实战**」。

文章汇总位置：[https://wx.zsxq.com/dweb2/index/columns/51122554151214](https://wx.zsxq.com/dweb2/index/columns/51122554151214)

![](images/Fnl1ueruAt4exbk9U2e5CcbjFHDI.png)

源码授权与获取地址：[https://articles.zsxq.com/id\_1s85grnaae4p.html](https://articles.zsxq.com/id_1s85grnaae4p.html)

![](images/FmkphgNM2yJBSqCq4raWXJA2r2Ah.png)

本篇主要是实战分析，所以就不贴 git 代码分支了。

## **01 前言**

上篇带大家从整体上了解下监控软件「**Prometheus 架构设计**」全貌： [【电商实战项目第一百零二篇】华仔电商实战项目之监控软件 Prometheus 架构设计](https://articles.zsxq.com/id_0a1bjl054a0w.html)， 今天我们就对监控软件「**Prometheus 进行部署业务中间件 exporter 组件实战**」。

##   
**02 Prometheus 部署**

本来打算通过 Rancher 进行部署 Prometheus 的，结果发现 Rancher 自带的版本太老了，另外 docker 安装也很简单，直接 docker run 就行。

[https://hub.docker.com/r/prom/prometheus/tags](https://hub.docker.com/r/prom/prometheus/tags)

![](images/FvfzGR90EUIXRXpCejtX-AX5nCh2.png)

另外如果 Rancher 挂了，整个监控也就跟着挂了，所以这里就使用「**源码方式**」来部署 Prometheus。

## **2.1 Prometheus 源码部署**

我们知道 Prometheus 使用 GO 语言开发的，所以在部署之前需要先安装 GO 环境。

### **2.1.1 Go 环境安装**

官方下载地址：[https://golang.google.cn/dl/](https://golang.google.cn/dl/)

![](images/Fp3lWzGnHftsIcWy9BlL7zMhXJc7.png)

这里我们使用最新版：[https://golang.google.cn/dl/go1.24.4.linux-amd64.tar.gz](https://golang.google.cn/dl/go1.24.4.linux-amd64.tar.gz)

### **2.1.1.1 下载 Go 最新版本**

wget https://golang.google.cn/dl/go1.24.4.linux-amd64.tar.gz

![](images/Frllb-Ek9KmvfioVxyX2WFdhdRTz.png)

### **2.1.1.2 解压到指定目录**

tar -C /usr/local -zxvf go1.24.4.linux-amd64.tar.gz

![](images/FuGr8yNGACtqzuLdvATA1FLDBWjY.png)

### **2.1.1.3 添加环境变量**

\# 1.打开文件

sudo vim /etc/profile

\# 2.添加环境变量

export GOROOT=/usr/local/go

export PATH=$PATH:$GOROOT/bin

\# 3.编译生效

source /etc/profile

![](images/FpsF3u9hzR21-LNT92n5Qfb194nN.png)

### **2.1.1.4 测试版本**

go version

### ![](images/FgWvQTkv_zMBWCOk6Q4kSQyXb_G1.png)

### **2.1.2 Prometheus 源码部署**

这里也部署最新版本，官方下载地址：[https://prometheus.io/download/](https://prometheus.io/download/)

![](images/FkPS3uGdwPmv2C9Szc7zXy5a4ug1.png)

### **2.2.1 下载 Promethues 最新版本**

wget https://github.com/prometheus/prometheus/releases/download/v3.5.0/prometheus-3.5.0.linux-amd64.tar.gz

![](images/Fsppwks61s0AOGLomXdWFLJmzYtm.png)

### **2.2.2 解压 Promethues**

\# 解压

tar xf prometheus-3.5.0.linux-amd64.tar.gz

\# 重命名

mv prometheus-3.5.0.linux-amd64 prometheus

![](images/FrkgQCylYcy1m4IHpPiqPCn7WrTn.png)

### **2.2.3 启动 Promethues**

\# 进入目录启动 prometheus

cd prometheus/  

./prometheus --config.file=./prometheus.yml &

\# 查看是否启动成功，默认端口 9090

lsof -i:9090

![](images/FspnA51oVO0M8AF8C9Wan8QcZ4Ur.png)

### **2.2.4 访问 Promethues**

进入访问图形界面：[http://ip + 9090](http://ip%20+%209090/)，云主机记得开放网络安全组：

![](images/FrQW9016yudxcJnQGYUDwbLPoFzE.png)

我们需要做下时间同步：

\# 安装时间同步工具

sudo apt update && sudo apt install -y ntpdate

\# 手动同步时间（使用阿里云 NTP 服务器）

sudo ntpdate ntp.aliyun.com

\# 验证时间是否同步

date

执行完成后再次刷新浏览器就没有报错了：

![](images/FiozG42A4tY2SRu6Sqwsy_1I8cNg.png)

访问指标数据：[http://ip + 9090/metrics](http://%20http//ip%20+%209090/metrics)

![](images/Fg48xtj9M2JiVPIhsZ1xT4qUw02r.png)

### **2.2.5 Promethues 使用技巧**

Promethues 里面经常需要修改相关配置，可以利用「**动态更新**」。启动时在参数中加入 [\--web.enable-lifecycle](http://--web.enable-lifecycle%20/) （该参数默认关闭，生产环境不建议加）。

\# 启动 &表示需要守护进程方式运行，不然退出终端则进程消失

./prometheus --config.file=./prometheus.yml --web.enable-lifecycle &

\# 动态更新

curl -X POST http://localhost:9090/-/reload

![](images/FvZBvH4bvH3R96TJFeufrmUrK05O.png)

### **2.2.6 Promethues 操作面板介绍**

Promethues 操作面板比较简单，我们来看下：

![](images/FiEWp4NYAk1jJBltML4ETz0mC62T.png)

查看目标服务的健康情况：

![](images/FnHKbv9t9fE2oKrU82h0dgO8cJr3.png)

查看服务发现：

![](images/FvD-kRsr6I7eXAKmM1X7kKADTdvI.png)

查看版本信息：

![](images/FldDKU91udL3OhDjiGMSUqgxTCT-.png)

查看 TSDB 状态：

![](images/FqGuHte7_ZFFFZitPxW8BlVYBy9Q.png)

查看命令行参数：

![](images/FjU0iQg-umJbKBmtQCYvTn_ru1KP.png)

查看配置文件：

![](images/Fj5-BpWL0fz2aS0NTDrSJeTpsbhL.png)

查询指标入口：

![](images/FogXyia4T7NNpdOKPDTtagwkbJSG.png)

查询相关指标及图表展示：

![](images/FiwlKchfL3zoQvFejh3wigtTnwQQ.png)

最后，来说一个比较重要的点，就是时间：

注意，默认 Prometheus 的 dashboard 不是使用本地时间，而是使用 UTC 的时间，可以看下面的切换：

![](images/FplAMmxdKAp2obpQlCL5fdeKA9ny.gif)

### **2.2.7 Promethues 配置文件讲解**

关于 Promethues 配置文件总共分为 4 部分：

1.  全局配置。
2.  告警配置。
3.  规则配置。
4.  抓取配置。

我们分别来看下：

### **2.2.7.1 全局配置**

\# 全局配置，默认，可以被覆盖

global:

scrape\_interval:15s # 全局的抓取间隔（默认15秒）

scrape\_timeout:10s # 抓取超时时间（默认10秒）

evaluation\_interval:15s # 规则评估间隔（默认15秒）

external\_labels: # 外部系统标识标签

monitor: 'prometheus' # 标识监控系统类型

region: 'cn-east-1' # 标识地域

### **2.2.7.2 告警配置**

\# 告警配置

alerting:

alertmanagers: # 告警管理器

\- follow\_redirects:true # 是否启用重定向

enable\_http2:true # 是否启用 HTTP/2 协议

scheme: http # 使用 HTTP 协议

timeout:10s # 告警发送超时时间

api\_version:v2 # 指定 Alertmanager 的 API 版本，此处为v2

static\_configs:# 告诉Prometheus哪些目标是静态的(即不会更改)，如果有多个目标，则可以在targets中指定多个地址。

\- targets:\[\]

![](images/FjdLzzjtcDptcXFeSxN0JEqKloJI.png)

### **2.2.7.3 规则配置**

\# 规则文件配置

rule\_files:

\# 规则文件路径列表（支持通配符）

\- 'rules/\*.rules.yml' # 存放告警规则文件

\- 'recording\_rules.yml' # 存放记录规则文件

### **2.2.7.4 抓取配置**

此处第一个抓取任务是 Prometheus 的任务，每隔 15 秒抓取一次 localhost:9090 上的 metrics 路径，超时时间为 10 秒。

\# 抓取配置

scrape configs:

\- job\_name:prometheus # 任务名称

honor\_timestamps:true # 指标的时间戳应该由服务器提供，而不是客户端在发送指标时提供的时间戳

scrape\_interval:15s # 抓取任务的时间间隔，即每15秒抓取一次。

scrape\_timeout:10s # 抓取任务的超时时间，单位为秒，即每个目标最多等待10秒钟

metrics\_path:/metrics # 抓取指标的路径

scheme:http # 指定抓取时使用的协议，默认为http

follow\_redirects:true # 是否启用重定向。在此处启用

enable\_http2:true # 是否启用HTTP2

static\_configs:

\- targets:

\- localhost:9090 # 目标配置，告诉 prometheus 哪些目标需要抓取，如果有多个目标，则可以在targets 中指定多个地址

\# 监控Node Exporter（服务器节点）

\- job\_name: 'node-exporter'

scrape\_interval: 30s # 降低抓取频率

static\_configs:

\- targets:

\- '192.168.1.10:9100'

\- '192.168.1.11:9100'

\- '192.168.1.12:9100'

\# 监控Kubernetes集群

\- job\_name: 'kubernetes-pods'

kubernetes\_sd\_configs: # Kubernetes服务发现

\- role: pod # 发现Pod资源

relabel\_configs: # 重新标记配置

\- source\_labels: \[\_\_meta\_kubernetes\_pod\_annotation\_prometheus\_io\_scrape\]

action: keep

regex: true

![](images/FlMO8O_1_6OXwsWCQjr07DF10-4Z.png)

我们来看下其自带的配置文件内容：

![](images/FuVcaVhb65U5bTve38UPjN67g9NX.png)

## **03 Prometheus 常用监控组件配置实战**

这里我们重点来看三个监控组件：

1.  Node Exporter。
2.  MySQL Exporter。
3.  Redis Exporter。
4.  ElasticSearch Exporter
5.  RocketMQ Exporter

## **3.1 什么是 Prometheus 的 Exporter**

Exporter 是 Prometheus 监控体系中的关键组件，它是一个专门用于将各种系统、应用程序或服务的监控数据转换为 Prometheus 可识别格式的程序。

它也是 Prometheus 监控生态的桥梁，使得各种系统能够无缝接入 Prometheus 监控体系，通过标准化格式暴露监控指标，同时保持系统的解耦和灵活性。

这种设计是 Prometheus 能够成为云原生时代主流监控系统的重要原因之一。

### **3.1.1 Exporter 核心功能**

1.  数据采集
2.  从目标系统（如操作系统、数据库、中间件等）收集原始监控数据。
3.  支持多种数据源：HTTP 端点、JMX、SNMP、自定义协议等。
4.  格式转换
5.  将原始数据转换为 Prometheus 支持的标准指标格式。
6.  输出格式为纯文本的 metrics 格式（OpenMetrics 格式）。
7.  数据暴露
8.  通过 HTTP 服务暴露 /metrics 端点。
9.  默认端口范围：9100-9200（如 node\_exporter 使用 9100）。
10.  被动服务
11.  不主动推送数据给 Prometheus。
12.  等待 Prometheus Server 定期来拉取（scrape） 数据。

###   
**3.1.2 Exporter 工作流程**

1.  在目标系统上部署 Exporter。
2.  Exporter 自动收集目标系统的监控数据。
3.  Exporter 将数据转换为 Prometheus 格式。
4.  Exporter 通过 HTTP 服务暴露 /metrics 端点。
5.  Prometheus Server 定期访问该端点抓取数据。
6.  Prometheus 存储和处理抓取到的指标数据。

### **3.1.3 Exporter 总结**

Exporter 是 Prometheus 指标数据收集组件，负责从目标 Jobs 收集数据。并把收集到的数据转换为 Prometheus 支持的时序数据格式，只负责收集并不向 Server 端发送数据，而是等待 Prometheus Server 主动抓取。

![](images/FiuIene66AWW_Ht4w_jeoZZNziK3.png)

Prometheus 社区以及其他团队开发了大量的 Exporter，覆盖了许多不同类型的系统和服务，主要有：

1.  Node Exporter
2.  MySQL Exporter
3.  Redis Exporter
4.  MongoDB Exporter
5.  Nginx Exporter
6.  ElasticSearch Exporter
7.  JMX Exporter
8.  RocketMQ Exporter
9.  。。。

Exporter 使用方式：

1.  在主机上安装了一个 Exporter 程序，该程序对外暴露了一个用于获取当前监控样本数据的 HTTP 访问地址。
2.  Prometheus 通过轮询的方式定时从这些 Target 中获取监控数据样本，并且存储在数据库当中

所有的 Exporter 程序都需要按照 Prometheus 的规范，返回监控的样本数据。比如下面我们要介绍的 Node Exporter 为例，当访问 /metrics 地址时会返回内容和本身 Prometheus 协议保持一致即可。

主要由三个部分组成，Prometheus 会对 Exporter 响应的内容逐行解析：

1.  样本的一般注释信息(HELP)
2.  样本的类型注释信息(TYPE)
3.  样本值

  
![](images/Fsk2HOTmEBUmsCvx_euR8EXsM8Jo.png)

## **3.2 Node Exporter 部署实战**

node\_exporter 主要用来采集类 Unix 内核的硬件及系统指标，包括 CPU、内存和磁盘。

同 Prometheus 官网一致：[https://prometheus.io/download/](https://prometheus.io/download/)

![](images/Fppe6ETRt9lIT2soZbjQdyZJAruY.png)

也可以从 github 下载：[https://github.com/prometheus?q=exporter&type=all&language=&sort=](https://github.com/prometheus?q=exporter&type=all&language=&sort=)

![](images/Fkl7Fm2QKVHSFWtioRZDB6E9s9X8.png)

### **3.2.1 下载 node\_exporter 最新版本**

wget https://github.com/prometheus/node\_exporter/releases/download/v1.9.1/node\_exporter-1.9.1.linux-amd64.tar.gz

![](images/FuBsiES2SaEUWDGAQyq_9-ldP66I.png)

### **3.2.2 解压 node\_exporter**

\# 解压

tar xf node\_exporter-1.9.1.linux-amd64.tar.gz

\# 重命名

mv node\_exporter-1.9.1.linux-amd64 node\_exporter

![](images/FlO4Cu2A0u1T_LazZyG0T9sFJs5H.png)

### **3.2.3 启动 node\_exporter**

\# 进入目录，后台启动

cd node\_exporter/

nohup ./node\_exporter &

\# 查看是否启动成功，默认端口 9100

lsof -i:9100

![](images/FuiX4xQpCrOUWAPwHPnU-eBI2rvc.png)

![](images/FhK6ifOLk-4EzsjGkY3G9Ipx4CIs.png)

### **3.2.4 访问 node\_exporter**

进入访问图形界面：[http://ip + 9100](http://ip%20+%209100/)，云主机记得开放网络安全组：

![](images/FqoLSd4tVRW-sEYaS2ZcpqH6x_FP.png)

我们主要关注 /metrics 指标监控相关数据：[http://ip + 9100](http://ip%20+%209100/metrics)[/metrics](http://ip%20+%209100/metrics)

大概会采集 610 个指标，包括：

1.  go 前缀的指标：这是 node-exporter 进程本身的一些指标，比如 gc 耗时、内存使用等。
2.  node 前缀的指标：机器的一些常规指标，比如 CPU、内存、硬盘、网络、IO 等。
3.  promhttp 前缀的指标：node-exporter 的 http 服务的一些指标，比如请求次数。

机器层面的监控分为两部分，带内监控和带外监控。带内监控就是通过带内网络来监控，主要是以在 OS 里部署 Agent 的方式，来获取 OS 的 CPU、内存、磁盘、I/O、网络、进程等相关监控指标。随着云时代的到来，普通运维研发人员主要关注带内监控即可，IDC 运维人员才会关注带外监控。

所谓带外监控走的是带外网络，通常和业务网络不互通，通过 IPMI、SNMP 等协议获取硬件健康状况。我们这里讲解的都是带内监控。

cpu 相关指标监控数据：

![](images/Fp44rU3RspvAjiq8a7RWE50DieEZ.png)

CPU 相关的指标，最核心的就是使用率。大型互联网公司为了应对突发流量，CPU 的平均利用率一般就是 30% 左右。如果平时 CPU 利用率总是超过 60%，就比较危险了。

![](images/Ftd-pDbNGfYAUw75T9EYeup4NOlk.png)

磁盘相关指标监控数据：

![](images/Fh3DHbQHajjYF9pwaP8Tyv8OUbnv.png)

内存相关指标监控数据：

![](images/FpaykKxmv1hpj1wJtpn22qLWqzl_.png)

内存相关的指标，最核心的是可用内存，即 node\_memory\_MemAvailable\_bytes。

根据下面公式求内存可用率：

100 \* (1 - (node\_memory\_MemAvailable\_bytes / node\_memory\_MemTotal\_bytes))

这个值如果小于 30% 就可以发个低级别告警出来了，然后着手扩容。

### **3.2.5 node\_exporter 整合 Prometheus**

很简单，只需要在 Prometheus 服务器中添加被监控机器的配置即可，我们需要编辑 prometheus/prometheus.yml 文件，直接复制 prometheus 自带的 job 并修改如下：

![](images/FieowCLj7yxpnmF28nIn9kYF9SCo.png)

那么这样保存完就生效了吗，如何查看是否生效？如下图：

![](images/FtJcPS0QFrRHeNQZ-k1enh4vq-CQ.png)

如何生效呢？其实在前面 **Promethues 使用技巧** 已经讲过了：

\# 动态更新

curl -X POST http://localhost:9090/-/reload

![](images/FtDqQwwhd47JZgPXHCXL9JCaQBZ4.png)

再次刷新访问 Web UI 界面的 target 和 configuration 是否有对应的配置信息，从下图可以看出此时就已经加载了最新的配置信息：

![](images/FvDX-grk9PTE0z8PfW1nR8FxqJl6.png)

再来看下 target 界面：

![](images/FmeUKPuSn15XjKV-xdrAlLgSTTmM.png)

### **3.2.6 常规 exporter 整合 Prometheus 配置总结**

社区提供了很多个 exporter，这里我们来总结下操作步骤：

1.  在对应的机器安装 exporter。
2.  启动 exporter 并监听对应的程序。
3.  访问对应的 exporter 的 metric 路径，看是否返回数据。
4.  prometheus.yml 配置新的 job 任务。
5.  访问 Prometheus UI 查看 target 和 configuration 是否有数据。

## **3.3 MySQL Exporter 部署实战**

前面是通过源码的方式进行 exporter 部署的，但是之前我们通过 Rancher 部署了 MySQL 等业务中间件，这里我们通过 rancher 的方式来部署下 mysqld\_exporter。

### **3.3.1 Rancher 部署 mysqld\_exporter 最新版本**

官方 docker 仓库地址：[https://hub.docker.com/r/prom/mysqld-exporter/tags](https://hub.docker.com/r/prom/mysqld-exporter/tags)

![](images/FvlCJlVLHXtPrfJFJ0phM7jjkvUW.png)

如果采用 docker 命令如下：

sudo docker run -d --privileged --restart=unless-stopped --name mysqld\_exporter -p 9104:9104 -e DATA\_SOURCE\_NAME="root:123456@(172.20.8.220:30036)/mysql" prom/mysqld-exporter:latest

我们这里使用 Rancher 来部署，在部署之前需要先创建一个 configMap 来指定 my.cnf 配置信息：

![](images/FgnGnw2mOZ2VXv1SxW2cGCMY7IUl.png)

![](images/Fh2HT-w9KWkv2nMrmOm9uHRKQL45.png)

my.cnf

\[client\]

host=172.20.8.220

port=30036

user=root

password=123456

如果不指定否则报错：

time=2025-08-07T02:12:29.147Z level=INFO source\=mysqld\_exporter.go:239 msg="Starting mysqld\_exporter" version="(version=0.17.2, branch=HEAD, revision=e84f4f22f8a11089d5f04ff9bfdc5fc042605773)"

time=2025-08-07T02:12:29.147Z level=INFO source\=mysqld\_exporter.go:240 msg="Build context" build\_context="(go=go1.23.6, platform=linux/amd64, user=root@18b69b4b0fea, date=20250226-07:16:19, tags=unknown)"

time=2025-08-07T02:12:29.148Z level=ERROR source\=config.go:141 msg="failed to validate config" section=client err="no user specified in section or parent"

time=2025-08-07T02:12:29.148Z level=INFO source\=mysqld\_exporter.go:244 msg="Error parsing host config" file=.my.cnf err="no configuration found"

接着我们来部署 mysqld-exporter：

![](images/FpEU2sl-n2JnsnySljeeqxvYD1Ig.png)

指定环境变量：

DATA\_SOURCE\_NAME="root:123456@(172.20.8.220:30036)/mysql"

![](images/FkShIHTqUbD5PIT54ukpbpXZQNSR.png)

添加数据卷：

卷名：mysqld-exporter-conf

配置映射名：选择前面已经添加好的 configMap

容器路径和子路径：.my.cnf

![](images/FidwcUU7ebKhPCvsnbEAODiNEj05.png)

执行启动，静静等待一会：

![](images/FqLUQ9DH-2R5vf5vM5HPwq9tQXg0.png)

![](images/FpygJT0y-w4Mt1N9JBvOy2KJy_JH.png)

### **3.3.2 访问 mysqld\_exporter**

启动成功后访问：[http://ip+9104](http://ip+9104/)

![](images/Fsg30f2wRuhldiH2b1_slijOt6OH.png)

我们主要关注 /metrics 指标监控相关数据：[http://ip + 9104/metrics](http://ip%20+%209104/metrics)

![](images/FmE-cuLeRxm0QmazdfKP-eaHgMdX.png)

### **3.3.4 mysqld\_exporter 整合 Prometheus**

很简单，只需要在 Prometheus 服务器中添加被监控机器的配置即可，我们需要编辑 prometheus/prometheus.yml 文件，直接复制 prometheus 自带的 job 并修改如下：

  
![](images/FnVyyylr2UDsBooAX8H9Ob8KTHhW.png)

此时我们还需要动态刷新下才能生效：

\# 动态更新

curl -X POST http://localhost:9090/-/reload

![](images/FpDHojUYzXljEPK5w4g_h1hwLeMa.png)

刷新访问 Web UI 界面的 target 和 configuration 是否有对应的配置信息，从下图可以看出此时就已经加载了最新的配置信息：

  
![](images/Fko5aiBcQ4dTkh7Jobo6m2BxO-ZA.png)

再来看下 target 界面：

  
![](images/FscyYfTJuaaEwy2IWJ5b0XMigbwi.png)

## **3.4 Redis Exporter 部署实战**

前面是通过源码的方式进行 exporter 部署的，但是之前我们通过 Rancher 部署了 Redis 等业务中间件，这里我们通过 rancher 的方式来部署下 redis\_exporter。

Redis Exporter 是一个用于将 Redis 服务器的运行数据暴露为 Prometheus 格式指标的工具。它能够收集 Redis 的各种运行指标，例如连接数、内存使用情况、命令执行频率等，并将这些数据转换为 Prometheus 可以识别的格式。

Redis Exporter 的优势在于它简化了 Redis 监控的复杂性，让开发者和运维人员能够方便地使用 Prometheus 进行监控和告警。它支持最新版本的 Redis 功能，确保了监控数据的实时性和准确性。

### **3.4.1 Rancher 部署 redis\_exporter 最新版本**

官方地址：[https://github.com/oliver006/redis\_exporter](https://github.com/oliver006/redis_exporter)，这个没有维护在 Prometheus 下面。

docker hub 地址：[https://hub.docker.com/r/oliver006/redis\_exporter/tags](https://hub.docker.com/r/oliver006/redis_exporter/tags)

![](images/FqZHSQkOB5eZDlnh-kQ203-HO_0l.png)

我们这里使用 Rancher 来部署，在部署之前需要先创建一个 configMap 来指定 config.yaml 配置信息：

  
![](images/FqHyqMKITRUFyYdRs4MndGkIXBUz.png)

config.yaml

databases:

\- name: "main\_db"

redis:

addr: "localhost:6379"

password: "123456"

maxmemory\_policy: "noeviction"

set\_max\_intset\_entries: 512

list\_max\_listpack\_size: 1024

stream\_max\_len: 10000

zset\_max\_ziplist\_entries: 128

zset\_max\_ziplist\_value: 64

hll\_max\_hashsize: 512

master: true

tags:

\- "app:your\_app" # 这块暂时没搞明白要做啥就没改

\- "env:your\_env" \# 这块暂时没搞明白要做啥就没改

接着我们来部署 redis-exporter：

  
![](images/FrOFmAsk7O2FxNuEQSowjofAtmAo.png)

之前通过 configMap 添加发现连接不上 Redis，导致抓取有问题，改为通过添加命令行完成：

\-redis.addr redis://172.20.8.220:6379 -redis.password 123456 --web.listen-address=:9121

![](images/Frnyt4aYJ5YaermApSYZx_54rwfq.png)

执行启动，静静等待一会：

  
![](images/Fmyy8bw4ksr56HItFBkA6HItw9Uo.png)

![](images/FiI7K7f2S3UPYatOz2Q0dOf8q61S.png)

### **3.4.2 访问 redis\_exporter**

启动成功后访问：[http://ip+9121](http://ip+9121/)

![](images/Fri4URq2csz3PFcEdWDtk_ih7rlx.png)

我们主要关注 /metrics 指标监控相关数据：[http://ip + 9121/metrics](http://ip%20+%209121/metrics)

![](images/FuA0dy4cx251xMo2uCPaZkqX3jxe.png)

上图是之前通过 configMap 方式配置后的抓取结果，有问题导致 Grafana Redis 监控没数据。下图通过命令行参数启动的：

![](images/FizFnie-aP1Q17v_oUgunYDLWsIL.png)

### **3.4.3 redis\_exporter 整合 Prometheus**

很简单，只需要在 Prometheus 服务器中添加被监控机器的配置即可，我们需要编辑 prometheus/prometheus.yml 文件，直接复制 prometheus 自带的 job 并修改如下：

  
![](images/FvNbpZWdmFPTMVRW_ECVBd7a4b-m.png)

此时我们还需要动态刷新下才能生效：

\# 动态更新

curl -X POST http://localhost:9090/-/reload

![](images/Fu4Up9f6ik7C1dBgXQ-ulilSz2a0.png)

刷新访问 Web UI 界面的 target 和 configuration 是否有对应的配置信息，从下图可以看出此时就已经加载了最新的配置信息：

  
![](images/FknUfZEW_tpVUPjoaApKTs2RkFxw.png)

再来看下 target 界面：

![](images/FvwwoKbVbPMc_Xn3ZX1vFiI6CgbG.png)

## **3.5 ElasticSearh Exporter 部署实战**

前面是通过源码的方式进行 exporter 部署的，但是之前我们通过 Rancher 部署了 ElasticSearch 等业务中间件，这里我们通过 rancher 的方式来部署下 elasticsearch\_exporter。

elasticsearch\_exporter 指的是一个专门设计用来从 Elasticsearch 集群中导出统计信息的工具，以便这些数据可以被 Prometheus 这种监控和警报工具所抓取和使用。

Elasticsearch 是一个基于 Lucene 的搜索引擎，它提供了一个分布式多用户能力的全文搜索引擎，基于 RESTful web 接口。它通常用于实现全文搜索功能，但它缺乏原生的指标暴露功能，这限制了它与 Prometheus 这类系统监控工具的直接集成。这就是为何需要一个导出器，比如 elasticsearch\_exporter，来弥补这一缺口。

elasticsearch\_exporter 与 ES 集群是分开独立，不需要对原有的 ES 集群做任何修改，不需要重启，只要能访问 es集群即可。

### **3.5.1 Rancher 部署 elasticsearch\_exporter 最新版本**

docker hub 地址：[https://hub.docker.com/r/prom/elasticsearch-exporter/tags](https://hub.docker.com/r/prom/elasticsearch-exporter/tags)

![](images/FryELRBOHkjsIf-BgwdDRtYHnr4-.png)

我们这里使用 Rancher 来部署：

  
![](images/Fjm3CDLvAvM58ZXb_efxW_Z9zbnW.png)

![](images/FnHdgHjk5cSRljCPxPiFWatL7X1a.png)

\--es.all --es.indices --es.node=huazai-ecshop-es7-79df56d895-f9cm7 --es.indices\_settings --es.shards --es.timeout=5s --web.listen-address :9555 --web.telemetry-path /metrics --es.ssl-skip-verify --es.clusterinfo.interval=5m --es.uri http://elastic:elastic@172.20.8.220:9200

\## 参数说明：

\--es.uri 　　　　默认http://localhost:9200，连接到的Elasticsearch节点的地址（主机和端口）。 这可以是本地节点（例如localhost：9200），也可以是远程Elasticsearch服务器的地址

\--es.all 默认flase，如果为true，则查询群集中所有节点的统计信息，而不仅仅是查询我们连接到的节点。

\--es.cluster\_settings 默认flase，如果为true，请在统计信息中查询集群设置 新版不支持了

\--es.indices 默认flase，如果为true，则查询统计信息以获取集群中的所有索引。

\--es.indices\_settings 默认flase，如果为true，则查询集群中所有索引的设置统计信息。

\--es.shards 默认flase，如果为true，则查询集群中所有索引的统计信息，包括分片级统计信息（意味着es.indices = true）。

\--es.snapshots 默认flase，如果为true，则查询集群快照的统计信息。新版不支持了

执行启动，静静等待一会：

  
![](images/FkuzyHeW7M4l4Ym-Y3eqGVGunyeU.png)

![](images/FlpK21-2ELvYz82DcHlS6P4wfYEX.png)

1.  \--web.listen-address ":9555"，指定监听的端口，不与现有端口冲突的前提下，可随便设置；还可以通过设置不用的监听端口，来启动多个实例，适用于监控不同的elasticsearch集群的场景。
2.  \--es.uri 此参数后若衔接的是https协议，则使用上面代码中的格式；若是http协议，则用：[http://IP](http://ip/):端口 ，即可。需要注意的是，这里的IP地址是指elasticsearch集群中某一台服务器的ip地址。
3.  该启动方法的优点在于，可启动多个不同端口的进程。

### **3.5.2 访问 elasticsearch\_exporter**

启动成功后访问：[http://ip+9555](http://ip+9555/)

![](images/Fux4P-Ric-qFDNry8dLb4zE7x4mQ.png)

我们主要关注 /metrics 指标监控相关数据：[http://ip + 9555/metrics](http://ip%20+%209555/metrics)

![](images/Fom7gXMPM-7mbxkpi63sMoe-XdS8.png)

### **3.5.3 elasticsearch\_exporter 整合 Prometheus**

很简单，只需要在 Prometheus 服务器中添加被监控机器的配置即可，我们需要编辑 prometheus/prometheus.yml 文件，直接复制 prometheus 自带的 job 并修改如下：

![](images/FjNxaRuTEAMjr_mJcj_ghWb7EZGB.png)

此时我们还需要动态刷新下才能生效：

\# 动态更新

curl -X POST http://localhost:9090/-/reload

![](images/FsF8O9qSHG5dVUGrMDeSanzmZkP6.png)

刷新访问 Web UI 界面的 target 和 configuration 是否有对应的配置信息，从下图可以看出此时就已经加载了最新的配置信息：

  
![](images/FqFzRP48xNag3nQusbivxu3MefIV.png)

再来看下 target 界面：

  
![](images/FvRiuBCMM3WCKWnb5Jb6dGW6FEdP.png)

### **3.5.4 elasticsearch\_exporter 核心指标**

### **3.5.4.1 集群健康和节点可用性**

通过 [cluster health](https://yq.aliyun.com/go/articleRenderRedirect?spm=a2c4e.11153940.0.0.42fee19f13Xswl&url=https%3A%2F%2Fwww.elastic.co%2Fguide%2Fen%2Felasticsearch%2Freference%2F6.2%2Fcluster-health.html) API 可以获取集群的健康状况，可以把集群的健康状态当做是集群平稳运行的重要信号，一旦状态发生变化则需要引起重视；API 返回的一些重要参数指标及对应的 prometheus 监控项如下：

![](images/FlY6xMjXncb9ACauh0labXUkXUZs.png)

### **3.5.4.2 主机级别的系统和网络指标**

![](images/Fj9nnbZ0a98H-Ghhgs0gTRY39R6-.png)

如果CPU使用率持续增长，通常是由于大量的搜索或索引工作造成的负载。可能需要添加更多的节点来重新分配负载。

文件描述符用于节点间的通信、客户端连接和文件操作。如果打开的文件描述符达到系统的限制（一般Linux运行每个进程有1024个文件描述符，生产环境建议调大65535），新的连接和文件操作将不可用，直到有旧的被关闭。

如果ES集群是写负载型，建议使用SSD盘，需要重点关注磁盘空间使用情况。当 segment 被创建、查询和合并时，Elasticsearch 会进行大量的磁盘读写操作。

节点之间的通信是衡量群集是否平衡的关键指标之一，可以通过发送和接收的字节速率，来查看集群的网络正在接收多少流量。

### **3.5.4.3 JVM 内存和回收垃圾**

![](images/FiaWjYvQLQ5Iw7EKuzJH5SFtYpgL.png)

主要关注JVM Heap 占用的内存以及JVM GC 所占的时间比例，定位是否有 GC 问题。Elasticsearch依靠垃圾回收来释放堆栈内存，默认当JVM堆栈使用率达到75%的时候启动垃圾回收，添加堆栈设置告警可以判断当前垃圾回收的速度是否比产生速度快，若不能满足需求，可以调整堆栈大小或者增加节点。

### **3.5.4.4 搜索和索引性能**

### **搜索请求**

![](images/Fq0-tGAqWvwJt-kbV7io_rGE77mI.png)

### **索引请求**

![](images/Fmx4glO5zDCKiPrWQ-mUJm82BFD1.png)

将时间和操作数画在同一张图上，左边 y 轴显示时间，右边 y 轴显示对应操作计数，ops/time 查看平均操作耗时判断性能是否异常。

通过计算获取平均索引延迟，如果延迟不断增大，可能是一次性 bulk 了太多的文档。Elasticsearch 通过 flush 操作将数据持久化到磁盘，如果 flush 延迟不断增大，可能是磁盘 I/O 能力不足，如果持续下去最终将导致无法索引数据。

### **3.5.4.5 资源饱和度**

![](images/FlrgEhPRj9nkddqGwwLhNgYX1GAK.png)

通过采集以上指标配置视图，Elasticsearch节点使用线程池来管理线程对内存和CPU使用。可以通过请求队列和请求被拒绝的情况，来确定节点是否够用。

每个Elasticsearch节点都维护着很多类型的线程池。一般来讲，最重要的几个线程池是搜索（search），索引（index），合并（merger）和批处理（bulk）。

每个线程池队列的大小代表着当前节点有多少请求正在等待服务。一旦线程池达到最大队列大小（不同类型的线程池的默认值不一样），后面的请求都会被线程池拒绝。

## **3.6 RocketMQ Exporter 部署实战**

前面是通过源码的方式进行 exporter 部署的，但是之前我们通过 Rancher 部署了 RocketMQ 等业务中间件，这里我们通过 rancher 的方式来部署下 rocketmq\_exporter。

  
Rocketmq-exporter 是用于监控 RocketMQ broker 端和客户端所有相关指标的系统，通过 mqAdmin 从 broker 端获取指标值后封装，以便这些数据可以被 Prometheus 这种监控和警报工具所抓取和使用。

当前 RocketMQ Exporter 已被 Prometheus 官方收录，其地址为：[https://github.com/apache/rocketmq-exporter](https://github.com/apache/rocketmq-exporter)。

![](images/FvWo9HozYIROBj7FLfja56Dkw8vy.png)

当前在 Exporter 当中，实现原理如下图所示：

  
![](images/Fgo57rSUu3oqmxCXWYvA56fVoMSy.png)

整个系统基于 spring boot 框架来实现。由于 MQ 内部本身提供了比较全面的数据统计信息，所以对于 Exporter 而言，只需要将 MQ 集群提供的统计信息取出然后进行加工而已。

所以 RocketMQ-Exporter 的基本逻辑是内部启动多个定时任务周期性的从 MQ 集群拉取数据，然后将数据规范化后通过端点暴露给 Prometheus 即可。其中主要包含如下主要的三个功能部分：

1.  MQAdminExt 模块通过封装 MQ 系统客户端提供的接口来获取 MQ 集群内部的统计信息。
2.  MetricService 负责将 MQ 集群返回的结果数据进行加工，使其符合 Prometheus 要求的格式化数据。
3.  Collect 模块负责存储规范化后的数据，最后当 Prometheus 定时从 Exporter 拉取数据的时候，Exporter 就将 Collector 收集的数据通过 HTTP 的形式在 /metrics 端点进行暴露。

###   
**3.6.1 Rancher 部署 rocketmq\_exporter 最新版本**

在部署 rocketmq\_exporter 之前需要确保启动 NameServer 和 Broker 已经正确启动。

  
docker hub 地址：[https://hub.docker.com/r/apache/rocketmq-exporter/tags](https://hub.docker.com/r/apache/rocketmq-exporter/tags)

![](images/FmXj2ggByn06J2XFkkKTmhknU9_k.png)

我们这里使用 Rancher 来部署：

![](images/FtJh_Yh4tgdtoqepfQHBBm-JS3-b.png)

\--rocketmq.config.namesrvAddr=rocketmq-nameserver.storage.svc.cluster.local:9876 --rocketmq.config.webTelemetryPath=/metrics --server.port=5557

![](images/FgcumMR31vEvteTXlsnlOttb5Tuo.png)

执行启动，静静等待一会：

  
![](images/FuSsbTqvPiGNY0owr5ULWrInTps2.png)

可以看到 running 了，查看日志发现有一些问题，如下：

![](images/FmbOw28P5qT8zoHn9RunMVtHCWVh.png)

不知道那里的问题，过了会就好了：

![](images/FhV15zvsNFiyw-oDnjazkJENPnIi.png)

我们主要关注 /metrics 指标监控相关数据：[http://ip + 5557/metrics](http://ip%20+%205557/metrics)

![](images/FkdePg-mzlMploFE2MFgDvcHxGq5.png)

### **3.6.2 rocketmq\_exporter 整合 Prometheus**

很简单，只需要在 Prometheus 服务器中添加被监控机器的配置即可，我们需要编辑 prometheus/prometheus.yml 文件，直接复制 prometheus 自带的 job 并修改如下：

![](images/FndgsXmWRpq3mvNr6Zn1xk-xr_Vm.png)

此时我们还需要动态刷新下才能生效：

\# 动态更新

curl -X POST http://localhost:9090/-/reload

![](images/Fv8AG-8vkCKJSBABlmaA_aWzRQDz.png)

刷新访问 Web UI 界面的 target 和 configuration 是否有对应的配置信息，从下图可以看出此时就已经加载了最新的配置信息：

![](images/Fqc-imj8ChVHT_BW6yBZV0mh1oTy.png)

再来看下 target 界面：

![](images/FrBNfFy_12cOdTx0PpfYBtfxKB9i.png)

**5.2**