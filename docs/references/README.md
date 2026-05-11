# 参考资料笔记

## 📚 目的与原则

本目录用于记录来自参考项目的可借鉴之处，**仅作为学习参考，绝对不能照搬代码或设计**。

### ⚠️ 重要原则
1. **仅参考，不拷贝**：所有记录的设计思路和代码实现都只是参考，必须加入自己的思考
2. **要有创新**：在参考的基础上，要有自己的优化和改进
3. **记录思考**：每个参考点都要记录自己的思考和优化方向
4. **注明来源**：明确标注参考来源（xhs_hz 或 huazai-ecshop）

## 📂 目录结构

```
references/
├── README.md                           # 本文件
├── xhs_hz-参考笔记.md                 # xhs_hz 项目参考笔记
├── huazai-ecshop-参考笔记.md          # huazai-ecshop 项目参考笔记
└── 技术选型对比.md                     # 技术选型对比分析
```

## 🔍 参考项目概览

### 1. xhs_hz（华仔电商实战项目）
- **项目类型**：小红书社交+电商微服务实战
- **技术栈**：Spring Cloud Alibaba + Redis + RocketMQ + Elasticsearch + ShardingSphere + Canal + Sentinel + Gateway + Nacos + XXL-Job + SkyWalking
- **部署**：Rancher + Docker + Jenkins
- **特点**：包含100+篇详细实战文档，覆盖从基础功能到高级架构的完整开发过程

### 2. huazai-ecshop（华仔电商项目）
- **项目类型**：电商微服务项目
- **技术栈**：Spring Cloud Alibaba + Redis + RocketMQ + Elasticsearch + ShardingSphere
- **模块**：huazai-common、huazai-gateway、huazai-order、huazai-inventory、huazai-coupon、huazai-product、huazai-social、huazai-cart、huazai-push、huazai-im、huazai-pay、huazai-food、huazai-admin、huazai-monitor、huazai-home、huazai-front、huazai-admin-front
- **特点**：完整的电商微服务架构，包含前台和后台管理系统

## 📝 笔记记录规范

每个参考点应包含：
1. **参考来源**：来自哪个项目、哪个文档/模块
2. **参考内容**：简要描述参考的设计或实现
3. **自己的思考**：如何优化、改进、创新
4. **应用计划**：在 my-xhs 中如何应用

---

**下一步**：开始整理参考笔记
