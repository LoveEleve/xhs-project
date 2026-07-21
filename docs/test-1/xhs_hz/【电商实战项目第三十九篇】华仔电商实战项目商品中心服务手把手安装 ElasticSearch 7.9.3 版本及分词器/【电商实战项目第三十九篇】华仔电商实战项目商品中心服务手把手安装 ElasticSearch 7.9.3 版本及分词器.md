从今天之后的一段时间内，华仔会带着大家一起从零开始搭建并研发一套高并发的电商实战项目，这里会涉及到很多互联网大厂开发过程中所使用的核心技术和架构设计模式，希望大家学完之后可以用到自己的简历中。

接下来我们会重点架构设计和开发一下我们高并发电商实战项目中的「**商品中心服务**」。

这是第三十九篇，本篇我们继续进行电商实战项目设计与开发，本篇将进行「**商品中心服务**」手把手安装 ElasticSearch 7.9.3 版本。

文章汇总位置：[https://wx.zsxq.com/dweb2/index/columns/51122554151214](https://wx.zsxq.com/dweb2/index/columns/51122554151214)

![](images/Fnl1ueruAt4exbk9U2e5CcbjFHDI.png)

源码授权与获取地址：[https://articles.zsxq.com/id\_1s85grnaae4p.html](https://articles.zsxq.com/id_1s85grnaae4p.html)

![](images/FmkphgNM2yJBSqCq4raWXJA2r2Ah.png)

本章源码地址：[https://gitcode.net/u011359591/huazai-ecshop/-/tree/ecshop-chapter-3](https://gitcode.net/u011359591/huazai-ecshop/-/tree/ecshop-chapter-39)9

## **01 前言**

终于要设计与研发电商项目代码了，今天我们主要对「**商品中心服务**」安装 ES 和 IK 中文分词器。

这里需要注意下，我们整个项目目前是不提供前端的，这个后续有时间在搞，主要是进行后端接口以及微服务模块架构设计。

关于 es 相关组件，如果下载不到可以直接从网盘下载：

通过网盘分享的文件：es 相关软件

链接: [https://pan.baidu.com/s/1mK1YOGfaGVCbdxCGjHYs9w?pwd=5wy9](https://pan.baidu.com/s/1mK1YOGfaGVCbdxCGjHYs9w?pwd=5wy9) 提取码: 5wy9

\--来自百度网盘超级会员v9的分享

## **02 商品中心安装 ElasticSearch 7.9.3**

在 [【电商实战项目第三十九篇】华仔电商实战项目商品中心服务手把手安装 ElasticSearch 8.5.2 版本及分词器](https://articles.zsxq.com/id_68an3a0r8lw2.html) 这篇中，我们安装了 es 8.5.2 版本，但是在实际使用 es 推荐的高级客户端操作时，遇到了各种问题，很难解决。

官方 API：[https://www.elastic.co/guide/en/elasticsearch/client/java-rest/current/java-rest-high.html](https://www.elastic.co/guide/en/elasticsearch/client/java-rest/current/java-rest-high.html)

![](images/FmYY41QZCGGfxM1IiivVRvfcgqZ1.png)

可以看到高级 API 在 7.17 版本之后被废弃了，但是可以启动兼容模式下与 8.x 版本一起工作，不过还是各种问题，不打算折腾了，直接降级到 7.9.3 版本，我们来测试下这个版本的操作情况。

今天我们来手把手安装最新 ElasticSearch 7.9.3 版本，先不废话，马上开始。

## **2.1 官方下载地址**

官方下载地址：[https://www.elastic.co/cn/downloads/elasticsearch](https://www.elastic.co/cn/downloads/elasticsearch)

这里我们选用 Linux x86\_64，如下图：

![](images/Ftx_6qkzOv6fZJNpnnjPUXIqq8Oq.png)

默认上图是最新版 8.16.1，我们选择稍微低点的版本：[https://www.elastic.co/downloads/past-releases/elasticsearch-7-9-3](https://www.elastic.co/downloads/past-releases/elasticsearch-7-9-3)

选好之后，点击下载，或者使用 wget 命令在服务器上直接下载。

wget https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-7.9.3-linux-x86\_64.tar.gz

服务器存放路径如下，自己可以根据本地情况存放即可，建议使用 Centos 或者 Ubuntu：

![](images/Fk83cpMg9h7rCWstw3T7qO_5_8ai.png)

## **2.2 解压**

切换到 [/home/es](http://home/es) 目录下进行解压，并修改目录用户组权限：

sudo tar xf elasticsearch-7.9.3-linux-x86\_64.tar.gz

![](images/FuuxPwh0dIZtvpbdZ_7Zw5BijNBn.png)

## **2.3 手把手安装 ES**

这里我们将 es 安装包放到根目录的 [/home/es](http://home/es) 目录下。

### **2.3.1 尝试执行启动**

我们先尝试执行下，看有什么报错。

![](images/Fnr84EnT54Sfu37JYwsQsQAZ5XU0.png)

可以看到报错，这里就是 es 强依赖 jdk 的问题，由于 es 和 jdk 是一个强依赖的关系，所以当我们在新版本的 ElasticSearch 压缩包中包含有自带的 jdk，但是当我们的 Linux 中已经安装了jdk 之后，就会发现启动 es 的时候优先去找的是 Linux 中已经装好的 jdk，此时如果 jdk 的版本不一致，就会造成 jdk 不能正常运行。

由于我在 Ubuntu 中安装的 java 1.8 版本，导致运行出错。

> 注：如果 Linux 服务本来没有配置 jdk，则会直接使用 es 目录下默认的 jdk，反而不会报错

###   
**2.3.2 修改 bin 文件**

############## 添加配置解决jdk版本问题 ##############

\# 将jdk修改为es中自带jdk的配置目录

export JAVA\_HOME=/usr/local/elasticsearch-7.9.3/jdk

export PATH=$JAVA\_HOME/bin:$PATH

if \[ -x "$JAVA\_HOME/bin/java" \]; then

JAVA="/usr/local/elasticsearch-7.9.3/jdk/bin/java"

else

JAVA=\`which java\`

fi

另外需要把 $JAVA\_HOME 相关的配置都失效掉，比如：[/etc/profile](http://etc/profile)、[/etc/environment](http://etc/environment%20) 等。

### **2.3.3 修改 elasticsearch.yaml 配置文件**

vim ../config/elasticsearch.yml

![](images/FpgSq98myAjMxlg4xB5umVWeFoPA.png)

![](images/FsTkuW133XnbIYjM6gnMEFUYBuIc.png)

### **2.3.4 再次尝试执行启动**

关于创建 es 账户，直接参考 8.5.2 那篇。

![](images/FvYbI670xtqa8guhSAoCrC0WBsdx.png)

这里我们使用后台启动命令：

\# 进入 bin 目录

cd /home/es/elasticsearch-7.9.3/bin

#启动elasticsearch -d 是指在后台运行

./elasticsearch -d

可以看到启动成功了。

![](images/FrpTDLOj0uIdCHolOjsuKNnH1JOj.png)

![](images/Fu9m1TWewJ3_ZQuW4NCxDtkwfUPM.png)

至此，elasticsearch 安装完成，下一节进行 IK 中文分词器的改造和安装。

##   
**2.4 IK 源码改造及安装 IK 中文分词器**

IK 源码官方地址：[https://github.com/infinilabs/analysis-ik](https://github.com/infinilabs/analysis-ik)

我们下载跟 elasticsearch 同版本的 [ik](https://codeload.github.com/infinilabs/analysis-ik/zip/refs/tags/v8.5.2)，解压后，用 IDEA 打开。

tag 包版本列表地址：[https://github.com/infinilabs/analysis-ik/tags](https://github.com/infinilabs/analysis-ik/tags)

tag 包下载地址：[https://github.com/infinilabs/analysis-ik/archive/refs/tags/v7.9.3.zip](https://github.com/infinilabs/analysis-ik/archive/refs/tags/v7.9.3.zip)

![](images/FquxuQfbyrpgz_5vXrwxWlyeGsPD.png)

### **2.4.1 IK 中文分词器源码改造**

原生开源的 IK 中文分词器不太好用，通常我们都需要进行一些二次开发和改造，来看看都需要做些什么。

首先 IK 需要支持从自定义的数据库加载词库，开启一个后台线程，定时从数据库「**热刷新**」和「**热加载**」最新的词库。

![](images/FphPj7TUc_1mfBV0hYWPAn5KQA0D.png)

具体的实现自行下载代码后查看，简单来说就是自己构造一个从 mysql 查询数据的方法，并将结果集添加到 IK 分词器的内存中。

接下来需要修改下顶层的 pom 文件，将 es 的版本从 [7.4.0](http://7.4.0.0/) 改成 [7.9.3](http://7.9.0.3/)。

![](images/Fk_jbkZ0xcALPn8BvmIWIUQagJtN.png)

执行 [mvn clean package](http://mvn%20clean%20package/)，得到修改后的 [elasticsearch-analysis-ik-7.9.3.jar](http://elasticsearch-analysis-ik-7.9.3.jar/) jar 包，后面会用这个 jar 包替换原先的 ik 发行版本中的 jar 包。

![](images/Fnb5ksezMC1LXSO22Y75PiCxZQcm.png)

CREATE TABLE \`extension\_word\` (

\`id\` bigint NOT NULL AUTO\_INCREMENT,

\`word\` varchar(64) COLLATE utf8mb4\_general\_ci NOT NULL DEFAULT '' COMMENT '分词',

\`createtime\` datetime DEFAULT CURRENT\_TIMESTAMP COMMENT '创建时间',

\`updatetime\` datetime DEFAULT CURRENT\_TIMESTAMP ON UPDATE CURRENT\_TIMESTAMP COMMENT '更新时间',

PRIMARY KEY (\`id\`)

) ENGINE\=InnoDB DEFAULT CHARSET\=utf8mb4 COLLATE=utf8mb4\_general\_ci COMMENT\='中文分词词库';

### **2.4.2 IK 中文分词器安装**

release 发行版列表：[https://github.com/infinilabs/analysis-ik/releases?page=9](https://github.com/infinilabs/analysis-ik/releases?page=9)

![](images/Fv_hJ3XK2281kTOrT7bZaKPEh8f2.png)

先下载官方发行版：[https://github.com/infinilabs/analysis-ik/releases/download/v7.9.3/elasticsearch-analysis-ik-7.9.3.zip](https://github.com/infinilabs/analysis-ik/releases/download/v7.9.3/elasticsearch-analysis-ik-7.9.3.zip)

接着在 elasticsearch 安装目录的 plugins 目录下创建一个 ik 目录用来存放解压后的 ik 包。

![](images/FqB9_J5yFqJjsCw3kIXb2dO4aNMa.png)

解压刚刚上传或者 wget 下来的 ik 包到当前 ik 目录下。

sudo unzip /home/wangjianghua/src/elasticsearch-analysis-ik-7.9.3.zip

解压后，接着用我们改造打包好的 [elasticsearch-analysis-ik-7.9.3.jar](http://elasticsearch-analysis-ik-7.9.3.jar/) 替换目录下的 [elasticsearch-analysis-ik-7.9.3.jar](http://elasticsearch-analysis-ik-7.9.3.jar/)，并上传 mysql 连接 jar 包。

  
![](images/FumOrYpQbkVPSuLtJ3akEXY8BZLN.png)

  
最后在 [ik/config](http://ik/config) 目录下添加 [jdbc.properties](http://jdbc.properties/) 文件用来连接 mysql，然后启动 ES。

![](images/FjogiJI-3GSC4HCrKqfPlMBI2Pan.png)

![](images/FjTdCrA1YGCz9wu0S-3hEbBOCYLK.png)

目前这块测试有点问题，先忽略，使用官方的。后续调整好再使用。

## **2.5 拼音分词器安装**

源码官方地址：[https://github.com/infinilabs/analysis-pinyin](https://github.com/infinilabs/analysis-pinyin)

release 下载地址：[https://github.com/medcl/elasticsearch-analysis-pinyin/releases/download/v7.9.3/elasticsearch-analysis-pinyin-7.9.3.zip](https://github.com/medcl/elasticsearch-analysis-pinyin/releases/download/v7.9.3/elasticsearch-analysis-pinyin-7.9.3.zip)

> 注意：ik 分词器跟拼音分词器的版本最好跟 es 主版本一致，避免引起错误。

安装基本跟 ik 分词器一致，首先需要在 elasticsearch 安装目录的 plugins 目录下创建一个 pinyin 目录用来存放解压后的 pinyin 包。

![](images/FpsWNCyDjDe9VkO90e5tcnaswTLE.png)

解压刚刚上传或者 wget 下来的 pinyin 包到当前 pinyin 目录下。

sudo unzip /home/wangjianghua/src/elasticsearch-analysis-pinyin-7.9.3.zip

![](images/FuVTPgkn6ohEr6AbfEevwiHpak_J.png)

然后就可以重启 ES 了。

  
![](images/Ft7xJLzClaDkwH3rone9FdC7UTTQ.png)

![](images/Fo0KNmDGAs9U2kPGGFloyy0lH_bL.png)

![](images/Fry495QLz3jKPDBCWSd7gqCRjW9v.png)

## **2.6 手把手安装 kibana**

最后我们来安装下 kibana，后续操作 ES 都会通过 kibana 来进行。

### **2.6.1 官方下载地址**

官方下载地址：[https://www.elastic.co/downloads/past-releases/kibana-7-9-3](https://www.elastic.co/downloads/past-releases/kibana-7-9-3)

![](images/FtGxW8wjXeWjEG6HeSnVN89Jgl0c.png)

选好之后，点击下载，或者使用 wget 命令在服务器上直接下载。

\# 下载

wget https://artifacts.elastic.co/downloads/kibana/kibana-7.9.3-linux-x86\_64.tar.gz

\# 解压到 /home/es 目录下

sudo tar xf kibana-7.9.3-linux-x86\_64.tar.gz

![](images/FpnwGVMmpFAJ8hVTbV8gWs0KzZ5v.png)

### **2.6.2 启动 kibana**

先修改下配置如下：

![](images/Fg0JMZhgbCVp6eA2eZqpB3ANsXTx.png)

启动 kibana。

![](images/FqhOT5u-Bm8bzgRxl3qH45SNldnn.png)

至此，我们就可以通过浏览器进行访问 kibana 了，地址如下：

  
![](images/FgHlmVT_5bxJrznXuphigQKBh4yV.png)

进入之后，作为开发、运维，我们经常会用到以下几个功能，如下图所示：

###   
**2.7.3.1 Stack Monitoring**

![](images/FsAdWSDhuKj5EUrECZiIkywgUP9H.png)

点击进去可以看到如下监控：

  
![](images/Fo0jKQq0clH8uE4bkrXs3UY2YNWM.png)

### **2.7.3.2 Dev Tool**

![](images/FuqJQ2D5FTvf6myUDGTEt_9-SZO1.png)

后续我们经常会使用该工具来设置和查询 es 相关索引数据或者监控数据。

  
![](images/FnQ5ixqnpMT4K8H3VbLoNEjCaUdc.png)

另外大家也可以通过 head 插件进行查询，这里就不安装了，自行从扩展中安装即可，关于使用请点击：[https://blog.csdn.net/qq\_50854662/article/details/135967448](https://blog.csdn.net/qq_50854662/article/details/135967448)

## **2.7 手把手安装 elasticvue**

这里再给大家推荐一款**轻量级且强大**的 Elasticsearch GUI ：**elasticvue，**这是一款桌面程序。

![](images/FqKEX-KNnQrXeLJwafizoarUNcgw.png)

### **2.7.1 下载安装**

官方地址：[https://github.com/cars10/elasticvue/releases/](https://github.com/cars10/elasticvue/releases/)

进入下载界面：

![](images/Fmn1nXE7mgiJMD1PjUV9K94mG-PO.png)

我本地是 windows，自行选择，安装完成之后，点击图标，显示如下：

![](images/FiwBgrMrMk0qGF6EHEEP2mkGyaeX.png)

### **2.7.2 集群配置**

点击 「**添加ELASTICSEARCH集群**」按钮 ，选择不同的验证方式（无需验证、用户名和密码、API key）。

![](images/FlK1yZe4oqWf2vMNSwSpkkFqko7l.gif)

点击「**测试连接**」，弹出成功提示后，连接即可。

![](images/Fsr_zFp8u1OnvSyS9hh5AURzVLi6.png)

如图，集群首页显示集群的节点信息、集群健康状况等。

首页第一栏目有很多的操作选项：「**节点**」**、**「**分片**」**、**「**索引**」**、**「**搜索**」**、**「**REST**」 **、**「**快照**」**、**「**配置**」。

![](images/FsvyF8uCR1JYXrmPNlkOMhERoB85.png)

![](images/FjkUrzf9zhtqG-wxCL-PGS4jxYpP.png)

![](images/Flr4WQB8yzt-daw4SvvJEGnLzzfi.png)

这个搜索功能不错，可以测试下。

![](images/FpPZnBjWiNMwDL31TEVRq1caLttD.png)

### **2.7.3 创建索引**

在 Elasticsearch 中创建索引是一个相对简单的过程，可以通过发送 HTTP PUT 请求来完成。创建索引时，你可以定义索引的设置（settings）和映射（mappings）。

具体示例步骤如下：

### **1、准备工作**

确保你已经安装并运行了 Elasticsearch，并且可以通过命令行工具（如 curl）、编程语言客户端，或者通过 Kibana 的 Dev Tools 控制台与之交互。

这里介绍 elasticvue 如何通过 GUI 界面与 ES 交互创建索引。

### **2、索引示例**

这里使用我们商品 sku 信息作为示例：

PUT /huazai\_ecshop\_sku\_index\_test

{

"settings":{

"number\_of\_shards" : 1,

"number\_of\_replicas":1

},

"mappings":{

"properties":{

"skuId": {

"type" :"integer"

},

"skuName" : {

"type" : "text",

"analyzer": "ik\_max\_word",

"search\_analyzer":"ik\_smart"

},

"category":{

"type" :"keyword"

},

"brand":{

"type" :"keyword"

},

"price":{

"type":"integer"

},

"vipPrice":{

"type":"integer"

},

"mainUrl" :{

"type":"keyword",

"index": false

},

"skuStatus" : {

"type":"integer"

},

"createTime":{

"type": "date",

"format": "yyyy-MM-dd HH:mm:ss"

},

"updateTime":{

"type": "date",

"format": "yyyy-MM-dd HH:mm:ss"

}

}

}

}

在 ES 中，PUT 方法用于创建或更新索引、文档或设置 ，请求体包含了一个 mappings 部分，这用来定义索引中文档的结构和字段的数据类型。

映射是索引内文档结构的蓝图，它告诉 Elasticsearch 如何处理和存储数据。

### **3、创建索引**

点击 REST 按钮，将例子索引拷贝左侧文本框，点击发起请求后，右侧文本框会返回响应结果。

![](images/FjaxSMOzSs22LkfiLW578T4Gv3V6.png)

### **4、添加数据**

我们可以使用 POST 命令添加索引数据，格式如下：

![](images/FkIwqTOfQWMzDLxF_ze6XijY_gs-.png)

### **5、查看索引**

点击索引栏目，进入示例索引，可以查看所有的索引数据，点击最右侧操作按钮，查看数据详情。

![](images/Fp7vUAFhJMpqjWXeRKl9EpjaEEAc.png)