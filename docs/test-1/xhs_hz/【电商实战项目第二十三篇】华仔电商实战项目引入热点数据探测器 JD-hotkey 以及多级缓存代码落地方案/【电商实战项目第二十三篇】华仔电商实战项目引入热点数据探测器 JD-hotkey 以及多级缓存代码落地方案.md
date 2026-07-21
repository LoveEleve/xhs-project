大家好，我是**华仔**, 又跟大家见面了。

从今天之后的一段时间内，华仔会带着大家一起从零开始搭建并研发一套高并发的电商实战项目，这里会涉及到很多互联网大厂开发过程中所使用的核心技术和架构设计模式，希望大家学完之后可以用到自己的简历中。

接下来我们会重点架构设计和开发一下「**社交系统**」中的重要服务：「**关注服务**」、「**计数服务**」、「**评论服务**」。

这是第二十三篇，本篇我们继续进行电商实战项目设计与开发，本篇主要对「**电商项目**」引入热点数据探测器 [JD-hotkey](https://gitee.com/jd-platform-opensource/hotkey) 以及「**多级缓存**」代码落地方案。

文章汇总位置：[https://wx.zsxq.com/dweb2/index/columns/51122554151214](https://wx.zsxq.com/dweb2/index/columns/51122554151214)

![](images/Fnl1ueruAt4exbk9U2e5CcbjFHDI.png)

源码授权与获取地址：[https://articles.zsxq.com/id\_1s85grnaae4p.html](https://articles.zsxq.com/id_1s85grnaae4p.html)

![](images/FmkphgNM2yJBSqCq4raWXJA2r2Ah.png)

本章源码地址：[https://gitcode.net/u011359591/huazai-ecshop/-/tree/ecshop-chapter-23](https://gitcode.net/u011359591/huazai-ecshop/-/tree/ecshop-chapter-23)

## **01 前言**

终于要设计与研发电商项目代码了，今天我们主要对「**电商项目**」引入热点数据探测器 [JD-hotkey](https://gitee.com/jd-platform-opensource/hotkey) 以及「**多级缓存**」代码落地方案，本篇内容比较多，也非常重要，希望大家认真学习。

这里需要注意下，我们整个项目目前是不提供前端的，这个后续有时间在搞，主要是进行后端接口以及微服务模块架构设计。

## **02 热点数据探测器实现原理**

说到「**热点**」问题，首先我们先理解一下什么是「**热点**」？

「**热点**」通常意义来说，是指在一段时间内，被广泛关注的物品或事件，例如「**微博热搜**」、「**热卖商品**」、「**热点新闻**」、「**明星直播**」等等。

所以热点产生主要包含 2 个条件：

1.  有限时间。
2.  流量高聚。

  
![](images/FmpSgoajFFsg-phl0bMPTGtjTRkh.png)

而在互联网领域，热点又主要分为 2 大类：

1.  有预期的热点：比如在电商活动当中推出的爆款联名限量款的商品，又或者是秒杀的会场活动等。
2.  无预期的热点：比如受到了黑客的恶意攻击，网络爬虫频繁访问，又或者突发新闻带来的流量冲击等。

针对于「**有预期**」的热点可以通过「**热点数据预热**」、「**流量限制**」、「**异步队列**」等方式进行处理。但是对于「**突发性无感知**」的热点数据流量，往往由于请求过于集中，导致访问数据流量超出的 server 的正常负载水位，从而出现服务过载不可用的情况，这种问题被称之为「**热点问题**」。

## **2.1 热点探测使用场景**

我们已经理解了「**热点问题**」产生的条件是「**短时间内被频繁访问导致流量高聚**」，而流量高聚就会出现一系列的「**热点问题**」。

那些被频繁访问的 Key，就是我们通常所说的「**热 Key**」。

我们来看一下哪些场景会导致热点问题以及对应的热 Key：

1.  MySQL 中被频繁访问的数据 ，如热门用户、美食笔记、商品等主键 Id。
2.  Redis 缓存中被密集访问的 Key，如热门用户、美食笔记、商品的详情信息。
3.  恶意攻击或机器人爬虫的请求信息，如特定标识的 userId、机器 IP。
4.  频繁被访问的接口地址，如获取用户信息接口 /userInfo/ + userId。

## **2.2 使用热点探测好处**

### **2.2.1 提升性能**

解决热点问题通常会使用分布式缓存，但是在读取时还是需要进行「**网络调用**」，就会有额外的时间开销。那如果能对热点数据提前进行「**本地缓存**」，即本地预热，就能大幅提升机器读取数据的性能，减轻下层缓存集群的压力。

> 注意，本地缓存与实时数据存在不一致的风险。需要根据具体业务场景进行评估，缓存级数越多，数据不一致的风险就越大！

### **2.2.2 规避风险**

对于突发场景下形成的热 Key，可能会对业务系统带来极大的风险，可将风险分为两个层次：

1.  数据层的风险：正常情况下，Redis 缓存单机就可支持十万左右 QPS，并能通过集群部署提高整体负载能力。对于并发量一般的系统，用 Redis 做缓存就足够了。但是对于瞬时过高并发的请求，因为 Redis 单线程原因会导致正常请求排队，或者因为热点集中导致分片集群压力过载而瘫痪，从而击穿到 DB 引起服务器雪崩。
2.  对应用服务的风险：每个应用在单位时间所能接受和处理的请求量是有限的，如果受到恶意请求的攻击，让恶意用户独自占用了大量请求处理资源，就会导致其他人畜无害的正常用户的请求无法及时响应。

因此，需要一套「**动态热 Key 检测机制**」，通过对需要检测的热 Key 规则进行配置，实时监听统计热 Key 数据，当突发的热点数据出现时，第一时间发现他，并针对这些数据进行特殊处理。如「**本地缓存**」、「**拒绝恶意用户**」、「**本接口限流/降级**」等处理方案。

##   
**2.3 如何进行热点探测**

首先我们要定义一下如何才能算是一个热点，我们知道热点产生的条件是 2 个：一个时间，一个流量。

那么根据这个条件我们可以简单定义一个规则：比如 [1](http://0.0.0.1/) 秒内访问 [1000](http://1000%20/) 次的数据算是热数据，当然这个数据需要根据具体的「**业务场景**」和「**过往数据**」进行具体评估。

对于单机应用，检测热数据很简单，直接在本地为每个 Key 创建一个「**滑动窗口**」计数器，统计「**单位时间**」内的访问总数（频率），并通过一个集合存放检测到的热 Key。

![](images/FuEqmZng7FhKZ7EBeoAgvUr8kawI.png)

而对于分布式应用，对热 Key 的访问是「**分散**」在不同的机器上的，无法在本地独立地进行计算，因此，需要一个「**独立的、集中的热 Key 计算单元**」。

我们可以简单理解为：分布式应用节点感知热点规则配置，将热点数据进行上报，工作节点进行热点数据统计，对于符合阈值的热点进行推送给客户端，应用收到热点信息进行本地缓存等策略这五个步骤：

1.  热点规则：配置热 Key 的上报规则，圈出需要重点监测的 Key。
2.  热点上报：应用服务将自己的热 Key 访问情况上报给集中计算单元。
3.  热点统计：收集各应用实例上报的信息，使用滑动窗口算法计算 Key 的热度。
4.  热点推送：当 Key 的热度达到设定值时，推送热 Key 信息至所有应用实例。
5.  热点缓存：各应用实例收到热 Key 信息后，对 Key 值进行本地缓存（此步骤根据具体业务策略调整）。

  
![](images/Fncrp-E_DSixs6lXkw9uBOn6z66l.png)

![](images/FlNIqIiSR1j3hZiHYbyCJtOFk4wJ.png)

## **03 JD-hotKey 安装与架构实现原理**

了解完「**热点数据探测原理**」之后，我们来看下业界流行的由京东开源的 hotKey 安装过程以及架构实现原理。

##   
**3.1 JD-hotKey 介绍与安装**

官网地址：[https://gitee.com/jd-platform-opensource/hotkey](https://gitee.com/jd-platform-opensource/hotkey)

![](images/Ft2twaFICGFw4sg5Sl-LT-aUK7BE.jpg)

京东根据多次被突发海量请求压垮数据层服务的场景，并时刻面临大量的爬虫刷子机器人用户的请求的经验，设计开发了一套通用轻量级热 key 探测框架——[JdHotkey](http://jdhotkey/)。这个框架历经多次高压压测和2020年京东618、双11大促考验。

[JdHotKey](http://jdhotkey/) 可以对任意突发性的无法预先感知的热点数据，包括并不限于：

1.  热点数据（如突发大量请求同一个商品，用户，美食）。
2.  热用户（如恶意爬虫刷子）。
3.  热接口（突发海量请求同一个接口）。

然后进行毫秒级精准探测到，对这些热数据、热用户等，推送到所有服务端 JVM 内存中，以大幅减轻对后端数据存储层的冲击，并可以由使用者决定如何分配、使用这些热key（譬如对热商品做本地缓存、对热用户进行拒绝访问、对热接口进行熔断或返回默认值）。这些热数据在整个服务端集群内保持一致性，并且业务隔离，worker 端性能强悍。

虽然 JD-hotKey 是开源的，但是它是有很多坑要自己解决的，比如：

1.  导入 maven 依赖时，你会发现导入不进去，主要因为它没有把代码上传到 maven 的中央仓库。你只能把它打成 Jar 包，上传到本地或者公司的 maven 仓库才可以使用。
2.  导入成功后，有些地方还需要自己修改才能使用。

下面我们就来安装下这个工具，由于它底层依赖的是 etcd，所以我们需要先安装 etcd。

### **3.1.1 安装 etcd**

在 etcd 下载页面下载对应操作系统的 etcd，[下载地址](https://github.com/etcd-io/etcd/releases)，建议使用 [3.4.x](http://3.4.x/) 以上。

我本地是 Windows，所以这里下载的是 Windows 版本，大家可以根据自己的情况自行选择对应版本。

![](images/Fu8wmL6z-jW56cn7dipkt96q2Qcw.png)

下载完成后将其解压，并启动 etcd.exe

![](images/FkUNg7jzbZBih1sHLg4BKzIRxEbH.png)

我们这里以单机为主，后期等真正部署上线时才会启动 ectd 集群模式。

### **3.1.2 拷贝代码**

我们先来把它的源代码下载到本地，然后使用 IDE 进行部分修改。

![](images/FsDphrfgIpIXCl_Ttd6fs3W6gFX8.png)

### **3.1.3 打开项目，运行 SQL**

在这个目录下会有一个 SQL，需要创建一个 [hotkey\_db](http://hotkey_db%20/) 的数据库。

![](images/FmKOAvDt9ksJOlS_kUQ8KlOUWqH0.png)

![](images/FuU3FJjnJG5DO433MggevUv-pK-O.png)

修改数据库配置：

![](images/FnnA9mZSpnlL9rrh_w0KnTXCqsWN.png)

### **3.1.4 启动 Worker**

这里可以将 worker 打包为 Jar 包，也可以直接在代码中启动（必须先启动 [etcd](http://etcd/)，否则报错）。

![](images/FsgkrE2yzRUT8JYtKXMdhie5iamY.png)

![](images/Fjn5nyhBAslzWijbt2-ZCkEqxAD5.png)

点击菜单栏 [File](http://file%20/) -> [Project Structrue](http://project%20structrue/) 。

![](images/FnY0yIGA1x39dQMgzia4EQcT2Vbm.png)

![](images/Fgim8HJ9qWiipS7VKPrPmdE5P7f3.png)

继续启动 Worker：

![](images/FndyOqKKXvQXBsLaHdsctjlHNFdJ.png)

### **3.1.5 启动控制台 dashboard**

一定记得在 [application.yml](http://application.yml/) 中修改自己的 MySQL 地址和用户名和密码。

![](images/Fsd8blRb_iVwKBiyMwWrJx40W_q6.png)

![](images/Fu9WXrdNK839rm5w2Mo6o6A95vf2.png)

### **3.1.6 进入控制台 dashboard**

访问地址：IP:8081。 因为我是本地启动，所以是 [127.0.0.1:8081](http://127.0.0.1:8081/)。登录的账号密码默认 [admin](http://admin%20/) [123456](http://0.1.226.64/)。

![](images/Flgw0Vy27QTWpMrcRsnIT1Um7rXg.png)

![](images/Flozjhs2vBlpFpik3B-A1QIKQold.png)

### **3.1.7 添加用户**

此时可以在用户管理添加用户（重要的是所属 APP）

![](images/Fs0_3k_-XCZLj5enzRXeDw_V6X3d.png)

### **3.1.8 配置规则**

参数如下：

1.  key-(\*代表任意以key为前缀)。
2.  prefix-是否前缀，**这里需要给 true，否则 worker 收集的时候可能会失败，导致 dashboard 后台热点模块没数据**。
3.  interval-间隔时间(秒)。
4.  threshold-阈值。
5.  duration-缓存时间(秒),默认60。

\[

{

"duration": 120,

"interval":2,

"key":"user\_info",

"prefix": true,

"threshold": 3,

"desc":"热门用户"

},

{

"duration": 120,

"interval":2,

"key":"food\_info",

"prefix": true,

"threshold": 3,

"desc":"热门美食"

},

{

"duration": 120,

"interval":2,

"key":"user\_follower\_info",

"prefix": true,

"threshold": 3,

"desc":"热门关注"

}

\]

![](images/FuQRuZTdyUBpgG9Mo60o4k1rOwoS.png)

![](images/FsDJ6NSW2Cs1DHNd-T3EDVKaRY3l.png)

如果在自己实战的时候发现如下：

worker 这里收集到的都是 0：

![](images/Fl9WqV1ujHFaMyk1cNgIBNV3PeSw.png)

etcd 内部也是 0：

![](images/Ftb82XtOTuqZgf0RkExN0g5uoqr4.png)

查看 etcd 相关 key：

\# 查看所有 key

etcdctl --endpoints=http://localhost:2379 get / --prefix --keys-only

\# 查看 /jd/totalKeyCount 相关统计

etcdctl --endpoints=http://localhost:2379 get /jd/totalKeyCount/192.168.31.11

正常情况如下：

![](images/FtnBnQc8E7ELnlaYK7ZMSVxSK0F6.jpg)

![](images/FvP-2otBw2xIcCrzwwxGHD40uqfv.jpg)

问题就出现在配置中：

![](images/FtfjinPcYD3dpYnvP3gTovgM7EKx.png)

## **04 JD-hotKey 项目实战**

接下来，我们来进行实战。

## **4.1 添加 JD-hotKey 依赖包**

首先需要将 maven 依赖包导入到我们电商实战项目中，由于 JD-hotKey 比较坑，它的依赖包并不存在于 maven 中央仓库中，执行时会报错。

![](images/FnLyKh7z2nmnDlFilHiuS38lwA6W.png)

所以这里我们需要手动将其打包，并添加到本地 maven 仓库中。

### **4.1.1 打 Jar 包**

在下载好的 hotkey 项目中进行打包，打包需要花时间，所以耐心等待完成，操作步骤如下：

![](images/FlOZOU36IEtanQt-RGzmxbIxb1fg.png)

![](images/Fjaai-RU1iIYUJz_LEXnuQU8uzg6.png)

这样会把所有的都打包，只不过这里我们只需要 [hotkey-client](http://hotkey-client/) 这个 jar 包。

### **4.1.2 在项目中引入依赖包**

![](images/FlLk0whQPNZ8dEPdb1st9qzw27d4.png)

![](images/Fo9XkuNbaA0q6yLCd4ifMxyj0BJd.png)

![](images/Fm3_awkEsIyeQn5cu4XMpsRSvziT.png)

这里会对之前的「**用户服务**」、「**美食服务**」进行热 key 探测与多级缓存实战。

## **4.2 用户服务热 key 探测与多级缓存实战**

### **4.2.1 hotkey 启动配置**

\# hotkey 相关配置

hotkey:

app-name: sample

\# etcd服务器地址，集群用逗号分隔

etcd-server: http://127.0.0.1:2379

\# 设置本地缓存最大数量，默认5万

caffeine-size: 50000

\# 批量推送key的间隔时间，默认500ms，该值越小，上报热key越频繁，相应越及时，建议根据实际情况调整

\# 如单机每秒qps10个，那么0.5秒上报一次即可，否则是空跑。该值最小为1，即1ms上报一次。

push-period: 1

![](images/Fk7Vt17FMKyXwVKkHY-dyTPulhdz.png)

### **4.2.2 hotkey 统一初始化配置**

![](images/Fs9NUg5eTccLTzAybMWq61lGGrlU.png)

### **4.2.3 RedisCache 统一处理**

/\*\*

\* 读取缓存

\* 后续会增加热 Key 读取，如果存在优先从内存中读取，否则再读取 Redis 缓存

\* @param key

\* @return

\*/

public Object getCache(String key) {

/\*

\* 从 JVM 内存中获取热点信息

\* 如果是热 key，则存在两种情况，1、是返回value，2、是返回 null。

\* 返回 null 是因为尚未给它 set 真正的 value，返回非 null 说明已经调用过 set 方法了，本地缓存 value 有值了。

\* 如果不是热 key，则返回 null，并且将 key 上报到探测集群进行数量探测。

\*/

Object hotkeyValue \= JdHotKeyStore.getValue(key);

log.info("缓存模块-从 JVM 内存中获取信息,key:{},value:{}", key, hotkeyValue);

if (hotkeyValue != null) {

return hotkeyValue;

}

String value \= this.get(key);

log.info("缓存模块-从缓存中获取信息,key:{},value:{}", key, value);

/\*

\* 方法给热 key 赋值 value，如果是热 key，该方法才会赋值，非热 key，什么也不做

\*/

JdHotKeyStore.smartSet(key, value);

return value;

}

/\*\*

\* 缓存存储

\* 将数据存储在内存和redis中，如果不是热key，就只存储redis

\*

\* @param key

\* @param value

\* @param seconds

\* @return void

\* @author zhonghuashishan

\*/

public void setCache(String key, Object value, int seconds){

/\*

\* 方法给热key赋值value，如果是热key，该方法才会赋值，非热key，什么也不做

\* 如果是热key，存储在内存中

\*/

//JdHotKeyStore.smartSet(key, value);

this.set(key, JsonUtil.object2Json(value), seconds);

}

### **4.2.4 测试效果**

这里我们简单写个示例测试下：

![](images/FoRuweCg6eIabBfgxa43Dr6H91en.png)

在一秒钟内请求几次，因为我设置了 2s 内的阈值是 3 次，所以肯定会触发热 Key 的阈值。

![](images/FlKX2YmK7fo7AIfCbg9n-N8rGlSd.png)

如果不好使可以，重新启动下 hotkey 的 worker。

![](images/FghdwdyqOQoiZJ9XYy-eqwaBvRCL.png)

![](images/FqW_Hfp5Ts9vH5Y7plkiNIB0rUGn.png)

![](images/FnLW1lyly80Uq2_KMp39SUY0kdtn.png)

### **4.2.5 真实效果测试**

这里主要还是对之前的用户详情进行热 Key 检测。

![](images/FlI-aD-NWMF6yUwKbhG591o2cJuA.png)

![](images/FiSu4JWAgzC_WD-IX-VnbLDIqn9u.png)

然后启动 user 服务，会获取 hotkey 配置规则。

![](images/Fiis8wf_NQ0JQqEvptjXJk11sst1.png)

测试下效果，使劲点

![](images/FtgYveNgLv6thsjKpgbTzbd7hVj5.png)

![](images/Fvc7ADAq8XdA3jeueaZI56IovGpC.png)

![](images/FtscfraFVBqah-7-oy3l1shXp5X7.png)

![](images/Fg4RPd2g1z04QY8_CG_XdCMqkoGr.png)

![](images/Ft4Yv2beUJfpZ2yqhQ-Lgg3Dv8gh.png)

## **4.3 美食服务热 key 探测与多级缓存实战**

有了前面的趟坑经验，后面其他服务都好搞很多了，我们也来看下。

### **4.3.1 hotkey 启动配置**

同用户服务的，这里直接复制过来。

\# hotkey 相关配置

hotkey:

app-name: sample

\# etcd服务器地址，集群用逗号分隔

etcd-server: http://127.0.0.1:2379

\# 设置本地缓存最大数量，默认5万

caffeine-size: 50000

\# 批量推送key的间隔时间，默认500ms，该值越小，上报热key越频繁，相应越及时，建议根据实际情况调整

\# 如单机每秒qps10个，那么0.5秒上报一次即可，否则是空跑。该值最小为1，即1ms上报一次。

push-period: 1

![](images/FuDgNXP_NHuC94XjUvaAobOWfN2M.png)

### **4.3.2 真实效果测试**

这里主要还是对之前的美食详情进行热 Key 检测。

![](images/Fp3fZG6SrmLLcpL_5hErkeYbSPYN.png)

![](images/FsBFIZbHqibPKY1Hw1mTtibs4OjJ.png)

然后启动 food 服务，使劲点这里，效果如下：

![](images/FoPDgZZKOumHTe089bePVu73rNc5.png)

![](images/FlmvXETlr1G4houdQvBQiIGKZfCS.png)

![](images/Fq2kq5hiypOgNUJcncfjhyecdr7N.png)

![](images/FjhiG0Zl1E2r7rRXdO1q-LPSjVuW.png)