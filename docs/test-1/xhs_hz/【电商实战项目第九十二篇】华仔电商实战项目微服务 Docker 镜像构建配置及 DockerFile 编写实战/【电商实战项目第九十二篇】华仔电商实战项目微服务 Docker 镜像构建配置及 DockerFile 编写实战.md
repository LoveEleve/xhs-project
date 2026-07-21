从今天之后的一段时间内，华仔会带着大家一起从零开始搭建并研发一套高并发的电商实战项目，这里会涉及到很多互联网大厂开发过程中所使用的核心技术和架构设计模式，希望大家学完之后可以用到自己的简历中。

接下来我们会重点架构设计和开发一下我们高并发电商实战前端 Web 项目的相关功能 。

这是第九十二篇，本篇我们继续进行电商实战项目设计与开发，本篇我们来「**微服务 Docker 镜像构建配置及 DockerFile 编写实战**」。

文章汇总位置：[https://wx.zsxq.com/dweb2/index/columns/51122554151214](https://wx.zsxq.com/dweb2/index/columns/51122554151214)

![](images/Fnl1ueruAt4exbk9U2e5CcbjFHDI.png)

源码授权与获取地址：[https://articles.zsxq.com/id\_1s85grnaae4p.html](https://articles.zsxq.com/id_1s85grnaae4p.html)

![](images/FmkphgNM2yJBSqCq4raWXJA2r2Ah.png)

本章源码地址：[https://gitcode.net/u011359591/huazai-ecshop/-/tree/ecshop-chapter-92](https://gitcode.net/u011359591/huazai-ecshop/-/tree/ecshop-chapter-92)

## **01 前言**

上篇，我们重点进行了「**整合新版 Jenkins 打包实战**」，但是在最后构建打包时遇到了一些问题，经过多轮的测试，最终发现遗留了一个重要的事情，那就是「**微服务 Docker 镜像构建配置及 DockerFile 编写实战**」。

今天我们重点来进行实操下。

## **02 微服务 Docker 打包插件**

在微服务架构中，使用 Docker 将服务容器化是常见的部署方式。以下是几款常用的 **Docker 打包插件**及其特点，帮助开发者快速构建镜像。

  
![](images/lldoKYA-XqVWGBBVuBxEZ26bPBUN.png)

如图流程：微服务采⽤容器化部署 -> 本地推送镜像到镜像仓库 -> 容器云管理平台拉取部署。

##   
**2.1 SpringBoot 打包插件配置**

在聚合⼯程 pom 添加全局变量：

<!-- 指定 docker 打包镜像版本，后续部署用 -->

<docker.image.prefix>huazai-ecshop-cloud</docker.image.prefix>

![](images/FpFDXSiBGM7Rq_bN5pOjf93FsouB.png)

## **2.2 每个微服务添加相关打包插件**

这里以用户服务为例，其他微服务一致就行，在 project 内层添加即可：

<build>

<finalName>huazai-user</finalName>

<plugins>

<plugin>

<groupId>org.springframework.boot</groupId>

<artifactId>spring-boot-maven-plugin</artifactId>

<!--需要加这个，不然打包镜像找不到启动文件-->

<executions>

<execution>

<goals>

<goal>repackage</goal>

</goals>

</execution>

</executions>

<configuration>

<fork>true</fork>

<addResources>true</addResources>

</configuration>

</plugin>

<plugin>

<groupId>com.spotify</groupId>

<artifactId>dockerfile-maven-plugin</artifactId>

<version>1.4.13</version>

<configuration>

<repository>${docker.image.prefix}/${project.artifactId}</repository>

<buildArgs>

<JAR\_FILE>target/${project.build.finalName}.jar</JAR\_FILE>

</buildArgs>

</configuration>

</plugin>

</plugins>

</build>

![](images/FoPvzKqIxVzy8cF0gIpjwgGlewgC.png)

## **2.3 每个微服务添加 DockerFile**

这里还以用户服务为例，其他微服务自己查看 git 代码即可。

第一版 dockerfile 内容如下：

\# 多阶段构建：第一阶段构建 JAR 包 仅复制父 POM、common、huazai-user 模块 确保 Maven 的构建操作在容器内的 /app 目录下执行，避免路径混乱。

FROM maven:3.9.9 AS build

\# 动态生成 settings.xml

ARG MAVEN\_MIRROR\_URL=https://maven.aliyun.com/repository/public

\# 创建 .m2 目录并生成 settings.xml

RUN mkdir -p /root/.m2 && \\

echo "<settings><mirrors><mirror><id>aliyun</id><mirrorOf>\*</mirrorOf><url>${MAVEN\_MIRROR\_URL}</url></mirror></mirrors></settings>" > /root/.m2/settings.xml

\# 复制所有依赖到本地仓库

COPY docker-libs/com/jd/platform/hotkey /root/.m2/repository/com/jd/platform/hotkey

\# 在 COPY 后添加目录结构检查

RUN ls -lR /root/.m2/repository/com/jd/platform/hotkey

\# 设置 Maven 构建阶段的工作目录

WORKDIR /app

\# 将代码复制到此阶段的 /app 目录，仅复制父 POM 和当前模块代码

COPY pom.xml .

COPY huazai-user/pom.xml huazai-user/

COPY huazai-common/pom.xml huazai-common/

COPY huazai-user/src huazai-user/src/

COPY huazai-common/src huazai-common/src/

\# 分阶段构建

RUN mvn -B -N install -DskipTests && \\

cd huazai-common && mvn -B install -DskipTests && \\

cd ../huazai-user && mvn -B package -DskipTests \\

\# 第二阶段运行镜像 为 Java 应用运行提供独立的工作目录，确保后续操作（如复制 JAR 包、启动命令）路径正确。

FROM openjdk:17-jdk-slim

\# 设置 Java 运行阶段的工作目录

WORKDIR /app

\# 从构建阶段的 /app 目录复制 JAR 包

COPY --from=build /app/huazai-user/target/huazai-user.jar ./huazai-user.jar

\# 暴露容器内部端口

EXPOSE 9001

\# 健康检查

HEALTHCHECK --interval=30s --timeout\=3s \\

CMD curl -f http://localhost:9001/api/health/v1/health || exit 1

\# 启动命令

ENTRYPOINT \["java", "-jar", "huazai-user.jar"\]

另外在构建相关服务时，最好把其他的服务模块注释掉，否则会全部构建，占用空间很大。

![](images/FvO3LWyZgpAWUTrvMHti9wtO9Fj_.png)

否则会构建全部模块，耗时非常长，构建一次大概十几分钟，如下：

![](images/Fl6U2fAuFl-rgRRvN_tjzlb9asm-.png)

## **2.4 Jenkins 构建配置与测试**

### **2.4.1 Jenkins 后置 shell 构建配置**

上篇 [【电商实战项目第九十一篇】华仔电商实战项目整合新版 Jenkins 打包实战](https://articles.zsxq.com/id_angf9p0dmi8u.html) 中的后置 shell 配置的有些问题，这里做下修改：

#!/bin/bash

set -e -x

\# 生成 settings.xml

CUSTOM\_SETTINGS="${WORKSPACE}/settings.xml"

cat > "${CUSTOM\_SETTINGS}" <<EOF

<settings>

<mirrors>

<mirror>

<id>block-http</id>

<mirrorOf>nothing</mirrorOf>

<url>http://0.0.0.0/</url>

</mirror>

<mirror>

<id>aliyun</id>

<mirrorOf>external:\*</mirrorOf>

<url>https://maven.aliyun.com/repository/public</url>

</mirror>

</mirrors>

<profiles>

<profile>

<id>block-http</id>

<repositories>

<repository>

<id>block-http</id>

<url>http://0.0.0.0/</url>

<releases><enabled>false</enabled></releases>

<snapshots><enabled>false</enabled></snapshots>

</repository>

</repositories>

<pluginRepositories>

<pluginRepository>

<id>block-http</id>

<url>http://0.0.0.0/</url>

<releases><enabled>false</enabled></releases>

<snapshots><enabled>false</enabled></snapshots>

</pluginRepository>

</pluginRepositories>

</profile>

</profiles>

<activeProfiles>

<activeProfile>block-http</activeProfile>

</activeProfiles>

</settings>

EOF

\# ====================== 1. 准备 Docker 构建上下文 ======================

echo "📦 \[Step 1/3\] 准备 Docker 上下文..."

\# 专用构建上下文目录

DOCKER\_CONTEXT="${WORKSPACE}/docker-context"

\# 清理旧上下文

rm -rf "${DOCKER\_CONTEXT}"

mkdir -p "${DOCKER\_CONTEXT}"

echo "当前工作目录: $(pwd) WORKSPACE 路径: ${WORKSPACE} 检查项目根目录内容:"

ls -al "${WORKSPACE}"

echo "检查 huazai-user 目录内容:"

ls -al "${WORKSPACE}/huazai-user"

\# 复制项目代码

cp -r "${WORKSPACE}/huazai-user" "${DOCKER\_CONTEXT}/"

cp "${WORKSPACE}/pom.xml" "${DOCKER\_CONTEXT}/"

cp -r "${WORKSPACE}/huazai-common" "${DOCKER\_CONTEXT}/"

\# ====================== 2. 复制 hotkey 依赖到 Docker 构建上下文 ======================

echo "📦 \[Step 2/3\] 复制 hotkey 依赖到 Docker 构建上下文..."

mkdir -p "${DOCKER\_CONTEXT}/docker-libs/com/jd/platform/hotkey"

\# 从本地仓库复制到构建上下文

cp -r "${HOME}/.m2/repository/com/jd/platform/hotkey/hotkey" \\

"${DOCKER\_CONTEXT}/docker-libs/com/jd/platform/hotkey/"

cp -r "${HOME}/.m2/repository/com/jd/platform/hotkey/hotkey-client" \\

"${DOCKER\_CONTEXT}/docker-libs/com/jd/platform/hotkey/"

cp -r "${HOME}/.m2/repository/com/jd/platform/hotkey/hotkey-common" \\

"${DOCKER\_CONTEXT}/docker-libs/com/jd/platform/hotkey/"

\# ====================== 3. 构建并推送镜像 ======================

echo "🚀 \[Step 3/3\] 构建用户服务 Docker 镜像开始..."

\# 进入用户服务目录（仅为后续操作）

cd "${DOCKER\_CONTEXT}"

\# 构建 Docker 镜像

docker build \\

\-f huazai-user/Dockerfile \\

\--build-arg MAVEN\_MIRROR\_URL="https://maven.aliyun.com/repository/public" \\

\-t huazai-ecshop/huazai-user:latest \\

. # 关键点：上下文设为 docker-context 目录

#"${WORKSPACE}"

\# 标记镜像并推送

docker login --username=tb907859\_2012 crpi-lv6l3g60ipeedbnw.cn-shenzhen.personal.cr.aliyuncs.com

docker tag huazai-ecshop/huazai-user:latest crpi-lv6l3g60ipeedbnw.cn-shenzhen.personal.cr.aliyuncs.com/huazai-ecshop/huazai-user:v1.1

docker push crpi-lv6l3g60ipeedbnw.cn-shenzhen.personal.cr.aliyuncs.com/huazai-ecshop/huazai-user:v1.1

\# 仅清理当前模块的构建产物，保留源码和依赖

if \[ -d "${WORKSPACE}/huazai-user/target" \]; then

echo "🧹 清理 huazai-user 构建产物..."

\# 清理（仅清理当前模块）

mvn clean -f "${WORKSPACE}/huazai-user/pom.xml"

rm -rf "${WORKSPACE}/huazai-user/target"

fi

echo "🎉 用户服务构建推送成功"

echo "🎉 =======构建脚本执⾏完毕===== 耗时：${SECONDS}秒"

![](images/Fs3HYnYEQSFl9i7KGEHekUn9TAID.png)

### **2.4.2 Jenkins 构建测试**

![](images/FtGUDKlwEeofVobKcwcWHyDm6SNc.png)

构建日志太长了，这里截取部分截图：

![](images/FkJ6k8bSa9M8D_ATMDnuJCQVGFGJ.png)

![](images/FtwZsz4h_ISxEOrCrkkLrbDWBAn4.png)

![](images/FtcfWR7MinbZAgY6AT33YYyV0aHv.png)

构建后的模块如下：

![](images/Fmpm2qgXOdGzOcku7O8JJ8lyjsB3.png)

![](images/FgJxMZp73IzMpnG2Q4SxtbOZrZTM.png)

![](images/FpyTHGwO8E90Ty9_GZ1ulkAhhX_0.png)

![](images/FjBvy0M0-bhIDc6JhOmX1kmRQ_Kh.png)

发现最终构建失败：

![](images/FuXz_Yyh1JoeYYwTU3HdVpUmeHu0.png)

这里有点粗心了，应该去掉最后的这个斜杠，再次尝试：

![](images/FuBTblCb17_BwSPTGX6r10BRzkrn.png)

![](images/FirA6V-iOPfIR8kuoKmKm8vM2E8E.png)

通过检查后置 shell 发现 docker 登录时忘记写密码了，调整后再次测试：

![](images/FqjaB5_baFoK9ja9tzlsPK2daNA5.png)

修改如下：

![](images/Fg20S_-4U_WMRL8UNGXO8e0yNdoF.png)

再次执行构建，最终打包并推送成功：

![](images/FuIxRlipRPmX9qSPn8GidnxTqsqP.png)

查看阿里云后台镜像版本：

地址：[https://cr.console.aliyun.com/repository/cn-shenzhen/huazai-ecshop/huazai-user/images](https://cr.console.aliyun.com/repository/cn-shenzhen/huazai-ecshop/huazai-user/images)

![](images/FhJS1kYdOpqAzLXVCbFNwMBqPhI1.png)

具体的脚本内容可以直接在本篇 git 代码获取即可：

![](images/FtVhiBOsQMWnPeERvKV4RMr_qxr5.png)

具体的 DockerFile 在每个微服务根目录下：

![](images/FjA6KYrtvPj97XH7-qm1N78LvOTC.png)

### **2.4.3 其他微服务构建打包推送镜像**

### **2.4.3.1 首页服务构建打包推送镜像**

1、首先，需要修改下 huazao-ecshop的 pom.xml 文件去掉除 common、home 之外的其他模块。每个微服务打包时最好都这么操作，否则会全部打包，非常耗时。

![](images/FkEW7SeSzyjy2cU3tgFBCIhe0QLg.png)

2、修改 Dockerfile

![](images/Ft8m_d9Bf8fDzn9gIM2loZAph_44.png)

3、创建 Jenkins 服务任务

![](images/FtHLTD3RLVtcGPaF0xIw9QWf7tHx.png)

4、执行 Jenkins 构建结果

![](images/FmMqQhfb8Xvl7bHs56xfK1klrEt_.png)

5、查看阿里云镜像版本

![](images/FiJqiLp01FzobNfLcBZG6T6epl8P.png)

### **2.4.3.2 美食服务构建打包推送镜像**

1、同样需要修改下 huazao-ecshop 的 pom.xml 文件去掉除 common、food 之外的其他模块。每个微服务打包时最好都这么操作，否则会全部打包，非常耗时。

![](images/Fhvk0X24r8EWppoBALbhFB3aqnYr.png)

2、修改 Dockerfile

![](images/FnmnS05cNBJtsE0XOpIZ46gQCqD7.png)

3、后续可以通过以下方式来快速创建 Jenkins 服务任务，然后将对应的服务改为当前服务即可，比如将 huazai-home 改为 huazai-food。

![](images/FnPXtVx3_wT3jH5Gbjk81MZroDUj.png)

4、执行 Jenkins 构建结果

![](images/ForuHV9qjtDHIl9ur-bD8L-8scms.png)

5、查看阿里云镜像版本

![](images/Fl87tcVM_IuTAUGIHK3hcO9lbNNk.png)

### **2.4.3.3 社交服务构建打包推送镜像**

1、同样需要修改下 huazao-ecshop 的 pom.xml 文件去掉除 common、social 之外的其他模块。每个微服务打包时最好都这么操作，否则会全部打包，非常耗时。

![](images/Fn9DfDEb2RGOSbEr3MbWJVTi8RAD.png)

2、修改 Dockerfile

![](images/FsPimcPRi4JzvzUUX7hWTYdLbIJw.png)

3、后续可以通过以下方式来快速创建 Jenkins 服务任务，然后将对应的服务改为当前服务即可，比如将 huazai-home 改为 huazai-social。

![](images/FtQtjteSAL42bH_I4xea27PMaCq-.png)

4、执行 Jenkins 构建结果

![](images/FjCaVwmbEDHKsmDL01pakyhyQJ5o.png)

5、查看阿里云镜像版本

![](images/Fic_AsC_TUzf-4T_sgvvOELPgiAA.png)

### **2.4.3.4 购物车服务构建打包推送镜像**

1、同样需要修改下 huazao-ecshop 的 pom.xml 文件去掉除 common、cart 之外的其他模块。每个微服务打包时最好都这么操作，否则会全部打包，非常耗时。

![](images/FiiLeZCFrIIb6vi7g6WuzquxBSld.png)

2、修改 Dockerfile

![](images/FpjTfzQpEOcTYnugKxm9QVH9twsF.png)

3、后续可以通过以下方式来快速创建 Jenkins 服务任务，然后将对应的服务改为当前服务即可，比如将 huazai-home 改为 huazai-cart。

![](images/FjK4kxedmyUSvUJKf-DjdecoqMiV.png)

4、执行 Jenkins 构建结果

![](images/FgzZLcu0vHWFjKZLBpmBcy3DYuiM.png)

5、查看阿里云镜像版本

![](images/FjYfGqEJnvYNihyAONBhs-xC1_eM.png)

### **2.4.3.5 商品服务构建打包推送镜像**

1、同样需要修改下 huazao-ecshop 的 pom.xml 文件去掉除 common、product 之外的其他模块。每个微服务打包时最好都这么操作，否则会全部打包，非常耗时。

![](images/Fi_ftwu676YtZuIT5OFy1GsANCOV.png)

2、修改 Dockerfile

![](images/FimVGheUHeszGtmpJMBGMKGqklhK.png)

3、后续可以通过以下方式来快速创建 Jenkins 服务任务，然后将对应的服务改为当前服务即可，比如将 huazai-home 改为 huazai-product。

![](images/FpNfggkFp1WQ_DBnAYr4PHMQRQKx.png)

4、执行 Jenkins 构建结果

![](images/FnPbLaSYlTjkZjN2KYdRulk6rbt2.png)

5、查看阿里云镜像版本

![](images/FlPm7QquSBUvWsU1WWtYTIAcAbox.png)

### **2.4.3.6 IM 服务构建打包推送镜像**

1、同样需要修改下 huazao-ecshop 的 pom.xml 文件去掉除 common、im 之外的其他模块。每个微服务打包时最好都这么操作，否则会全部打包，非常耗时。

![](images/FkAVuhK25IoFg1WAKo0JOkAwcfDZ.png)

2、修改 Dockerfile

![](images/FjgR1GIhatWL0sQhHHQwUdhMhFre.png)

3、后续可以通过以下方式来快速创建 Jenkins 服务任务，然后将对应的服务改为当前服务即可，比如将 huazai-home 改为 huazai-im。

![](images/Fh00_f-X_7o-ik_LmX-8fwovzmho.png)

4、执行 Jenkins 构建结果

![](images/FjhPNaFGfl6CaTN5YMYbxVFg-uVN.png)

5、查看阿里云镜像版本

![](images/FtWuTl49vre88GtpWM72dJ0RolL1.png)

### **2.4.3.7 优惠券服务构建打包推送镜像**

1、同样需要修改下 huazao-ecshop 的 pom.xml 文件去掉除 common、coupon 之外的其他模块。每个微服务打包时最好都这么操作，否则会全部打包，非常耗时。

![](images/Fv3fWR5ya0A3KNKq1aL1Dh66_Bnn.png)

2、修改 Dockerfile

![](images/Fg9Xve-IAcWLrOJJttFq0sLeP_tS.png)

3、后续可以通过以下方式来快速创建 Jenkins 服务任务，然后将对应的服务改为当前服务即可，比如将 huazai-home 改为 huazai-coupon。

![](images/Fp0Bjzg9cdlEQIAzSws2roj7hcjM.png)

4、执行 Jenkins 构建结果

![](images/FsTaPLmf2HY1k54o0CALJpuoRjXp.png)

5、查看阿里云镜像版本

![](images/FhcmO_aPe9AWuAQv5upeXB9fQfDH.png)

### **2.4.3.8 库存服务构建打包推送镜像**

1、同样需要修改下 huazao-ecshop 的 pom.xml 文件去掉除 common、inventory 之外的其他模块。每个微服务打包时最好都这么操作，否则会全部打包，非常耗时。

![](images/FnltdnrAnIgHv8-g8gBhFMS-p9UN.png)

2、修改 Dockerfile

![](images/FvoAn05opZZ3WukCnWGL15-9ospQ.png)

3、后续可以通过以下方式来快速创建 Jenkins 服务任务，然后将对应的服务改为当前服务即可，比如将 huazai-home 改为 huazai-inventory。

![](images/Fp1xtm2jfX-2WhpIkK0EKbw1SCEC.png)

4、执行 Jenkins 构建结果

![](images/Fl5zfHPHokOayqaHy6GtrKaQCVg1.png)

5、查看阿里云镜像版本

![](images/FgB9oygpyalFL40QGN880_E3Rkzo.png)

### **2.4.3.9 订单服务构建打包推送镜像**

1、同样需要修改下 huazao-ecshop 的 pom.xml 文件去掉除 common、order 之外的其他模块。每个微服务打包时最好都这么操作，否则会全部打包，非常耗时。

![](images/FuFCbnOjrnv-FdRd2FjEalAKMoIA.png)

2、修改 Dockerfile

![](images/Fgi2iqzTNEYGurdwshpsPeQrVcv2.png)

3、后续可以通过以下方式来快速创建 Jenkins 服务任务，然后将对应的服务改为当前服务即可，比如将 huazai-home 改为 huazai-order。

![](images/Fp_WX7EDjxyIl-xltQt7qJC6XJon.png)

4、执行 Jenkins 构建结果

![](images/Fq_vwiddg5RSU9rKzeqZTw472JzQ.png)

5、查看阿里云镜像版本

![](images/FgoHJH2SUU5-jbV_tp0kv3jgK9G0.png)

### **2.4.3.10 支付服务构建打包推送镜像**

1、同样需要修改下 huazao-ecshop 的 pom.xml 文件去掉除 common、pay 之外的其他模块。每个微服务打包时最好都这么操作，否则会全部打包，非常耗时。

![](images/Foi-nxf9gQKg_RAzDlCezAyFAKzX.png)

2、修改 Dockerfile

![](images/FocGDlVmmY1MUOaQdf-G2PIvrllx.png)

3、后续可以通过以下方式来快速创建 Jenkins 服务任务，然后将对应的服务改为当前服务即可，比如将 huazai-home 改为 huazai-pay。

![](images/FgZzCfOXqzBmk0XmezjfqcZZT3m8.png)

4、执行 Jenkins 构建结果

![](images/FqYiioysMVLiNOl_0ElTqPOdRZVs.png)

5、查看阿里云镜像版本

![](images/FhGd9X-SH_bYBvGczI0gXriq_v4Y.png)

### **2.4.3.11 后管服务构建打包推送镜像**

1、同样需要修改下 huazao-ecshop 的 pom.xml 文件去掉除 common、admin之外的其他模块。每个微服务打包时最好都这么操作，否则会全部打包，非常耗时。

![](images/FhoCgdm_NhMCxxfRFYOepid-1WYF.png)

2、修改 Dockerfile

![](images/FlludR8KkoT0bQmv095gS8s_9Pym.png)

3、后续可以通过以下方式来快速创建 Jenkins 服务任务，然后将对应的服务改为当前服务即可，比如将 huazai-home 改为 huazai-admin。

![](images/FpvSViljHYgcyJPXZBjFfkFWX9Hc.png)

4、执行 Jenkins 构建结果

![](images/Fo_UMs3vwaYU5F-Qxp60M0t5avJ9.png)

5、查看阿里云镜像版本

![](images/FgSTcffA74ku04eqhSP2TR0JPji-.png)

### **2.4.3.12 推送服务构建打包推送镜像**

1、同样需要修改下 huazao-ecshop 的 pom.xml 文件去掉除 common、push 之外的其他模块。每个微服务打包时最好都这么操作，否则会全部打包，非常耗时。

![](images/FnFzpo1LbVhWWDCzlgjHn6RLRkZW.png)

2、修改 Dockerfile

![](images/Fn4HQczJRTu2cc_XCKJ1U-F_lqwu.png)

3、后续可以通过以下方式来快速创建 Jenkins 服务任务，然后将对应的服务改为当前服务即可，比如将 huazai-home 改为 huazai-push。

![](images/Fijc6ETnG_938CVCm_LYKK_Fd_ro.png)

4、执行 Jenkins 构建结果

![](images/FgjdUaQn2RiRzVY4Knn1vMWM2dau.png)

5、查看阿里云镜像版本

![](images/Ftw5P3fq3ta0iRdBS2fRY21FmeOI.png)

### **2.4.3.13 网关服务构建打包推送镜像**

1、同样需要修改下 huazao-ecshop 的 pom.xml 文件去掉除 gateway 之外的其他模块。每个微服务打包时最好都这么操作，否则会全部打包，非常耗时。

![](images/Fn8ZEejpnu42NOk73pHxiP5usjQE.png)

2、修改 Dockerfile

![](images/FkMqIZAzKsJ5AFmqFLOLxI0hB2bz.png)

3、后续可以通过以下方式来快速创建 Jenkins 服务任务，然后将对应的服务改为当前服务即可，比如将 huazai-home 改为 huazai-gateway。

![](images/FqaDb2rZ7ri_yJOh1BwyMPUDOcSm.png)

4、执行 Jenkins 构建结果

![](images/FrfJFrcC7B77MlWWhCyR2g_Pl-nz.png)

5、查看阿里云镜像版本

### ![](images/FtwGs7zU4sDLrJpdghg8vq3BdXlJ.png)

### **2.4.3.14 微服务汇总**

![](images/Fr3osi4k_9V5rJLwLwF7Srk90C4X.png)

### ![](images/Fspag2ZMQppqpk5eBw99lT7HLRl8.png)

### **2.4.3.14 jenkins 构建脚本优化**

### **1、脚本优化深层清理**

![](images/FsuKWlnuIIFUkGFJsAg7-ByYFZ3H.png)

目前每个服务只清理了当前服务的 pom.xml 和 target

![](images/Ft4zbidXgFMcuKF8LxBMOkInbRcY.png)

修改清理空间：

![](images/FtG_WE5nKMi9r9jC4_1fbLi71zXK.png)

### **2、空间深层清理**

**2.1、Jenkins docker 空间深层清理**

现象：删除 [/home/wangjianghua1/docker/jenkins/workspace](http://home/wangjianghua1/docker/jenkins/workspace) 后宿主机空间未释放。

根本原因：Docker 容器内删除文件后，宿主机不会立即释放空间（需要停止容器或使用特殊方法）

解决方案：彻底清理容器内已删除文件的空间。

![](images/FqvwZxzHeBQpRrZwejKNKCJMivkG.png)

wangjianghua1@LAPTOP-3B77RHGG:~/docker/jenkins$ sudo docker ps -a

\[sudo\] password for wangjianghua1:

CONTAINER ID IMAGE COMMAND CREATED STATUS PORTS NAMES

fb374894d55e rancher/rke-tools:v0.1.80 "/docker-entrypoint.…" 18 hours ago Created rke-etcd-port-listener

fe0a1d909497 rancher/mirrored-coreos-etcd:v3.4.15-rancher1 "/usr/local/bin/etcd…" 18 hours ago Up 3 hours etcd

138ffc687e01 rancher/rancher-agent:v2.5.16 "run.sh --server htt…" 18 hours ago Up 3 hours adoring\_leakey

8a7275ed6385 rancher/rancher:v2.5.16 "entrypoint.sh" 23 hours ago Up 3 hours 0.0.0.0:80->80/tcp, \[::\]:80->80/tcp, 0.0.0.0:443->443/tcp, \[::\]:443->443/tcp huazai\_rancher1

f0d4bd4b0e7e jenkins/jenkins:2.479.1-lts-jdk17 "/usr/bin/tini -- /u…" 30 hours ago Up 29 minutes 50000/tcp, 0.0.0.0:9302->8080/tcp, \[::\]:9302->8080/tcp huazai\_ecshop\_jenkins

wangjianghua1@LAPTOP-3B77RHGG:~/docker/jenkins$ sudo docker exec -it f0d4bd4b0e7e /bin/bash

root@f0d4bd4b0e7e:/#

root@f0d4bd4b0e7e:/#

root@f0d4bd4b0e7e:/# cd /var/jenkins\_home/workspace

root@f0d4bd4b0e7e:/var/jenkins\_home/workspace# dd if=/dev/zero of=zero.fill bs=1M count=1024

1024+0 records in

1024+0 records out

1073741824 bytes (1.1 GB, 1.0 GiB) copied, 13.2382 s, 81.1 MB/s

root@f0d4bd4b0e7e:/var/jenkins\_home/workspace# rm -f zero.fill

root@f0d4bd4b0e7e:/var/jenkins\_home/workspace# exit

exit

wangjianghua1@LAPTOP-3B77RHGG:~/docker/jenkins$

wangjianghua1@LAPTOP-3B77RHGG:~/docker/jenkins$ sudo systemctl restart docker

### **2.2、Jenkins 持久化数据目录清理**

需要清理的关键目录:

/home/wangjianghua1/docker/jenkins/

├── jobs/ # 任务配置和构建记录

├── workspace/ # 工作空间（已处理）

├── caches/ # 插件缓存

├── fingerprints/ # 文件指纹记录

└── tools/ # 自动下载的JDK/Maven等

手动清理步骤：

\# 进入宿主机挂载目录

cd /home/wangjianghua1/docker/jenkins

\# 清理旧构建记录（保留最近10个）

find jobs/ -name builds -type d -exec bash -c "ls -dt {}/\* | tail -n +11 | xargs rm -rf" \\;

\# 清理工具缓存

rm -rf tools/hudson.model.JDK/\*\_latest tools/apache-maven-3.\*.zip

\# 清理插件缓存

rm -rf plugins/\*.jpi plugins/\*.hpi plugins/archived\_pins.txt

\# 清理工作空间

rm -rf workspace/\*

### **2.3、Docker 级清理（/var/lib/docker）**

关键目录说明：

1.  overlay2：容器文件系统层
2.  volumes：卷数据
3.  buildkit：构建缓存

清理方案：

![](images/FlXiYt7MfKWLS_7OrWzl4TFUhN-r.png)

\# 查看 Docker 磁盘使用情况

sudo docker system df

\# 执行全面清理（危险！会删除所有未使用的资源）

sudo docker system prune --all --volumes --force

\# 针对性清理 overlay2

\# 1. 停止 Docker 服务

sudo systemctl stop docker

\# 2. 手动清理残留层（需先确认无在用容器）

rm -rf /var/lib/docker/overlay2/\*

\# 3. 重启 Docker

sudo systemctl start docker

通过 sudo docker system prune --all --volumes --force 执行了以下清理：

1.  删除容器：1 个停止状态的容器
2.  删除卷：8 个未被使用的 Docker 卷
3.  删除镜像：多个未使用的镜像（包括 Rancher 相关镜像）
4.  空间回收：15.43GB 已释放回宿主机

表示此次清理完全成功，且回收空间量合理。

清理前：

![](images/FmflQaW-CMz8TOd3Yh0siLRvdInD.png)

清理后：

### ![](images/FspUXjsEkxne2VvKq0nNvV2Nu1f3.png)

### **2.4、宿主机清理**

#### **2.4.1 手动压缩 WSL2 虚拟磁盘**

我本地使用的是 wsl ubuntu，如果你也是的话，可以直接使用下面的方式来释放空间。

![](images/FgSp6mtSXDXaFpk-zmTVHGls5QMI.png)

\# 以管理员身份打开 PowerShell

wsl --shutdown # 关闭所有 WSL 实例

\# 找到你的 WSL2 发行版名称（例如 Ubuntu）

wsl -l -v

\# 优化虚拟磁盘（替换为你的发行版名称）

diskpart

\# 此时会弹窗，在弹窗挨个输入以下内容

select vdisk file="C:\\Users\\meng\_\\AppData\\Local\\Packages\\CanonicalGroupLimited.Ubuntu24.04LTS\_79rhkp1fndgsc\\LocalState\\ext4.vhdx"

attach vdisk readonly

compact vdisk

detach vdisk

exit

### **2.4.2 验证压缩结果**

\# 查看压缩后的虚拟磁盘大小

Get-ChildItem -Path "C:\\Users\\meng\_\\AppData\\Local\\Packages\\\*" -Recurse -Filter \*.vhdx |

Select-Object FullName, @{Name="SizeGB";Expression={\[math\]::Round($\_.Length /1GB, 2)}}

![](images/FoR4roUtLtUJfLfOSFATmIOXWXth.png)

### **2.4.3 配置自动压缩**

\# 创建定时任务（每月1号自动压缩）

$Action = New-ScheduledTaskAction -Execute 'diskpart.exe' -Argument '/s C:\\Scripts\\wsl\_compact.txt'

$Trigger = New-ScheduledTaskTrigger -Monthly -Days 1

Register-ScheduledTask -TaskName "WSL Disk Compact" -Action $Action -Trigger $Trigger -User "SYSTEM"

\# 在 C:\\Scripts\\wsl\_compact.txt 中写入：

select vdisk file="C:\\你的路径\\ext4.vhdx"

attach vdisk readonly

compact vdisk

detach vdisk

exit

### **2.4.4 关键优化(预防空间膨胀)**

### **2.4.4.1 限制 WSL2 最大磁盘占用**

\# powershell 执行

\# 在 %UserProfile%\\.wslconfig 文件中添加：

\[wsl2\]

memory=4GB

processors=2

localhostForwarding=true

disk=50GB # 限制虚拟磁盘最大 50GB

### **2.4.4.2 修改 docker 数据存储路径**

\# powershell 执行

\# 将 Docker 数据迁移到其他分区（例如 D 盘）

wsl --export docker-desktop-data D:\\wsl\\docker-data.tar

wsl --unregister docker-desktop-data

wsl --import docker-desktop-data D:\\wsl\\data D:\\wsl\\docker-data.tar --version 2

### **2.4.4.3 清理 WSL2 系统缓存**

\# 在 Linux 子系统中执行

sudo apt clean

sudo rm -rf /var/lib/apt/lists/\*

sudo journalctl --vacuum-time=7d

\# powershell 验证

\# 查看 Windows 的磁盘空间变化

Get-Volume -DriveLetter C | Select-Object SizeRemaining

\# 在 Linux 子系统中执行

\# 在 WSL2 中查看 Docker 存储使用

sudo docker system df