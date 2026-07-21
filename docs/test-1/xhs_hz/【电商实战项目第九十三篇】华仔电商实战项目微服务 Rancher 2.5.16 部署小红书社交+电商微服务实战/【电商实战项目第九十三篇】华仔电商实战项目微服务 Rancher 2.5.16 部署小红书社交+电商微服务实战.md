## 【电商实战项目第九十三篇】华仔电商实战项目微服务 Rancher 2.5.16 部署小红书社交+电商微服务实战

从今天之后的一段时间内，华仔会带着大家一起从零开始搭建并研发一套高并发的电商实战项目，这里会涉及到很多互联网大厂开发过程中所使用的核心技术和架构设计模式，希望大家学完之后可以用到自己的简历中。

接下来我们会重点架构设计和开发一下我们高并发电商实战前端 Web 项目的相关功能 。

这是第九十三篇，本篇我们继续进行电商实战项目设计与开发，本篇我们来实操「**Rancher 2.5.16 部署小红书社交+电商微服务实战**」。

文章汇总位置：[https://wx.zsxq.com/dweb2/index/columns/51122554151214](https://wx.zsxq.com/dweb2/index/columns/51122554151214)

![](images/Fnl1ueruAt4exbk9U2e5CcbjFHDI.png)

源码授权与获取地址：[https://articles.zsxq.com/id\_1s85grnaae4p.html](https://articles.zsxq.com/id_1s85grnaae4p.html)

![](images/FmkphgNM2yJBSqCq4raWXJA2r2Ah.png)

本章源码地址：[https://gitcode.net/u011359591/huazai-ecshop/-/tree/ecshop-chapter-93](https://gitcode.net/u011359591/huazai-ecshop/-/tree/ecshop-chapter-93)

## **01 前言**

上篇，我们重点进行了「**微服务 Docker 镜像构建配置及 DockerFile 编写实战**」， 今天我们重点来进行实操下「**Rancher 2.5.16 部署小红书社交+电商微服务实战**」。

## **02 Racher 2.5.16 部署小红书社交+电商微服务实战**

这里我们的部署顺序如下：先部署「**GateWay 服务**」，然后再根据端口号顺序挨个部署其他微服务。

> 注意：对应的 Nacos、Mysql、Redis、RocketMQ、Elasticsearch、XXL-Job、Canal、Sentinel、SkyWalking 等中间件暂时先不改动，后续再进行迁移部署。
> 
> 可能在部署微服务的时候会报相关中间件错误，这里先忽略，后续在来观察。

## **2.1 Racher 2.5.16 部署 GateWay 服务**

### **2.1.1 新增工作负载**

![](images/Fp5jLDERujx-wsBGNduwFElmYDa3.png)

这里的 Docker 镜像填写上一篇中生成的阿里云镜像版本。

![](images/Foq6vN4K5XZl7yVcHpEDAGy6zP5l.png)

![](images/FlIaqYxxqAIVzDEwZsNW3d7veBK0.png)

### **2.1.2 编辑集群-端口范围**

我们的微服务是从 9001 开始，所以下图需要改为从 9000 开始，默认是从 30000 开始的。

![](images/FsoPsoqezj_wxwd8hkDytMBH4xXG.png)

### **2.1.3 启动 gateway 服务**

![](images/FhET32kIcNJGCE3go-P_jsIvbROm.png)

![](images/FvUEp-29sYXwZ5IV3fn9b_wtisC5.png)

点击启动后就会自动去阿里云拉取镜像版本进行部署了：

![](images/FpQoaiYS7gTmyVXCKL6E7mPAulbl.png)

此时需要静静等待即可:

![](images/Fl3Ks77OrqwniLUlYjN4KBipzjIL.png)

![](images/FmMSDEe8p2C62SQU-VpkrDnOfhxA.png)

但是会发现启动失败，如下：

![](images/FvSYaZIA4HyQduW2cC4aHkuTNW2J.png)

![](images/Fg2Qg6-C1S3-CXYV8QmRWi2yrDFQ.png)

我们等待一会，发现最终可以成功注册到 Nacos：

![](images/FolGOGixi8VgqQ9sZDavOYfPNDzf.png)

![](images/FgUuQ0TKzFA_n34zHMIy-rotr3Qq.png)

## **2.2 Racher 2.5.16 部署用户服务**

同样的步骤，我们再来操作下，熟悉下流程。

![](images/FtrMTWmQT94rrrPERwlPZlwoUE38.png)

![](images/FjUWtUfhajwaHiVzV5V6KPs2qgqA.png)

查看日志有报错无所谓，后续我们等部署完所有的中间件后在来观察启动日志。

## **2.3 Racher 2.5.16 部署首页服务**

同样的步骤，我们再来操作下，熟悉下流程。

![](images/FgXDuvHklpOYXkp_ECnbqeKa8TeP.png)

![](images/FrayTrAQqwU6VbetsvnFRvL8aOFz.png)

## **2.4 Racher 2.5.16 部署美食服务**

同样的步骤，我们再来操作下，熟悉下流程。

![](images/FiVrQlfOBoh1cQVVd070l6vCSzvZ.png)

![](images/Fvb0noor4wvIg50jq6pz_WmW56jY.png)

## **2.5 Racher 2.5.16 部署社交服务**

同样的步骤，我们再来操作下，熟悉下流程。

![](images/Fn1ajAcGhhCoW9rKyItOa-fSe604.png)

![](images/Fk3hgsQkJplGkVhKm6em95uqTy_S.png)

## **2.6 Racher 2.5.16 部署购物车服务**

同样的步骤，我们再来操作下，熟悉下流程。

![](images/FluG4Heau-VuzLznqYjpqn4K7pvr.png)

![](images/FpedL8vsYQdxjaE5coiCzbO54FYo.png)

## **2.7 Racher 2.5.16 部署商品服务**

同样的步骤，我们再来操作下，熟悉下流程。

![](images/FhlbuR0rHRO55MQ4bIzfjNf-NXXU.png)

![](images/FpER-0h8RND_WU7BHx1_f6x0AIkh.png)

## **2.8 Racher 2.5.16 部署IM服务**

同样的步骤，我们再来操作下，熟悉下流程。

![](images/FksADYtz94XQnuYkJINCZ4Nopby5.png)

![](images/FhQ6PG7BjJj90y1rL7AUkMiWcM0V.png)

## **2.9 Racher 2.5.16 部署优惠券服务**

同样的步骤，我们再来操作下，熟悉下流程。

![](images/FqZPPJ0-DTWqjsOxURzzGkADtuDM.png)

![](images/FrGFKc5KqGnrQRCHHN8pb5vamSEf.png)

## **2.10 Racher 2.5.16 部署库存服务**

同样的步骤，我们再来操作下，熟悉下流程。

![](images/FvQ-NZ48T2-kHO6yK98GXy_QSqCu.png)

![](images/Fri_RGaM9qIhNUZKTreNgghvKU1x.png)

## **2.11 Racher 2.5.16 部署订单服务**

同样的步骤，我们再来操作下，熟悉下流程。

![](images/FvlJV3BdzKOHM8mdmLs9RYW7Mmsr.png)

![](images/Fm7RReHK6sBJJ2cCJOxNh8o5UgDD.png)

## **2.12 Racher 2.5.16 部署支付服务**

同样的步骤，我们再来操作下，熟悉下流程。

![](images/FtZhAWevVylKHin1muK5_uSVQAgI.png)

![](images/FkwKjUj4aoKJns2eI2nIFSYlAySj.png)

## **2.13 Racher 2.5.16 部署后管服务**

同样的步骤，我们再来操作下，熟悉下流程。

![](images/FsJVVZPkBDrDCZRkjCmzaeFd0IpR.png)

![](images/FiK3kgVILo-98M7ODdBPeiArEpAb.png)

这里启动还是有些问题的，先忽略。

## **2.14 Racher 2.5.16 部署推送服务**

同样的步骤，我们再来操作下，熟悉下流程。

![](images/Fr7d-fDe_idCE3t_O_ENJTvAmn00.png)

![](images/Fpc1nZ8yJFzbnGGeuXdcYHMJvfiF.png)

## **2.15 微服务部署汇总**

![](images/Fuy42h9tlu5owvymxyeocx2qtack.png)

![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAQAAAAEACAYAAABccqhmAAAQAElEQVR4AeydjZLkNg6D97v3f+dctH2+sUi4zZHtbv8gFcUmDYIUlELVqGaT//zjv6yAFXisAv/547+sgBV4rAI2gMcevTduBf78sQH43wIr8FAF2rZtAE0FLyvwUAVsAA89eG/bCjQFbABNBS8r8FAFbAAPPXhv+9kKTLu3AUxK+GkFHqiADeCBh+4tW4FJgbIBAH/g+2safI8n5P0oXuhxFQz0NfCKt9TCiwNeT8V1dA5eveH9c885IPfak7/KBf0cqg56DHwnVrOpXNkAVLFzVsAKXE+B+cQ2gLkafrcCD1PABvCwA/d2rcBcARvAXA2/W4GHKbDJAP75558/R66jz0LNfnTPPfkhXzApfqjhVG3MVTWD3BPWc7Ffi1VPGOOC9TrQmDbLyFLz75n7zUwRu8kAIpljK2AFrqWADeBa5+VprcCuCtgAdpXTZFbgWgrYAK51Xp7WCgwroAp3NwDQFyjwPq+GG83B+17w+l7ljxc28KqHn6fiinUthp8aeL2r2tEcvDjh59n6xgU/36H+ruaK3CpWdSoHtVlUj5iDzKV6xroWK9yeOcizwXpuzxka1+4G0Ei9rIAVuIYCNoBrnJOntAKHKGADOERWk1qBcymwNM0tDaD9DFdZsP4zF2RMhbthlOgtv9eq8itczFVngqxH5Gox9DjF33BxKZzKQc8PRKrd4zjH7g2+QHhLA/iCjm5pBS6pgA3gksfmoa3APgrYAPbR0SxW4LQKvBvMBvBOHX+zAjdX4BYGAAz958qqZxsvf2CsH9TrqrNFHNR6jNZFLVocuVrc8vPVcqML8p7m3NM79LgpP39WZ5jXTO/V2ivhbmEAVxLcs1qBMylgAzjTaXgWK7CzAmt0NoA1hfzdCtxYARvAjQ/XW7MCawrsbgDThclvn2uDvvv+214TXnFO3+ZPWL9cmuPfvaueKgd9T8ix6qO4FK6SU1wqB3m2iIN1TKz5bRz3BLWeUMP9dp53+DhrNX7HOfJtdwMYGcI1VsAK7K9AhdEGUFHJGCtwUwVsADc9WG/LClQUsAFUVDLGCtxUgU0GAPnyBPbLVTWHvqeqgx4DyP+nAazjIGO29FS18VJIYbbkoN/DFq7R2rjHFkM/F9TPqTJH6xFXpa5hoJ+t5SoL+jrYN1YzVHObDKDaxDgrYAXOqYAN4Jzn4qmswEcUsAF8RGY3sQLnVMAGcM5z8VRWYFiB3xSWDSBenHwrrmwO8iVLpa5h1L5afmQpLqjNBj1O9YceAyiYzMXZJEgkgfRHrwWslIIaF4zh4h5bXBpsA6j1OMOqbqFsAFVC46yAFbiOAjaA65yVJ7UCuytgA9hdUhNage8p8NvONoDfKma8FbiRArsbAOQLG+hzVf2grwMdV/kiDjQf9PlYtyVWF0SKT+FiTtWpHPT7gVqsuI7OxT1uidWskPeucCoXZ4HMBTmnuKCGU7V75nY3gD2HM5cVsALHKmADOFZfs1uBjykw0sgGMKKaa6zATRT4igFA/vkHci7+zLUUj56F4hvlgjy/4oIaLtZCrqvOX8XFnt+IIe9zdA6ocZ1ZH+j3MKrFUt1XDGBpGOetgBX4rAI2gM/q7W5W4BAFRkltAKPKuc4K3EABG8ANDtFbsAKjCpQNAPrLCGC0p/xPcSkyIP3JM8g5VRtz6qIH9uNS/HGGb8Wwvs/q/FVcZa9buGBsT9WekPmhzykulYO+DvR/5kxpFvkUZkuubABbmrjWCliB4xTYwmwD2KKea63AxRWwAVz8AD2+FdiigA1gi3qutQIXV2CTAUDtcqNykRExS3FFb1ULtVkVP+RaGMup2VQOen6FUbOqXKUW+n5Qv6hSPaHnq2AABZMXwWpPgMTC+7xsKpKxp4CUU5BnUsXQ4yKmxdBjgJYurU0GUOpgkBWwAqdVwAZw2qPxYFbgeAVsAMdr7A5W4LQK2ABOezQezAq8V2CPr5sMIF6KtBjoLmLUkNBjoB63HnGpHpUc5L6Ru8UVroarLMUF63NU66q40VkVv8qN8lfqGqbSs4JZ4lK10J+TwlRzrW9c1dqIizwtjpileJMBLJE6bwWswDUUsAFc45w8pRU4RAEbwCGymtQKHKvAXuw2gL2UNI8VuKACZQNoFwtxQX8pAgxLELmXYqC7ZIT8G2uwjmn8o8O22rgg94RaTs0Bfa3CqFycq8UKB+v80GMARSX/eDfQnZMs3DkJfc+297igx4COY52Kdx5f0sW+CgR5DwqncmUDUMXOWQErcG0FbADXPj9P/0AF9tyyDWBPNc1lBS6mQNkAIP+cEX8+UfEWPaDWM/ZQc8AYV+OOfFDjinVLcesxshTfCE+rgdqeGjYuWK+NNS2uzg+Zv9WPLNVT5Ua4Ww3kWRU/ZFyrX1uQ6xT/Gs/0vWwAU4GfVsAK3EcBG8B9ztI7eYACe2/RBrC3ouazAhdSwAZwocPyqFZgbwXKBqAuGiBfSFQGVFyqTuEg94Q+V+VSOOi5IMeq7iw5yPMqHSvzQuaCnFNckHGwnlNcan7IXBGnuKo5yPywnqvyKxxkfoWLORirazxlA2hgLytgBb6nwBGdbQBHqGpOK3ARBWwAFzkoj2kFjlDABnCEqua0AhdRoGwAULtogIyDPqe0gR4DOo4XPS1WfEfmWs+4QM8L63k1a+RXGMjcsa7FUMM17HypnvPv795j7Tvs/BvkWSPXUgx9rcJBjwEdq9pKbr6X6R1yjwpXw8CrFl7PiXPt2Worq2wAFTJjrIAVuJYCNoBrnZentQK7KmAD2FVOk1mBaylgA7jWeXnaBypw5JbLBrB26TB9j8NO+fkTXhca8POMdS2e10zv8FMDr/fp2/RstXHBCwvvn7GuGk+9509VO//+7l3Vxpyqh7y/WFeNFb+qhWN7QuZXs8WcmlXlYt1SHGsVLmJavAUXayFrATnX+lZW2QAqZMZYAStwLQVsANc6L09rBXZVwAawq5wmswL7KnA0mw3gaIXNbwVOrMAmA4D1ywdYxyzpA2O1kOviZcpSvDTLPA+Zf/59elc9pm/zJ2Q+6HNz/PQOPQby/yOhzTDh50/ItTCWaz3imvdq75C5W/7IBbWeUMPFPUKtTu0xcrUYxvlUj0pukwFUGhhjBazAeRWwAZz3bDzZwxX4xPZtAJ9Q2T2swEkVsAGc9GA8lhX4hAKbDKBdXKwttQlVswUXa6v88PlLF6j1jHuAXBcxLYYaLmqm4sZXWbDeU/FDroOcG61VdSqn9gi1ORRfzMF+XJG7xWr+lq+sTQZQaWCMFbACv1fgUxU2gE8p7T5W4IQK2ABOeCgeyQp8SoGyAcB+P8dAjQvGcZBroc99SuR5H/XzmsrNa5beod8PIKHAH+iXBIYk9DWg41C2KVRaVHOxcbUO8r4il4oV/xacqo051RPG5m/cZQNoYC8rYAWOV+CTHWwAn1TbvazAyRSwAZzsQDyOFfikAjaAT6rtXlbgZArsbgDQX0ioS4uqBqpW5Sp8o3WKu8oFvRaAoksXdKBxsjgkq7OFMhkqrmpOEhaSQNKjUPYXEmf7myz8I9YtxZEK8qyQc7HuXTzyTc1b5dndAKqNjbMCVuD7CtgAvn8GnsAKfE0BG8DXpHdjK/B9BWwA3z8DT2AF/irwjX8cbgAwfikCuRZyLgq35VIkclVjWJ+rccEYTu1J5aDG32ZZW7Afl5q1moM8B6zn1vb37jvsxw/rXMC7cQ77drgBHDa5ia2AFdisgA1gs4QmsALXVcAGcN2z8+Q3UuBbW7EBfEt597UCJ1BgdwOoXOyofVfqljCRDxj+bbLIpWLI/Go2VTuKU1zVnOpZySl+yHtXuKNzlfmhNitknOKPe1KYai5ytVjVQj9bw+25djeAPYczlxWwAscqYAM4Vl+zW4FVBb4JsAF8U333tgJfVsAG8OUDcHsr8E0FygZQuaCA/sICdFzdMOT6am3EQeZSe1K5yKViyPx746DvofirOTiOC3puoDqWxKkzAdJFL/Q5RQY9Bur/Q1XFF3OQ+SNmKYaxWhira3OUDaCBvayAFdhXgW+z2QC+fQLubwW+qIAN4Iviu7UV+LYCZQOAsZ8z1M9v1U2P1qo6lYO8J8i5WHv0/FX+Lbgz7Amy1lDLxfmrsdIMck+Fq+TUHJW6JUzkW8KN5ssGMNrAdVbACmgFzpC1AZzhFDyDFfiSAjaALwnvtlbgDArYAM5wCp7BCnxJgbIBxMuIalzdF+SLGKjlKj0gc6k6tS+FizlVB7mnwqncKH+sazHkOWA912rjglyn5o85yHWRu8WxrsUtX1nQ91A1jS8uhYOeC0gwYPWXkUBjEtmGRNxPi6t0ZQOoEhpnBazAdRSwAVznrDypFdhdARvA7pKa0ApcRwEbwHXOypPeRIEzbWOTAYC+4ICf/JbNtsuMuBRfBaPqqjn42Q8gy4B0IRTnajFknCJs2PmqYBpe4VSuYeergml4hYO8J+hzqk7loK+D7/xpvbbXuNS8MRdrWhwxLW75ymrYI9cmAzhyMHNbAStwvAI2gOM1dgcrcFoFbACnPRoPdkcFzrYnG8DZTsTzWIEPKlA2ANjvckZdfqg9Q+6pcDEHtbrqHAoXc3GG38SwPi9kDORcnGsphr5W4aDHAHJbqjbmVGHEtFjhgHTBqnCtfr4qmDl+/q5qY26On96hNmvkajHkWljPtdrRVTaA0QauswJW4LwK2ADOezae7GYKnHE7NoAznopnsgIfUsAG8CGh3cYKnFGBTQYA+YJiugyZnls2PXHMn1v4Yi3k+SNGxZDr5jNO76pW5Sb8/Al9D1WnctDXgY5VbSU3n3F6V3XQ952w86eqm3+f3hUOen6oxYpL5SDzTfNMT8gYxbUlN/V694TxOTYZwJaNudYKPEmBs+7VBnDWk/FcVuADCtgAPiCyW1iBsypQNoB3P4PMv0H+eQT6nBJjzjG9Q18H+k+GQY+r8k995k9VG3Nz/PQO/Qyg4wk/f0LGzr8vvce5WqywLR9XxEFthsjTYlivhYxptZUVZ21xpa6KgfHZqj0quLavuCDPBn2uwr2EKRvAEoHzVsAKvFfgzF9tAGc+Hc9mBQ5WwAZwsMCmtwJnVsAGcObT8WxW4GAFDjeAeKnRYugvMQC5zYaNSwErGFWnckD6k2ewnosztFjxV3Mw1hNyXZslLuhx8XuLoceAjhs2Luix8XuLlRbQ1wEKJnONc74UCEjnO6+Z3iu1ChNzSzHkOSDnpnl++1zqG/OHG0Bs6NgKWIHzKGADOM9ZeBIr8HEFbAAfl9wNrcB5FLABnOcsPMnNFLjCdjYZAORLi7hpWMe0Gsg4qOVa/dqCGtdvL1smPGR+NRPUcBPvuyfUuCDjIq+atZqDzF+tPRIX97gUQ55/CbuWV/tRNaM4yLNCzil+ldtkAIrQOStgBa6jgA3gOmflSa3A7grYAHaX1IRW4M+fq2hgA7jKSXlOK3CAAmUDgHzRoC43Ym7LzJFrKYZ+NtVTOUL5WgAACDBJREFU1SqcykHPDzlW/Ftyao5KTvVUddDvQWGqXBUc9P0A1bKcUz2B9Ft+sJ5TXGoQ6LkURnFBXwf6j7UrPuhrFb/KKS6VKxuAKnbOCliBaytgA7j2+Xn6EypwpZFsAFc6Lc9qBXZWwAaws6CmswJXUmCTAUB/QQE5roqhLjIg80HOxdq9e0a+2K/FEdNiyLPCWK7xVRZk/kpdFdP2GhfknhGjYqjVQcZBzsU9qJ4RsxRD5o98S7Wjecg9R7mqdZsMoNrEOCvwFAWutk8bwNVOzPNagR0VsAHsKKaprMDVFLABXO3EPK8V2FGB3Q1gz4uSyLUUj+oB+dJF9Rjlr3JVcUfPEfkh6wM5p+aHHhe5W1ypAxq0tCIfkH4zMGJaDBmnGkKPi5gWQ4+B+m/9tfq1BZkfcm6NZ/q+uwFMxH5aAStwfgVsAOc/I09oBQ5TwAZwmLQmtgLnV2CTAbSfn+KKW47fWxwxSzHkn21gPdd6xAW5LmJaDOu4pXljHjJXxHwihvU5IGOaHnFV592rLvK8i6Hfg5oVegygYKUc8P87Bni9q/kUGbzw8PNUuEqu2lNxbTIAReicFbAC11HABnCds/KkVmB3BWwAu0tqQitwHQVsANc5K096UgWuPNYmA4CfCwx4vUcx4JWHn2f10kLhKjn46QWvd1UXZ22xwsGLA17PhosLXt/g5xkxLa7wQ/7lkVa751JzxBz87AVe72oGeH2D5edoHaBKZS7Or2JZKJKVWoUB0sUg5JxoKVOxhwLBOP8mA1DDOGcFrMB1FLABXOesPKkV2F0BG8DukprwSQpcfa82gKufoOe3AhsUKBtAvIxocaVvw8Wl6iBfZMBYTvGrHGR+havMHzEtVlzVHPSzqbrWY3RFPuj7Qb6IbL1iXTWGzK9qW4+4oFYb+WCsrvFArq3MFTG/iVvfuKCfI35vserR8pVVNoAKmTFWwApcSwEbwLXOy9OeSIE7jGIDuMMpeg9WYFABG8CgcC6zAndQYHcDgP7SAnKshFMXGdVc5FN1EdPiKg76PbTauKDHABGyGKs5Yg4o/YYZjOHUcJC54lwtVrUx13CVBbln5FqKoa+t9PsNJvZVtRGzFEM/KyChsYcCAenfDYVTud0NQDVxzgrcTYG77McGcJeT9D6swIACNoAB0VxiBe6igA3gLifpfViBAQXKBgBjFw3xEqPF1Tkh94Sci3ywjmk1kHGQcw07X5AxbV9xzWumd8i107f5E3rc/NsV3vfUorrfSs8ql8LB60xg+1Pxqxz0vRRmS65sAFuauNYKWIFzKmADOOe5eCor8BEFbAAfkdlNrMA5FSgbQPz5qhpv2fZoD1VXnaNSW8G0flVcw8YVa+P3FkdMi1s+rpYfWZHnNzGM/exanRN6fsixmhe24eac1VkVbs7zzfeyAXxzSPe2AlbgGAVsAMfoalYrcAkFbACXOCYPaQWOUcAGcIyuZr2hAnfcUtkAIF+ewOdzlUOAPFelroqBzA+13FkuhKCfd8veVW3cp8JsyUX+Fkc+6PcIRMjfGEh/mq7xxfUXvPIPyFwrJW8/j8zwljB8LBtAqHNoBazADRSwAdzgEL0FKzCqgA1gVDnXPUqBu27WBnDXk/W+rEBBgU0GEC8o9o4L8/+FxL5/k+EfkC9nYl2LYR0XqBfDxhcXZH7IuUgaeVocMb+JW/18/aY2Yuc80zvkPUGfm7DzZ+ReiqHngvz/MViqjfl5/+k9YqrxVD9/VmsruDnv9F6pW8JsMoAlUuetgBW4hgI2gGuck6f8ogJ3bm0DuPPpem9WYEUBG8CKQP5sBe6swO4GAPlyBtZze4o8XY6sPVVPVQP9/AqjuKCvg3xR1bgqtQpTzUGeA9ZzW/jbvtYW5BmqPRU39HwKo3LQ1wGlMYD0G4RQy5UaFEFqT8XSP7sbQLWxcVbgCgrcfUYbwN1P2PuzAm8UsAG8EcefrMDdFbAB3P2EvT8r8EaBWxgA9Bcvb/bbfYK+DnQcL1kg4yJmKYax2m7wN4Hq+wb+60+KX+Wg36dqpOoUbs8c9HPB8sXsSF+1J5VT3AoHeV5Yzyl+lbuFAaiNOWcFrMC6AjaAdY2MsAK3VcAGcNuj9caswLoCNoB1jYx4oAJP2bIN4MCThnxZo9rBOg4yBnJO8avLpUpOcakc5DkiP2RMlQtyLeRc7Kn4t+Qiv4ohz7WlZ6xVPVUu1i3FNoAlZZy3Ag9QwAbwgEP2Fq3AkgI2gCVlnH+sAk/a+O4GoH4eqeS2iB75YfznsMjVYuj5Wi4u6DGA3FKsW4qB7k+aSTKRhL4OdBxLIePUbLFuKYaeT3FBjwGW6IbyQKch1H/pB3ItrOfUPqvDQ+av1o7idjeA0UFcZwWswOcVsAF8XnN3tAKnUcAGcJqj8CBnUOBpM9gAnnbi3q8VmCmwyQAgX1rAfrnZnL96rV7EKBzk+SPuV8MEMGR+yLlQVg7jrC1WxdD3VBiVg74O9MVa6ztfkOsU/7xmelc4lYO+RwUDfQ28YlU7zTM9Faaamzjmz0otvOaD988KV8NsMoBG4GUFrMB1FbABXPfsPPnOCjyRzgbwxFP3nq3A/xSwAfxPCD+swBMVKBvA/LLim+9HH5LaW6WnqvtGTs06OkeVS+FirjpDrGtxtfZoXJtlvlS/+fffviu+0Vy1d9kAqoTGWYErKvDUmW0ATz1579sK/KuADeBfEfy3FXiqAjaAp568920F/lXABvCvCP772Qo8efc2gCefvvf+eAVsAI//V8ACPFkBG8CTT997f7wCNoDH/yvwbAGevvv/AgAA//8/4GJgAAAABklEQVQDANVxrTtYeQsiAAAAAElFTkSuQmCC)

扫码加入星球

查看更多优质内容

https://wx.zsxq.com/mweb/views/joingroup/join\_group.html?group\_id=51122554151214