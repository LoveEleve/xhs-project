从今天之后的一段时间内，华仔会带着大家一起从零开始搭建并研发一套高并发的电商实战项目，这里会涉及到很多互联网大厂开发过程中所使用的核心技术和架构设计模式，希望大家学完之后可以用到自己的简历中。

接下来我们会重点架构设计和开发一下我们高并发电商实战前端 Web 项目的相关功能 。

这是第八十七篇，本篇我们继续进行电商实战项目设计与开发，本篇我们正式接入「**微服务全家桶之网关服务 Gateway**」。

文章汇总位置：[https://wx.zsxq.com/dweb2/index/columns/51122554151214](https://wx.zsxq.com/dweb2/index/columns/51122554151214)

![](images/Fnl1ueruAt4exbk9U2e5CcbjFHDI.png)

源码授权与获取地址：[https://articles.zsxq.com/id\_1s85grnaae4p.html](https://articles.zsxq.com/id_1s85grnaae4p.html)

![](images/FmkphgNM2yJBSqCq4raWXJA2r2Ah.png)

本章源码地址：[https://gitcode.net/u011359591/huazai-ecshop/-/tree/ecshop-chapter-87](https://gitcode.net/u011359591/huazai-ecshop/-/tree/ecshop-chapter-87)

## **01 前言**

通过前面 86 篇，我们把「**小红书社交 + 电商**」整体的相关业务场景、技术架构设计、功能实现都完成了，今天我们来重点实战下「**微服务全家桶之网关服务 Gateway 的接入**」。

##   
**02 GateWay 网关搭建配置及避坑指南**

## **2.1 GateWay 相关依赖组件**

<dependencies>

<!--网关依赖-->

<dependency>

<groupId>org.springframework.cloud</groupId>

<artifactId>spring-cloud-starter-gateway</artifactId>

</dependency>

<!-- Spring Cloud 2020 中重磅推荐的负载均衡器 Spring Cloud LoadBalancer 简称 SCL-->

<dependency>

<groupId>org.springframework.cloud</groupId>

<artifactId>spring-cloud-starter-loadbalancer</artifactId>

</dependency>

<!--添加nacos客户端-->

<dependency>

<groupId>com.alibaba.cloud</groupId>

<artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>

</dependency>

<!--配置中心，需要需要使用配置中心，则开启-->

<dependency>

<groupId>com.alibaba.cloud</groupId>

<artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>

</dependency>

<!--坑：spring-cloud-dependencies 2020.0.0 默认不在加载bootstrap配置文件-->

<dependency>

<groupId>org.springframework.cloud</groupId>

<artifactId>spring-cloud-starter-bootstrap</artifactId>

</dependency>

<!--限流依赖-->

<dependency>

<groupId>com.alibaba.cloud</groupId>

<artifactId>spring-cloud-starter-alibaba-sentinel</artifactId>

</dependency>

<!--限流持久化到nacos-->

<dependency>

<groupId>com.alibaba.csp</groupId>

<artifactId>sentinel-datasource-nacos</artifactId>

</dependency>

<!-- 需要加 servlet包，不然配置跨域会找不到类 -->

<dependency>

<groupId>javax.servlet</groupId>

<artifactId>javax.servlet-api</artifactId>

<version>4.0.1</version>

</dependency>

</dependencies>

![](images/FpValZJHX0BPTjpWf-lMhapV1Hu1.png)

这里有几个坑需要注意下：

### **2.2.1 避坑1 默认 bootstrap.yaml 不再加载**

官方地址：[https://docs.spring.io/spring-cloud/docs/2020.0.1/reference/htmlsingle/#config-first-bootstrap](https://docs.spring.io/spring-cloud/docs/2020.0.1/reference/htmlsingle/#config-first-bootstrap)

![](images/FnHMcA-NxdXxVL2Jp8UYzIzczXwm.png)

新版默认不加载 bootstrap.yaml 导致无法使用配置中心 Nacos，解决办法：

![](images/FmCqNcYfwJtiW9SrCrqY6TrTFxuo.png)

### **2.2.2 避坑2 注册到 Nacos 报 503**

Spring Cloud Gateway 注册到了 Nacos ⽆法发现服务，报 503 Service Unavailable:

![](images/Flb-b-Ezonh3Mgr5R-mJaMk98Bx-.png)

需要依赖 loadbalancer 组件进行转发，解决办法：

![](images/FjaJKnYRJbDOZVXG0ZUDA3uhdNRf.png)

## **2.2 GateWay 启动类**

配置 application.yaml :

#服务端口

server:

port: 9090

spring:

application:

name: huazai-gateway

cloud:

nacos:

\# 注册中心

discovery:

server-addr: 192.168.31.11:8848

\# 命名空间id

namespace: 3661a3ef-e458\-476c\-9ee8\-4912bdc5ddce

username: nacos

password: nacos

增加启动类：

![](images/FqSWIGCrpXX80xXZSaOuKUJwPVbQ.png)

通过添加上面的依赖组件，发现最终可以启动成功，然后查看 Naocs 服务，发现网关已经注册进来了：

![](images/FrJAj3-ihtIF-P-EwjpcIF3CviLt.png)

至此，第一步操作完成，接下来正式接入多个微服务。

## **2.3 GateWay 网关配置接入多个微服务**

添加路由转发配置，如下：

#服务端口

server:

port: 9090

spring:

application:

name: huazai-gateway

cloud:

nacos:

\# 注册中心

discovery:

server-addr: 192.168.31.11:8848

\# 命名空间id

namespace: 3661a3ef-e458\-476c\-9ee8\-4912bdc5ddce

username: nacos

password: nacos

gateway:

routes: #数组形式

\- id: huazai-home #首页服务 路由唯一标识

uri: lb://huazai-home #从nocas进行转发

order: 1 #优先级，数字越小优先级越高

predicates: #断言 配置哪个路径才转发，前端访问路径统一加上XXX-server，网关判断转发对应的服务，如果是回调业务记得修改

\- Path=/home-server/\*\*

filters: #过滤器，请求在传递过程中通过过滤器修改

\- StripPrefix=1 #去掉第一层前缀，转发给后续的路径

\- id: huazai-user #用户服务 路由唯一标识

uri: lb://huazai-user #从nocas进行转发

order: 2 #优先级，数字越小优先级越高

predicates: #断言 配置哪个路径才转发，前端访问路径统一加上XXX-server，网关判断转发对应的服务，如果是回调业务记得修改

\- Path=/user-server/\*\*

filters: #过滤器，请求在传递过程中通过过滤器修改

\- StripPrefix=1 #去掉第一层前缀，转发给后续的路径

\- id: uazai-food #美食服务 路由唯一标识

uri: lb://huazai-food #从nocas进行转发

order: 3 #优先级，数字越小优先级越高

predicates: #断言 配置哪个路径才转发，前端访问路径统一加上XXX-server，网关判断转发对应的服务，如果是回调业务记得修改

\- Path=/food-server/\*\*

filters: #过滤器，请求在传递过程中通过过滤器修改

\- StripPrefix=1 #去掉第一层前缀，转发给后续的路径

\- id: huazai-social #社交服务 路由唯一标识

uri: lb://huazai-social #从nocas进行转发

order: 4 #优先级，数字越小优先级越高

predicates: #断言 配置哪个路径才转发，前端访问路径统一加上XXX-server，网关判断转发对应的服务，如果是回调业务记得修改

\- Path=/social-server/\*\*

filters: #过滤器，请求在传递过程中通过过滤器修改

\- StripPrefix=1 #去掉第一层前缀，转发给后续的路径

\- id: huazai-cart #购物车服务 路由唯一标识

uri: lb://huazai-cart #从nocas进行转发

order: 5 #优先级，数字越小优先级越高

predicates: #断言 配置哪个路径才转发，前端访问路径统一加上XXX-server，网关判断转发对应的服务，如果是回调业务记得修改

\- Path=/cart-server/\*\*

filters: #过滤器，请求在传递过程中通过过滤器修改

\- StripPrefix=1 #去掉第一层前缀，转发给后续的路径

\- id: huazai-product #商品服务 路由唯一标识

uri: lb://huazai-product #从nocas进行转发

order: 6 #优先级，数字越小优先级越高

predicates: #断言 配置哪个路径才转发，前端访问路径统一加上XXX-server，网关判断转发对应的服务，如果是回调业务记得修改

\- Path=/product-server/\*\*

filters: #过滤器，请求在传递过程中通过过滤器修改

\- StripPrefix=1 #去掉第一层前缀，转发给后续的路径

\- id: huazai-im #im服务 路由唯一标识

uri: lb://huazai-im #从nocas进行转发

order: 7 #优先级，数字越小优先级越高

predicates: #断言 配置哪个路径才转发，前端访问路径统一加上XXX-server，网关判断转发对应的服务，如果是回调业务记得修改

\- Path=/im-server/\*\*

filters: #过滤器，请求在传递过程中通过过滤器修改

\- StripPrefix=1 #去掉第一层前缀，转发给后续的路径

\- id: huazai-coupon #优惠券服务 路由唯一标识

uri: lb://huazai-coupon #从nocas进行转发

order: 8 #优先级，数字越小优先级越高

predicates: #断言 配置哪个路径才转发，前端访问路径统一加上XXX-server，网关判断转发对应的服务，如果是回调业务记得修改

\- Path=/coupon-server/\*\*

filters: #过滤器，请求在传递过程中通过过滤器修改

\- StripPrefix=1 #去掉第一层前缀，转发给后续的路径

\- id: huazai-inventory #库存服务 路由唯一标识

uri: lb://huazai-inventory #从nocas进行转发

order: 9 #优先级，数字越小优先级越高

predicates: #断言 配置哪个路径才转发，前端访问路径统一加上XXX-server，网关判断转发对应的服务，如果是回调业务记得修改

\- Path=/inventory-server/\*\*

filters: #过滤器，请求在传递过程中通过过滤器修改

\- StripPrefix=1 #去掉第一层前缀，转发给后续的路径

\- id: huazai-order #订单服务 路由唯一标识

uri: lb://huazai-order #从nocas进行转发

order: 10 #优先级，数字越小优先级越高

predicates: #断言 配置哪个路径才转发，前端访问路径统一加上XXX-server，网关判断转发对应的服务，如果是回调业务记得修改

\- Path=/order-server/\*\*

filters: #过滤器，请求在传递过程中通过过滤器修改

\- StripPrefix=1 #去掉第一层前缀，转发给后续的路径

\- id: huazai-pay #支付服务 路由唯一标识

uri: lb://huazai-pay #从nocas进行转发

order: 11 #优先级，数字越小优先级越高

predicates: #断言 配置哪个路径才转发，前端访问路径统一加上XXX-server，网关判断转发对应的服务，如果是回调业务记得修改

\- Path=/pay-server/\*\*

filters: #过滤器，请求在传递过程中通过过滤器修改

\- StripPrefix=1 #去掉第一层前缀，转发给后续的路径

\- id: huazai-admin #后台服务 路由唯一标识

uri: lb://huazai-admin #从nocas进行转发

order: 12 #优先级，数字越小优先级越高

predicates: #断言 配置哪个路径才转发，前端访问路径统一加上XXX-server，网关判断转发对应的服务，如果是回调业务记得修改

\- Path=/admin-server/\*\*

filters: #过滤器，请求在传递过程中通过过滤器修改

\- StripPrefix=1 #去掉第一层前缀，转发给后续的路径

\- id: huazai-push #推送服务 路由唯一标识

uri: lb://huazai-push #从nocas进行转发

order: 13 #优先级，数字越小优先级越高

predicates: #断言 配置哪个路径才转发，前端访问路径统一加上XXX-server，网关判断转发对应的服务，如果是回调业务记得修改

\- Path=/push-server/\*\*

filters: #过滤器，请求在传递过程中通过过滤器修改

\- StripPrefix=1 #去掉第一层前缀，转发给后续的路径

#开启网关拉取 nacos 的服务

discovery:

locator:

enabled: true

#设置日志级别：ERROR/WARN/INFO/DEBUG,默认是 INFO 以上才显示

logging:

level:

root: INFO

#nacos日志问题

com.alibaba.nacos.client.config.impl: WARN

这里有三部分组成，说明下：

1.  添加路由转发配置。
2.  开启网关拉取 Nacos 服务。
3.  Nacos 日志打印。

第一部分说明：

1.  [id](http://id%20/) ：服务路由唯一标识。
2.  [uri](http://uri/)：lb://xxx 从nocas进行转发 xxx 表示注册到 Nacos 的名称。
3.  [order](http://order/): 优先级，这里我默认从上到下优先级从大到小，数字越小优先级越高。
4.  [predicates](http://predicates/)：断言，配置哪个路径才转发，前端访问路径统一加上 XXX-server，网关判断转发对应的服务，如果是回调业务记得修改，固定 - Path=/xxx-server/\*\*。
5.  [filters](http://filters/): #过滤器，请求在传递过程中通过过滤器修改 - StripPrefix=1 #去掉第一层前缀，转发给后续的路径。

![](images/Fn8G-BxmpN3EjSAU-s8qTFrmb3Lp.png)

## **2.4 GateWay 网关配置访问多个微服务**

首先在访问之前需要解决一个重要问题就是「**跨域问题**」，前面我们在接入「**前端+后管服务**」时已经在 「**Common 包**」中加过「**跨域处理**」了。

原先配置如下，再来了解下什么是「**跨域问题**」和 「**CORS**」。

###   
**2.4.1 跨域问题**

做前后端分离项目肯定会遇到过跨域问题，先来看下什么是跨域。

### **2.4.1.1 什么是跨域**

我们先看下一个典型的网站的地址：

![](images/Fo8pKAQugGwpavHwhMqfLNH_N1dR.png)

**同源**是指：**协议、域名、端口号完全相同**。

下表给出了与 URL [http://www.training.com/dir/page.html](http://www.training.com/dir/page.html) 的源进行对比的示例 :

![](images/FsZFS18XGT_3mWewZvVWT9SPi4lh.png)

当用户通过浏览器访问应用（[http://admin.training.com](http://admin.training.com/)）时，调用接口的域名非同源域名([http://api.training.com](http://api.training.com/))，这是显而易见的跨域场景。

### **2.4.1.2 什么是 CORS**

**CORS** 是一个 W3C 标准，全称是"跨域资源共享"（Cross-origin resource sharing）, 它需要浏览器和服务器同时支持他，允许浏览器向跨源服务器发送XMLHttpRequest请求，从而克服 AJAX 只能**同源**使用的限制。

**跨域资源共享**标准新增了一组 HTTP 首部字段，允许服务器声明哪些源站通过浏览器有权限访问哪些资源。

规范要求，对那些可能对服务器数据产生副作用的 HTTP 请求方法（特别是 GET 以外的 HTTP 请求，或者搭配某些 MIME 类型的 POST 请求），浏览器必须首先使用 OPTIONS 方法发起一个预检请求（preflight request），从而获知服务端是否允许该跨域请求。

服务器确认允许之后，才发起实际的 HTTP 请求。在预检请求的返回中，服务器端也可以通知客户端，是否需要携带身份凭证（包括 Cookies 和 HTTP 认证相关数据）。

![](images/FrnzDZ_0rBPzk1npFfNI_1sJ8qSA.png)

### **2.4.1.2.1 简单请求**

简单请求模式，浏览器直接发送跨域请求，并在请求头中携带 Origin 的头，表明这是一个跨域的请求。 服务器端接到请求后，会根据自己的跨域规则，通过 Access-Control-Allow-Origin 和 Access-Control-Allow-Methods 响应头，来返回验证结果。

![](images/FhLXugeJq0v2apHcZgjHHSHPqOtJ.png)

### **2.4.1.2.2 预检请求**

浏览器在发现页面发出的请求非简单请求，并不会立即执行对应的请求代码，而是会触发预先请求模式。预先请求模式会先发送preflight request（预先验证请求），preflight request是一个 OPTION 请求，用于询问要被跨域访问的服务器，是否允许当前域名下的页面发送跨域的请求。在得到服务器的跨域授权后才能发送真正的 HTTP 请求。

![](images/FiObg6HQLAniyozVtiWwUMm9-Llm.png)

### **2.4.1.2.3 SpringBoot 后端配置**

SpringBoot 中新增一个配置类 [CorsConfig.java](http://corsconfig.java/) 实现 [WebMvcConfigurer](http://webmvcconfigurer%20/) 接口，项目启动后，会自动读取配置。

![](images/FhVzcKU5EF5HSuURaEKG2uEMB5xS.png)

另外需要在拦截器放行下 OPTIONS 请求，否则会报错，影响接口使用。

![](images/FpzASsuGYkYqTeR04uaElyEua01i.png)

### **2.4.2 网关跨域问题**

后续前后端服务都会接入「**网关服务**」，也就是所有的请求都会经过 「**网关服务**」，因此需要在「**网关服务**」配置「**跨域设置**」，然后屏蔽「**原来 Common 包**」下的 「**跨域设置**」。

###   
**2.4.2.1 网关跨域配置**

### ![](images/FuM2RQD8kikNewBe9-XIQGY14LlA.png)

### **2.4.2.2 屏蔽 Common 包跨域配置**

![](images/FgxQvyplXJqFqIoJ7gGjTwpCHVFG.png)

### **2.4.3 前端访问接入网关**

前端接入很简单，之前都已经抽离好：

![](images/FklWPEeNrhujHzI2KmNOfzmKrkRW.png)

![](images/FpIjVvLFodznPmCrboUFrUheXbb6.png)

![](images/FriAfb0lIY08GlIFqqFk-IyE4vV2.png)

![](images/FpPnWYpSL81ETW4Qp3qbDxe4x02d.png)

![](images/FhcxnWraOWKfqDyE9VJAX9N5AGsI.png)

![](images/FlGQVo8upz_upHF7oYHeDigOFS09.png)

![](images/FuFBx8Wo3ELMpg68ImipghB2_t-a.png)

![](images/FlgtmWeAFEfHUghXJCFpX_9emt2J.png)

![](images/FpNpYPWErDnGfeh8jegXOYpzaptJ.png)

![](images/FhNSvdJjdW1rZfoECnURv8Ez8glz.png)

![](images/FiGJ6JXkfvgvoTSJub4dafYRzwMp.png)

至此，整个「**微服务全家桶之网关服务 Gateway 的接入**」到此结束。