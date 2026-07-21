从今天之后的一段时间内，华仔会带着大家一起从零开始搭建并研发一套高并发的电商实战项目，这里会涉及到很多互联网大厂开发过程中所使用的核心技术和架构设计模式，希望大家学完之后可以用到自己的简历中。

接下来我们会重点架构设计和开发一下我们高并发电商实战前端 Web 项目的相关功能 。

这是第九十一篇，本篇我们继续进行电商实战项目设计与开发，本篇我们来「**整合新版 Jenkins 打包实战**」。

文章汇总位置：[https://wx.zsxq.com/dweb2/index/columns/51122554151214](https://wx.zsxq.com/dweb2/index/columns/51122554151214)

![](images/Fnl1ueruAt4exbk9U2e5CcbjFHDI.png)

源码授权与获取地址：[https://articles.zsxq.com/id\_1s85grnaae4p.html](https://articles.zsxq.com/id_1s85grnaae4p.html)

![](images/FmkphgNM2yJBSqCq4raWXJA2r2Ah.png)

本章源码地址：[https://gitcode.net/u011359591/huazai-ecshop/-/tree/ecshop-chapter-91](https://gitcode.net/u011359591/huazai-ecshop/-/tree/ecshop-chapter-91)

## **01 前言**

上篇，我们「**介绍一线大厂的一站式 DevOps 平台与 Rancher 2.x 容器编排管理平台部署**」 实操，今天我们来重点「**整合新版 Jenkins 打包实战**」。

##   
**02 Jenkins 实战**

## **2.1 什么是 Jenkins**

是⼀个开源的、提供友好操作界⾯的持续集成(CI)⼯具，主要⽤于持续、⾃动的构建/测试软件项⽬、监控外部任务的运⾏，⽤ Java 语⾔编写，可在 Tomcat 等流⾏的 servlet 容器中运⾏，也可独⽴运⾏。

其官网地址：[https://www.jenkins.io/](https://www.jenkins.io/)，文档地址：[https://www.jenkins.io/doc/book/getting-started/](https://www.jenkins.io/doc/book/getting-started/)

在使⽤ Jenkins ⾃动化部署之前，⾸先安装 Docker 容器，我们在上篇中已经安装过了。另外要保证机器的配置要⾼！生产环境不建议使⽤虚拟机，不然卡或者缺少类库东⻄则麻烦。

服务器部署带宽⼀定要⾼，不然推送镜像时间等待漫⻓，如果本地安装麻烦，可以使⽤阿⾥云按量付费⽅式进⾏购买云服务器。

## **2.2 安装 Jenkins**

### **2.2.1 创建 Jenkins 持久化目录**

mkdir -p /home/wangjianghua1/docker/jenkins

### **2.2.2 运行部署 Jenkins 容器**

sudo docker run -d \\

\-u root \\

\--name huazai\_ecshop\_jenkins \\

\--restart unless-stopped \\

\-p 9302:8080 \\

\-v /home/wangjianghua1/docker/jenkins:/var/jenkins\_home \\

\-v /var/run/docker.sock:/var/run/docker.sock \\

\-v /usr/bin/docker:/usr/bin/docker \\

jenkins/jenkins:2.479.1-lts-jdk17

解释：

1.  第⼀⾏：表示将该容器在后台运⾏。
2.  第⼆⾏：表示使⽤root⽤户来运⾏容器。
3.  第三⾏：表示给这个容器命名，后⾯可以通过这个名字来管理容器。
4.  第四行：自动重启，除非用户手动停止容器（生产环境推荐）, 通过 \--restart unless-stopped 参数与 Docker 服务自启配置，可确保宿主机异常关闭后 Jenkins 容器自动恢复运行
5.  第五⾏：表示将主机的9302端⼝映射到8080端⼝上，后⾯就可以通过主机ip:9302来访问Jenkins，根据⾃⾏需要端⼝是可以更改的。
6.  第六⾏：表示将本地/home/wangjianghua1/docker/jenkins⽬录映射为/var/jenkins\_home⽬录，这就是第⼆步中的持久化⽬录。
7.  第七、八⾏：表示把本地/var/run/docker.sock⽂件映射在容器中/var/run/docker.sock⽂件。这⼀步的⽬的就是为了把容器中的Jenkins可以与主机Docker进⾏通讯。
8.  第九⾏：指定使⽤哪⼀个镜像和标签。

![](images/FnbaDLo_0O5AvFajtBJcqipjKwZe.png)

通过查看「**docker**」和「**端口**」情况：

sudo docker ps -a | grep jenkins

sudo lsof -i :9302

![](images/FnsACdkn41g9Ik4o2OkiJiIoyYW5.png)

如果部署在云上，需要「**开放网络安全组对应端口**」，否则无法访问。

### **2.2.3 访问 Jenkins**

在浏览器输⼊ip+端⼝号，我这里的地址是：[http://172.20.8.220:9302/login?from=%2F](http://172.20.8.220:9302/login?from=%2F) 即

可进⼊到Jenkins登录⻚⾯。

这里需要先获取登录 Jenkins 的密码, 把获取的密码复制上去。

![](images/FlmBPo_OV6ByLI8SsJAn18YEf6CN.png)

我们来查看下：

\# 前面在安装时已经将容器的路径映射到这个位置

sudo cat /home/wangjianghua1/docker/jenkins/secrets/initialAdminPassword

![](images/Fl0-okzLphhW0AiNH3yco7x7yYTI.png)

### **2.2.4 安装推荐插件**

![](images/Fo1z_xl-7ENhanyMAmCKzHNG_kqm.png)

这里就按默认的「**安装推荐插件**」即可，等待 Jenkins 把插件安装好即可，安装好后进⼊实例配置⻚⾯，点保存就可以来到Jenkins主界⾯了， 如果本地安装的话大概需要1-2分钟左右吧 。

![](images/FiWsa4gZxxM2Kk4WPBexvrWqWzwV.gif)

等待安装完毕后，进入创建管理用户界面：

![](images/Fqwqy__9l_VQXJeCoZJuyrT0ERoo.png)

![](images/FqXrLfntaPvPNP1XQXUM4qF2YIiT.png)

直接保存即可，然后就会进入到 Jenkins 界面：

![](images/FsBtgcHMJYKqkmbtK1J1aFY2AqGR.png)

### **2.2.5 全局配置设置 JDK**

![](images/FhX-0W0quDvlrozdT5pfRmUemShm.png)

如果感兴趣也可以自动升级到最新版本，这里我就不操作了，最新版本的 jdk 是 21 的。

容器内部配置JDK 路径为主机 Jenkins 容器内部⾥的 JAVA\_HOME，也就是 echo $JAVA\_HOME 查看 JAVA\_HOME路径：

/opt/java/openjdk

![](images/FrwGf12AgRqxxSVCHa9ASbAiUmXu.png)

这里我们主要设置三个选项：

1.  JDK
2.  Git
3.  Maven

然后先点击「**应用**」，再点击 「**保存**」：

![](images/FuDduoq7fa6-ejIwzOHhbbfsS9i2.png)

### **2.2.6 插件页面下载插件**

![](images/FkFIbuwLN95mDPXrvfo1CGySTzlV.png)

![](images/FkSb-hTD4EJqWW6-ZnnjXDxx_r-G.png)

我们需要下载 Maven Integration、docker Pipeline、docker API 、docker、docker commons 这几个插件：

![](images/FitoRYXVX7wirq3DIA8bDBAXEsH1.png)

等待安装完成即可：

![](images/FnEXPMWmAPxpHL2QGF2GqvdfxcqA.gif)

### **2.2.7 Jenkins 配置 Git 全局访问凭证**

我们项目的 git 地址：[https://gitcode.net/u011359591/huazai-ecshop](https://gitcode.net/u011359591/huazai-ecshop)

开发、构建、部署流程图如下：

![](images/lldoKYA-XqVWGBBVuBxEZ26bPBUN.png)

接下来进行配置，首先还是进入 Manage Jenkins，找到「**凭证管理**」：

![](images/FmHLzC8atbupdeFVsSSbEQ3Yi_tZ.png)

![](images/FhmNyDExzUaYubZhBpEG9EI6Z-7j.png)

点击 System 进入：

![](images/Fm3D1TVGDfm9FidSMcVns_2j126B.png)

![](images/FrP-MKvU342JLs0v-e8esOXbz27c.png)

![](images/FkNQc-0BHqSSHGM7xKDzyUjLcoeo.png)

点击创建就生成了一个全局凭证：

![](images/FrsD0O7W7kgCc5pUdg--D2CmBGNE.png)

### **2.2.8 Jenkins 构建小红书平台微服务脚本编写**

这里需要每个微服务建⽴⼀个 Item 配置，我们分别来操作下。

![](images/Fl1omv_DViYS5OnNKaD7MwXesALR.png)

这里我们以「**用户服务**」为例来演示：

  
![](images/FiH4hy4dHl5ps2itvriL5xb1eTP6.png)

### **2.2.8.1 步骤一：添加 git 仓库配置**

![](images/FsVcOZG3PA5Zlt7yONvb3N0hHZQ9.png)

### **2.2.8.2 步骤二：添加前置步骤，构建 Common 依赖模块**

![](images/FvAPqJ0aMutryxjDrkhImMTU2Sld.png)

这里说明下，涉及到上篇中的 [【电商实战项目第九十篇】华仔电商实战项目一站式 DevOps 平台介绍与 Rancher 2.x 容器编排管理平台部署](https://articles.zsxq.com/id_d6f2mxwiucl0.html)

  
![](images/FqDIy88h90_sYCXFlukRk3Wui-_U.png)

阿里云访问凭证地址：[https://cr.console.aliyun.com/cn-shenzhen/instance/credentials](https://cr.console.aliyun.com/cn-shenzhen/instance/credentials)

![](images/Ftxh9K_DU_Rdxt9aJlk7w91ari8J.png)

![](images/FsLa-pFkzRQJTqoh5n8Jn7TfN_G3.png)

![](images/FgCEHgJO_YcaJL9Uf-ymKU-QSvor.png)

echo "登录阿⾥云镜像"

docker login --username=tb907859\_2012 crpi-lv6l3g60ipeedbnw.cn-shenzhen.personal.cr.aliyuncs.com --password=wang\_78591qaz

echo "构建huazai-common"

cd huazai-common

mvn install

ls -alh

![](images/FjL_H1xAzDklHTfzrPqibVBXTm1A.png)

### **2.2.8.3 步骤三：添加后置步骤，构建用户模块**

![](images/FgJLuALtZc9b4yZsc31JTPHk80Zk.png)

![](images/FowUEY_HqyHMI7OKSA4AXRYXSbQq.png)

ls -alh

cd huazai-user

ls -alh

echo "用户服务构建开始"

mvn install -Dmaven.test.skip=true dockerfile:build

docker tag huazai-ecshop/huazai-user:latest crpi-lv6l3g60ipeedbnw.cn-shenzhen.personal.cr.aliyuncs.com/huazai-ecshop/huazai-user:v1.1

docker push crpi-lv6l3g60ipeedbnw.cn-shenzhen.personal.cr.aliyuncs.com/huazai-ecshop/huazai-user:v1.1

mvn clean

echo "用户服务构建推送成功"

echo "=======构建脚本执⾏完毕====="

点击保存：

![](images/FlnO2Za7vh7SxhypKeDIAeCVjmJV.png)

![](images/FglnQOzXUquJd3lkCxM7_aOVj3BL.png)

其他的微服务也是同样的操作步骤，修改构建的代码，自行操作即可，这里就不展示了。

### **2.2.9 Jenkins 构建打包推送微服务镜像实战**

当保存好用户微服务 Item 后，就可以进入构建环节：

![](images/Ftfp-to1-rgQ9sJR1ORADRDLCYZs.png)

![](images/FpDRPMv2C2CTMK-pQ8TYwWusd8pr.png)

![](images/FgbMRdtazPR22C2or9-VPA9Y0i9x.png)

根据构建日志分析，主要问题出在「**Maven 依赖管理**」和 「**本地 Jar 包路径配置**」上。

###   
**1、本地依赖路径错误**  

  
![](images/FtWejazNo3whyMzvdLXYIbO7FW2w.png)

错误描述​：

1.  systemPath 指向了本地 Windows 路径 C:/code/java/...，但 Jenkins 运行在 Linux 环境下，路径不存在。
2.  system 作用域的依赖要求路径必须是 ​绝对路径，且文件实际存在。

  
日志证据​：

![](images/FqXb-QJXxaQ9vsFE6imK3QI_dHjq.png)

解决办法，通过 Maven 命令安装到本地仓库, 在 Jenkins 构建前执行以下命令：

![](images/FnQRXFm9JeDGmp16RR11r3ZOUhro.png)

#!/bin/bash

set -e \# 遇到错误立即终止执行

\# ====================== 1. 安装本地依赖到 Maven 仓库 ======================

HOTKEY\_JAR\_PATH="./libs/hotkey-client-0.0.4-SNAPSHOT.jar"

MAVEN\_REPO\_PATH="${HOME}/.m2/repository/com/jd/platform/hotkey/hotkey-client/0.0.4-SNAPSHOT/hotkey-client-0.0.4-SNAPSHOT.jar"

if \[ ! -f "${MAVEN\_REPO\_PATH}" \]; then

echo "🔧 开始安装 hotkey-client 依赖..."

if \[ -f "${HOTKEY\_JAR\_PATH}" \]; then

mvn install:install-file \\

\-Dfile="${HOTKEY\_JAR\_PATH}" \\

\-DgroupId=com.jd.platform.hotkey \\

\-DartifactId=hotkey-client \\

\-Dversion=0.0.4-SNAPSHOT \\

\-Dpackaging=jar

echo "✅ hotkey-client 安装成功"

else

echo "❌ 错误：未找到依赖文件 ${HOTKEY\_JAR\_PATH}"

exit 1 \# 终止构建

fi

else

echo "ℹ️ hotkey-client 依赖已存在，跳过安装"

fi

\# ====================== 2. 登录阿里云镜像仓库 ======================

echo "登录阿⾥云镜像"

docker login --username=tb907859\_2012 crpi-lv6l3g60ipeedbnw.cn-shenzhen.personal.cr.aliyuncs.com --password=wang\_78591qaz

\# ====================== 3. 构建模块 ======================

echo "构建huazai-common"

cd huazai-common

mvn install

ls -alh

但是这样还是报错，经过多轮测试，目前 hotkey 相关依赖已经成功了：

![](images/Fh0ZMzcQYqGqWUyhX7Iw3lwM1zpp.png)

在 jenkins docker 内部查看：

sudo docker exec -it 3b0677b1d12a /bin/sh

![](images/Fjx5WIWNK3tTCdGmdw4hH7FTrdbO.png)

![](images/FnKt5z5kadFKi8BBBfyGlJ6ja7tu.png)

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

<mirrorOf>\*</mirrorOf>

<url>https://maven.aliyun.com/repository/public</url>

</mirror>

</mirrors>

</settings>

EOF

\# ====================== 0. 安装项目父POM ======================

echo "🔧 \[Step 1/4\] 安装项目父模块..."

cd "${WORKSPACE}"

mvn -s "${CUSTOM\_SETTINGS}" clean install -N -B | tee build.log

\# ====================== 1. 登录镜像仓库 ======================

echo "🔑 \[Step 2/4\] 登录阿⾥云镜像"

docker login --username=tb907859\_2012 crpi-lv6l3g60ipeedbnw.cn-shenzhen.personal.cr.aliyuncs.com --password=wang\_78591qaz

\# ====================== 2. 安装本地依赖 ======================

HOTKEY\_CLIENT\_JAR="${WORKSPACE}/libs/hotkey-client-0.0.4-SNAPSHOT.jar"

HOTKEY\_CLIENT\_POM="${WORKSPACE}/libs/hotkey-client-0.0.4-SNAPSHOT.pom"

HOTKEY\_COMMON\_JAR="${WORKSPACE}/libs/hotkey-common-0.0.4-SNAPSHOT.jar"

HOTKEY\_COMMON\_POM="${WORKSPACE}/libs/hotkey-common-0.0.4-SNAPSHOT.pom"

HOTKEY\_PARENT\_POM="${WORKSPACE}/libs/hotkey-0.0.4-SNAPSHOT.pom"

install\_dependency() {

local jar\_path="$1"

local pom\_path="$2"

local packaging="${3:-jar}"

echo "🔧 安装 $(basename ${jar\_path})..."

TEMP\_DIR=$(mktemp -d)

\# 强制显示 Maven 详细日志

mvn -X -s "${CUSTOM\_SETTINGS}" -Dmaven.repo.local="${TEMP\_DIR}" \\

install:install-file \\

\-Dfile="${jar\_path}" \\

\-DpomFile="${pom\_path}" \\

\-Dpackaging="${packaging}" \\

\-Dmaven.main.skip=true \\

\-Dmaven.test.skip=true \\

\-Dmaven.compile.skip=true | tee "${TEMP\_DIR}/install.log"

\# 检查安装是否成功

if \[ $? -ne 0 \]; then

echo "❌ Maven 安装失败，详见日志 ${TEMP\_DIR}/install.log"

exit 1

fi

\# 使用 Maven 命令解析坐标（严格模式）

local group\_id=$(mvn -q -Dexec.executable='echo' -Dexec.args='${project.groupId}' --non-recursive exec:exec -f "${pom\_path}" 2>/dev/null)

local artifact\_id=$(mvn -q -Dexec.executable='echo' -Dexec.args='${project.artifactId}' --non-recursive exec:exec -f "${pom\_path}" 2>/dev/null)

local version=$(mvn -q -Dexec.executable='echo' -Dexec.args='${project.version}' --non-recursive exec:exec -f "${pom\_path}" 2>/dev/null)

\# 如果解析失败，直接读取 POM 文件

if \[ -z "${group\_id}" \] || \[ -z "${artifact\_id}" \] || \[ -z "${version}" \]; then

echo "⚠️ Maven 解析失败，尝试直接读取 POM 文件..."

group\_id=$(grep '<groupId>' "${pom\_path}" | head -1 | sed 's/<\[^>\]\*>//g' | tr -d ' ')

artifact\_id=$(grep '<artifactId>' "${pom\_path}" | head -1 | sed 's/<\[^>\]\*>//g' | tr -d ' ')

version=$(grep '<version>' "${pom\_path}" | head -1 | sed 's/<\[^>\]\*>//g' | tr -d ' ')

fi

\# 最终校验

if \[ -z "${group\_id}" \] || \[ -z "${artifact\_id}" \] || \[ -z "${version}" \]; then

echo "❌ 致命错误：无法解析 POM 坐标 ${pom\_path}"

echo "POM 文件内容："

cat "${pom\_path}"

exit 1

fi

\# 合并到主仓库（严格路径处理）

local src\_dir="${TEMP\_DIR}/$(echo ${group\_id} | tr . /)/${artifact\_id}/${version}"

local target\_dir="${HOME}/.m2/repository/$(echo ${group\_id} | tr . /)/${artifact\_id}/${version}"

echo "📁 源目录: ${src\_dir}"

echo "📂 目标目录: ${target\_dir}"

mkdir -p "${target\_dir}"

cp -vf "${src\_dir}/"\* "${target\_dir}/" || { echo "❌ 文件复制失败"; exit 1; }

rm -rf "${TEMP\_DIR}"

}

\# 强制清理旧依赖

echo "🧹 清理旧依赖..."

rm -rf "${HOME}/.m2/repository/com/jd/platform/hotkey" 2>/dev/null || true

echo "📦 \[Step 3/4\] 开始安装 hotkey 依赖..."

if \[ -f "${HOTKEY\_CLIENT\_JAR}" \] && \[ -f "${HOTKEY\_CLIENT\_POM}" \] && \[ -f "${HOTKEY\_COMMON\_JAR}" \] && \[ -f "${HOTKEY\_COMMON\_POM}" \]; then

\# 安装父 POM

echo "⬆️ 安装父 hotkey POM..."

install\_dependency "${HOTKEY\_PARENT\_POM}" "${HOTKEY\_PARENT\_POM}" "pom"

\# 安装 common 模块（关键步骤）

echo "⬆️ 安装 hotkey-common 模块..."

install\_dependency "${HOTKEY\_COMMON\_JAR}" "${HOTKEY\_COMMON\_POM}"

\# 安装 client 模块

echo "⬆️ 安装 hotkey-client 模块..."

install\_dependency "${HOTKEY\_CLIENT\_JAR}" "${HOTKEY\_CLIENT\_POM}"

\# 严格依赖校验

echo "✅ hotkey 依赖安装验证..."

declare -a required\_dirs=(

"com/jd/platform/hotkey/hotkey-common/0.0.4-SNAPSHOT"

"com/jd/platform/hotkey/hotkey-client/0.0.4-SNAPSHOT"

)

for dir in "${required\_dirs\[@\]}"; do

target\_path="${HOME}/.m2/repository/${dir}"

if \[ ! -d "${target\_path}" \]; then

echo "❌ 错误：hotkey 依赖目录未生成 ${target\_path}"

exit 1

fi

echo "✔️ hotkey 验证安装通过: ${target\_path}"

ls -l "${target\_path}"

done

else

echo "❌ 错误：缺少 hotkey 相关依赖文件"

ls -l "${WORKSPACE}/libs/"

exit 1

fi

\# ====================== 3. 构建模块 ======================

echo "🏗️ \[Step 4/4\] 构建 huazai-common"

cd huazai-common

mvn -s "${CUSTOM\_SETTINGS}" clean install -U -e -X

ls -alh target/\*.jar

echo "🎉 构建成功！耗时：${SECONDS}秒"

构建日志可以看到验证通过：

![](images/FlKLVSsVypTTWG3paG0UgWAmjckw.png)

但是最终还是因为 rocketmq 与 hotkey 底层依赖的 io.grpc:grpc-core 版本冲突导致最终失败：

1.  ​RocketMQ 依赖链​ 需要 grpc-core:1.53.0
2.  ​JD hotkey-common 依赖链​ 需要 grpc-core:1.30.1

这块在本地开发时通过下面就可以保证不报错：

![](images/FsfZfJhAOKtDQjr9AOyo8NllKOL3.png)

依赖包冲突解决：

![](images/Fhl718QN_fR07HVVK7vBiw4YEQIY.png)

![](images/FoVNG9c7oeuQgr5l9smt4NjPta9m.png)

最后需要改下之前的「**后置步骤 shell**」：

  
![](images/Fq93bnssct-7VlKT7zpphOrWK3Dn.png)

再次执行构建，如下错误：

![](images/Fs7-Y8a9DE79UeyeRl614G7427jb.png)

这里涉及到「**Docker 镜像打包插件介绍**」与 「**DockerFile 编写**」，这块我会放到下篇中进行介绍和实战，本篇暂时到此为止。

### **2、重复依赖声明**

![](images/FucHFrk3ugrdNFf7gA746QPzAEWv.png)

guava 依赖被重复声明，导致 Maven 警告。虽然不会直接导致构建失败，不过这个可以忽略。