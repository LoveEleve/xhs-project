从今天之后的一段时间内，华仔会带着大家一起从零开始搭建并研发一套高并发的电商实战项目，这里会涉及到很多互联网大厂开发过程中所使用的核心技术和架构设计模式，希望大家学完之后可以用到自己的简历中。

接下来我们会重点架构设计和开发一下我们高并发电商实战前端 Web 项目的相关功能 。

这是第九十篇，本篇我们继续进行电商实战项目设计与开发，本篇我们来「**介绍一线大厂的一站式 DevOps 平台与 Rancher 2.x 容器编排管理平台部署**」。

文章汇总位置：[https://wx.zsxq.com/dweb2/index/columns/51122554151214](https://wx.zsxq.com/dweb2/index/columns/51122554151214)

![](images/Fnl1ueruAt4exbk9U2e5CcbjFHDI.png)

源码授权与获取地址：[https://articles.zsxq.com/id\_1s85grnaae4p.html](https://articles.zsxq.com/id_1s85grnaae4p.html)

![](images/FmkphgNM2yJBSqCq4raWXJA2r2Ah.png)

本章源码地址：[https://gitcode.net/u011359591/huazai-ecshop/-/tree/ecshop-chapter-90](https://gitcode.net/u011359591/huazai-ecshop/-/tree/ecshop-chapter-90)

## **01 前言**

通过前面 89 篇，我们将整个「**小红书社交 + 电商平台**」的相关架构设计、功能实现就搞了一遍， 从今天开始，我们先来把整个系统进行「**容器编排**」，最后我们再来进行「**接口性能压测**」。

今天我们先来重点「**介绍一线大厂的一站式 DevOps 平台与 Rancher 2.x 容器编排管理平台部署**」。

##   
**02 大厂一站式 DevOps 平台**

在一线大厂，如阿⾥、腾讯、字节、京东、美团等都有自己的⼀站式 DevOps 平台，提供从「**需求**」\->「**开发**」-\>「**测试**」\->「**发布**」\->「**运维**」-\>「**运营**」端到端的协同服务和研发⼯具。

## **2.1 大厂是如何操作的**

由专⻔的团队开发维护好⾃⼰ BU 的容器管理平台 k8s，比如阿⾥的Aone-》对应阿⾥云产品云效[https://devops.aliyun.com/](https://devops.aliyun.com/)。

![](images/FlVV5lpAI7L71GrjeT6WC_lqYDwk.png)

整个云效平台链路的处理流程如下：

![](images/Fo2UPtnRhs7z61S0ujxYC5cW0wBc.png)

1.  容器编排正快速发展，从主要的基础设施公司到PAAS供应商，不仅仅是编排⼯具，更᯿要的是要构建、部署、CI/CD等⼀系列功能。
2.  很多⼚商也在PaaS平台做⼀些⼯作，重点在于让开发⼈员不必关⼼底层应⽤部署细节，只需想好要什么即可。

![](images/FpW73bNBHRrRuprihr94vld_IyDR.png)

## **2.2 容器编排平台介绍**

### **2.2.1 KubeSphere**

KubeSphere 是在 Kubernetes 之上构建的⾯向云原⽣应⽤的分布式操作系统，完全开源，⽀持多云与多集群管理，提供全栈的 IT ⾃动化运维能⼒，简化企业的 DevOps ⼯作流。它的架构可以⾮常⽅便地使第三⽅应⽤与云原⽣⽣态组件进⾏即插即⽤ (plug-and-play) 的集成。

官方地址：[https://kubesphere.com.cn/](https://kubesphere.com.cn/)

![](images/FvlAlSOKcV02l1K4m3EiV9XR5AmB.png)

![](images/Frz8fFHspn4cMgPsHgiNUWRpOKtM.png)

感兴趣自己去操作下，都是比较傻瓜式的操作。

### **2.2.2 Rancher**

它是⼀个开源的企业级容器管理平台, 通过Rancher企业再也不必⾃⼰使⽤⼀系列的开源软件去从头搭建容器服务平台。

Rancher提供了在⽣产环境中使⽤的管理 Docker 和 Kubernetes 的全栈化容器部署与管理平台。

官方地址：[https://rancher.com/](https://rancher.com/)，中文文档地址：[https://docs.rancher.cn/](https://docs.rancher.cn/)

![](images/FvX2_56ubo4OBb95HUXvLNk9bAav.jpg)

![](images/Fmslf6URPUpXNCqFVu7ZAo2ClKRM.png)

上面两个都是基于K8s提供功能,是比较优秀的k8s集群管理平台，对于降低k8s集群运维复杂度，降低运维成本，使开发⼈员能尽快上⼿部署微服务。

这里我们选择使用 「**Rancher**」作为最终的容器编排平台来进行实操。

## **2.3 Rancher 2.x 容器编排平台部署实战**

实战 Rancher 之前，需要安装下 docker 环境。

这里我们准备 3 台主机，组成一个 Racher 集群，下面只展示在一台主机的操作，另外两台主机的操作一致即可。

### **2.3.1 清理旧版本 docker**

ubuntu 下自带了 docker 的库，不需要添加新的源。但是自带的 docker 版本太低，需要先卸载旧的再安装新的

sudo apt remove docker docker-engine docker.io containerd runc

![](images/Ft4NRrDK0L9soVW-Xqd4UI_wCzo6.png)

### **2.3.2 获取最新源**

sudo apt update

![](images/Fl7MNNO8HF2dX3QIc5m8_Ey5I4AY.png)

### **2.3.3 安装 apt 依赖**

sudo apt-get -y install apt-transport-https ca-certificates curl software-properties-common

![](images/Fh_S9BB6OH6lEZy5z9NLkB7_-tkK.png)

### **2.3.4 安装 GPG 证书**

curl -fsSL https://mirrors.aliyun.com/docker-ce/linux/ubuntu/gpg | sudo apt-key add -

![](images/FiK7o0-oyPH_QbRrGFW4VXD14M9G.png)

验证：sudo apt-key fingerprint 0EBFCD88

![](images/Fshz9JNxfkuUxoRyTWD1hxh-7JEC.png)

### **2.3.5 设置稳定版仓库**

sudo add-apt-repository "deb \[arch=amd64\] https://mirrors.aliyun.com/docker-ce/linux/ubuntu $(lsb\_release -cs) stable"

![](images/Flj_6Ho69-z_dEUitvaUe7a0W4VO.png)

### **2.3.6 安装 docker Engine Community**

sudo apt-get install docker-ce docker-ce-cli containerd.io

![](images/FoKjfjI_zI35cyGmeohlCMjaQKcO.png)

测试效果：

sudo docker run --rm hello-world

![](images/Fh4AOaeYsHyAL3btwzgSOISZHL2P.png)

官方地址访问不通，需要切换镜像，这里以「**daocloud 镜像**」为例， [https://github.com/DaoCloud/public-image-mirror](https://github.com/DaoCloud/public-image-mirror)。

### **2.3.6.1 修改 docker 配置文件**

修改 docker 配置文件，添加镜像地址：

sudo nano /etc/docker/daemon.json

{

"registry-mirrors": \["https://docker.m.daocloud.io"\]

}

![](images/FmMZhU-hx7m4QlkUlO2GoToqCOoa.png)

保存完毕后，检测当前实际使用时的拉取镜像源：

sudo docker info | grep -A 1 "Registry Mirrors"

![](images/Fmtc-Sk0hDExnk7cx1I92M_nUig4.png)

### **2.3.6.2 重启 docker**

sudo systemctl daemon-reload

sudo systemctl restart docker

sudo systemctl status docker

![](images/FnUo7MJW0mX__pjGTWYDLYPdHEcb.png)

再次测试运行：

![](images/FnyZV8pz5pGihyt4fK_F-D1LEkw1.png)

### **2.3.7 安装 Rancher 2.x**

创建 Rancher 挂载目录：

mkdir -p rancher\_home/rancher

mkdir -p rancher\_home/auditlog

![](images/FtGAntCMSUsQlYjSA0izncNBz_bK.png)

开始部署，这个在其中一台部署即可：

sudo docker run -d \\

\--privileged \\

\--restart=unless-stopped \\

\-p 80:80 \\

\-p 443:443 \\

\-v /home/wangjianghua1/rancher\_home/rancher:/var/lib/rancher \\

\-v /home/wangjianghua1/rancher\_home/auditlog:/var/log/auditlog \\

\--name huazai\_rancher1 \\

rancher/rancher:v2.5.16

![](images/FgjR76SF86yJh2jIV-8_RIXw2NVV.png)

### **2.3.8 登录 Rancher 2.x**

启动成功 Rancher 后, 可以打开浏览器输⼊IP地址来进⼊ Rancher，登录地址为：[http://+IP](http://+ip/) ，比如我的IP：[http://172.20.8.220](http://172.20.8.220/)

配置账号密码，填写完账号密码后直接Continue即可。

![](images/FrBSN7HmMIKGgB9nLB3HpZD1Yh69.png)

保存配置，点击 Continue 后进⼊到 Server URL ⻚⾯，这⾥显示的是 IP（公⽹ip）地址，不⽤管直接点击 SaveURL。

![](images/Fi1v-ODZDd_6barv6EZANF_eAfPJ.png)

![](images/Fs3t7C9tKbtFVWrh5Kj1yZGsqxko.png)

![](images/FrwoA28waOwvgdMBu2NInpMtB8t4.png)

### **2.3.9 Rancher 2.x 配置镜像加速实战**

首先需要配置阿⾥云镜像加速地址，主要是因为 Rancher 依赖组件多，不配置下载镜像慢容易出问题。所以最佳实践就是配置一下，那么该如何配置呢？

这里就需要登录阿里云地址了，其入口：[https://cr.console.aliyun.com/cn-shenzhen/instances/mirrors](https://cr.console.aliyun.com/cn-shenzhen/instances/mirrors)

![](images/FldJg1ODXXfRqScVLtHTLppqmslr.png)

前面我们已经配置过 daocloud 了，这里需要增加一个 [https://0niopex2.mirror.aliyuncs.com](https://0niopex2.mirror.aliyuncs.com/)：

sudo nano /etc/docker/daemon.json

{

"registry-mirrors": \["https://docker.m.daocloud.io", "https://0niopex2.mirror.aliyuncs.com"\]

}

![](images/Ftay2d8Dpduhif0K4Eql4LU8Nq_n.png)

修改完成后，重新启动 docker：

sudo systemctl daemon-reload

sudo systemctl restart docker

![](images/FsbgipRaZLAsiP3SqkZEcnyDNHeY.png)

### **2.3.10 Rancher 2.x 添加集群配置 RancherAgent 节点**

这一小节比较重要，大家多实战。

### **2.3.10.1 创建电商项目集群**

首先在 Rancher 主页面点击 「**添加集群**」，然后进⼊到「**选择集群**」的类型，选择「**自定义**」即可。

![](images/Fq_TzY2blQpeBPizvg8g7OAEhJRY.png)

![](images/FmLDJzNz5nZfyrBd5k61bo8z6xuV.png)

进⼊到添加集群- Custom ⻚⾯:

![](images/FjEotWk1nhVfAtFRCWt9zL5xsx68.png)

进入下一步：

![](images/FvsWpZzveIiCMxlyWTaodOY3hEQ-.png)

sudo docker run -d --privileged --restart=unless-stopped --net=host -v /etc/kubernetes:/etc/kubernetes -v /var/run:/var/run rancher/rancher-agent:v2.5.16 --server https://172.20.8.220 --token tdz75m9fzv5jvd28dhhbc4lsznjd6g4xtzfnkgsjlw5zj6fgdkggjn --ca-checksum 6b868117ccf1b491575be66c837aad0b688db084e3a1a40c1dd42161eae0ca82 --etcd --controlplane --worker

![](images/Fkp-Z7niS2g1C8EjYHkaYpb5__ts.png)

然后将前面复制的命令在 3 台主机上都要执行（云主机记得开放对应端口）：

![](images/lvQbbJpaG_oVCXHIzN3sIDZcwTuY.gif)

![](images/Fm-9U0XHEQdi8yW8qpRlUk_FnxU6.png)

![](images/FrgfdrrO_p-HeIu3EThFuDxscdpc.png)

![](images/FnUu0woVIQfrs_BDarJ3yNxWyStz.png)

这里在集群就绪之前是不可用的，我们只能静静等待它完成，完成之后我们再来操作。

昨晚的有问题，删除重新执行了一次，如果报错如下：

![](images/Fvk-d2WbX_6KkMgUou-VUl0dUIO4.png)

根据提供的日志和错误信息，节点 [LAPTOP-3B77RHGG](http://laptop-3b77rhgg/) 无法注册到 Kubernetes 集群的核心原因是「**Rancher Server 的 443 端口无法访问**」，同时可能存在 「**主机名大小写不兼容**」和 「**挂载配置问题**」。

sudo docker logs -f 81c850b20198a2bf3c97d8a8eb4aa82a45382150e4ea638bf803739abd6a5c37 |grep "Error getting node"

![](images/Fl8PCKpC4qIgpmmIcqrG23UnqIG7.png)

![](images/Fph6lfkaLGugL3K8noMNHi6VliOA.png)  

这里需要手动安装 kubectl：

wangjianghua1@LAPTOP-3B77RHGG:~$ curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"

% Total % Received % Xferd Average Speed Time Time Time Current

Dload Upload Total Spent Left Speed

100 138 100 138 0 0 390 0 --:--:-- --:--:-- --:--:-- 390

100 57.3M 100 57.3M 0 0 18.9M 0 0:00:03 0:00:03 --:--:-- 27.4M

wangjianghua1@LAPTOP-3B77RHGG:~$

wangjianghua1@LAPTOP-3B77RHGG:~$

wangjianghua1@LAPTOP-3B77RHGG:~$ sudo install -o root -g root -m 0755 kubectl /usr/local/bin/kubectl

wangjianghua1@LAPTOP-3B77RHGG:~$ kubectl version --client

Client Version: v1.33.1

Kustomize Version: v5.6.0

![](images/FhNKRx4MZFHapK8vJfD9Gm7uQATN.png)

这张日志和截图表明，​**节点重复注册**和 ​[\*\*/var/lib/rancher 挂载点未共享\*\*](http://%2A%2A/var/lib/rancher%20%E6%8C%82%E8%BD%BD%E7%82%B9%E6%9C%AA%E5%85%B1%E4%BA%AB**)​ 导致控制平面升级失败，具体原因如下：

1.  ​**重复节点名称**​：两个同名节点 LAPTOP-3B77RHGG 尝试注册到同一集群，引发冲突。
2.  **挂载点权限问题**​：/var/lib/rancher 挂载在非共享挂载的路径上，导致容器无法重启 kubelet。
3.  ​**控制平面依赖未就绪**​：集群处于 Provisioning 状态，API Server 未完全启动，功能受限。

首先第一条是因为之前报错删除重新执行导致重复，可以忽略。

第二个问题：

\# 检查挂载点共享状态

mount | grep /var/lib/rancher

\# 如果是非共享挂载，重新挂载为共享

sudo mount --make-shared /var/lib/rancher

#永久生效

echo "/var/lib/kubelet /var/lib/kubelet none bind,shared 0 0" | sudo tee -a /etc/fstab

\# 若为 Docker 驱动，需配置 MountFlags

sudo mkdir -p /etc/systemd/system/docker.service.d

sudo tee /etc/systemd/system/docker.service.d/mounts.conf <<-'EOF'

\[Service\]

MountFlags=shared

EOF

\# 重启 docker

sudo systemctl daemon-reload

sudo systemctl restart docker

执行完会报其他错误：

![](images/FhLNcP6wdikVTNBhEDBNievUl7BF.png)

发现启动容器 rke-etcd-port-listener 容器试图绑定宿主机 IPv4 的 0.0.0.0:2379 端口，但该端口已被 etcd 服务占用。关键服务占用：

1.  etcd：Kubernetes 集群的默认键值数据库，必须保留其 2379 端口监听（若强行终止会导致集群不可用）。
2.  kube-apis：Kubernetes API Server 的进程，其大量连接是正常通信行为（如修改配置需重启集群）。

![](images/FoExLATj7f7B4C9kDi2WEDaUaczV.png)

大概率是下面这个容器是占用 2379 端口的 etcd 实例，需要停止且删除下。

sudo docker stop 213cb56ddfc9

sudo docker rm 213cb56ddfc9

\# 也可以使用该命令来批量删除已关闭的容器

sudo docker container prune

然后可能会出现如下错误：

![](images/FurrlewGK63q0wanhHaSE-sbWyiL.png)

继续排查端口占用情况，这个端口是 Kubernetes API Server 默认端口。错误显示在主机 172.20.8.220 上无法绑定 Kubernetes 控制平面端口 ​6443，大概原因是：

1.  ​端口重复绑定​：已有进程或容器占用了 6443 端口（Kubernetes API Server 默认端口）。
2.  ​残留容器冲突​：之前的 rke-cp-port-listener 容器未正确清理，导致端口仍被标记为占用。
3.  ​节点角色重复分配​：截图显示同一主机（172.20.8.220）被同时分配了 controlplane 角色多次，引发端口冲突。

  
不清楚到底是哪里的问题，或许是我执行了多次导致的。

![](images/FqxJizknvUtEppG3OIo8Va8EMfuR.png)

  
通过截图和命令输出可确认：​6443 端口被 Kubernetes 控制平面组件（kube-apiserver）占用，导致新集群无法绑定该端口。具体原因如下：

1.  ​残留的 kube-apiserver 进程​：lsof 显示 kube-apiserver（PID 146696）正在监听 6443 端口，且与多个组件（kubelet、controller-manager）建立了连接。
2.  ​未彻底清理旧集群​：可能是之前部署 Kubernetes 或 Rancher 集群后未正确卸载，导致进程残留。

**这里我不打算找原因了，重新再来操作一遍，将之前的所有 docker 及 rancher 集群都删除，只剩下 rancher 本身，**如下：

![](images/FqK1WueXo0dctDzru_A7jRqMQQCH.png)

然后重新添加集群，执行命令：

![](images/FqTPb2B6sxEe3Iyfiw3XrpOFAPRR.png)

大概等了几分钟，好了：

![](images/FmbE4DkiYsq86Hsceux-BsYlcpf4.png)

查看生成的 docker 如下:

![](images/FiaQ6TxvjqTur0_zAiDWd9xd-PgK.png)

![](images/FnyxJffrF0V69FDXjYvzIghK4Xz7.png)

后来发现运行 rancher 确实有问题，可能我在单节点部署 rancher 导致端口占用导致的，还是会出现上面的问题。

解决办法：

#检查是否是共享

findmnt -o TARGET,PROPAGATION /

findmnt -o TARGET,PROPAGATION /var/lib/kubelet

findmnt -o TARGET,PROPAGATION /var/lib/rancher

#如果不是，先mount

sudo mount --make-shared /

sudo mount --bind /var/lib/kubelet /var/lib/kubelet

sudo mount --make-shared /var/lib/kubelet

sudo mount --bind /var/lib/rancher /var/lib/rancher

sudo mount --make-shared /var/lib/rancher

#永久生效

echo "/var/lib/kubelet /var/lib/kubelet none bind,shared 0 0" | sudo tee -a /etc/fstab

#删除所有的 rancher 相关容器包括rancher 本身，最后执行

sudo docker container prune

#删除相关目录历史文件

rm -rf wangjianghua1/rancher\_home/auditlog/\*

sudo rm -rf wangjianghua1/rancher\_home/rancher/\*

cd /etc/kubernetes/ && sudo rm -rf \*

cd /var/lib/rancher/ && sudo rm -rf \*

cd /var/lib/kubelet/ && sudo rm -rf \*

\# 重新安装 rancher 和 rancher agent

sudo docker run -d --privileged --restart=unless-stopped -p 80:80 -p 443:443 -v /home/wangjianghua1/rancher\_home/rancher:/var/lib/rancher -v /home/wangjianghua1/rancher\_home/auditlog:/var/log/auditlog --name huazai\_rancher1 rancher/rancher:v2.5.16

sudo docker run -d --privileged --restart=unless-stopped --net=host -v /etc/kubernetes:/etc/kubernetes -v /var/run:/var/run rancher/rancher-agent:v2.5.16 --server https://172.20.8.220 --token q5sxmk95hlz8dc7txv2c7qxpr8xmqxtbg4957vs5r8ndxx557k7thl --ca-checksum 19511cc64bb1273df1788e547dd942cb30bf36eb3b66212de04c2cee6a32820a --etcd --controlplane --worker

至此，我们的空集群就搭建好了，下篇我们会进行「**小红书社交 + 电商微服务**」部署。

###   
**2.3.11 Rancher 2.x 配置私有镜像仓库实战**

私有镜像仓库主要⽤于存放公司内部的镜像，不提供给外部试⽤；

它有哪些？

1.  Harbor: 由 VMWare 公司开源的容器镜像仓库，Habor 是在 Docker Registry 上进⾏了相应的企业级扩展。
2.  Registry: 由 docker 官⽅提供的私有镜像仓库。
3.  云⼚商提供：阿⾥云、腾讯云等。

开通阿⾥云私有镜像仓库，登录阿⾥云账号访问地址：[https://cr.console.aliyun.com/](https://cr.console.aliyun.com/), 初次使⽤会提示开通。

整个开发部署流程图如下：

![](images/lldoKYA-XqVWGBBVuBxEZ26bPBUN.png)

### **2.3.11.1 私有镜像仓库配置**

入口，选择 huazai-ecshop 这个集群，然后选择 Default：

![](images/FiqsRsBjXnrXNViimABkXecSiAFq.png)

接着选择「**资源**」\->「**密文**」：

![](images/FtwgA4CBm67xuOHayE_vjkXT9ZtY.png)

然后点击「**镜像库凭证列表**」-> 「**添加凭证**」：

![](images/FigMFgy9AOBsIdi2-Juban33avvs.png)

这里需要先来看下阿里云的后台， 先创建一个命名空间：

![](images/FusEOSON2ylgpL3y4NY27xWFxszp.png)

然后创建镜像仓库：

![](images/Frhj1nACjlhX1L2Dm5GuiWdtQ_uw.png)

![](images/Fn1280Liutlr3x-x7_cezjElnNXK.png)

点击完成后，就可以看到阿里云的镜像仓库地址：

![](images/FpqiJhAPpHEm1DhQiBmJtCrPYeUd.png)

将这个地址添加到前面的 Rancher 平台：

![](images/Fn9ZXNzxk94uSKBkJt4ORYiSXmyK.png)

![](images/Fp4qfqaJkXvPRMuDhJ1zOQkHip45.png)

![](images/Fmj2FdtG8hJkjvMusEkOQrg3igJz.png)