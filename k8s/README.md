# MyXHS K8s 部署指南

## 前置条件
- K8s 集群 (v1.24+)
- kubectl 已配置
- Ingress Controller (nginx-ingress)

## 部署步骤

1. 创建 Namespace
```bash
kubectl apply -f namespace.yaml
```

2. 创建 ConfigMap
```bash
kubectl apply -f configmap.yaml
```

3. 部署服务（以 user 为例，SERVICE_NAME 替换为实际服务名）
```bash
sed 's/SERVICE_NAME/my-xhs-user/g' deployment-template.yaml | kubectl apply -f -
sed 's/SERVICE_NAME/my-xhs-user/g' service-template.yaml | kubectl apply -f -
```

4. 创建 Ingress
```bash
kubectl apply -f ingress-template.yaml
```

5. 查看状态
```bash
kubectl get all -n myxhs
```

## 注意事项
- 模板中的 `SERVICE_NAME` 需要替换为实际服务名
- 数据库/Redis/RocketMQ 等基础设施建议单独部署
- 生产环境需配置 Secret 替代 ConfigMap 中的敏感信息
