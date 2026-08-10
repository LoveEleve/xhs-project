# my-xhs-home 已知故障
## 一、N+1问题 — Feign聚合5路调用, 任一超时拖慢整体
## 二、大V判定延迟 — 粉丝数刚过阈值可能标记不及时
## 三、Feed断点续推cursor丢失 — Redis记录expire后MQ重投从头开始
## 四、@Profile("dev")误上生产 — H06/H07直写Redis可污染生产数据
