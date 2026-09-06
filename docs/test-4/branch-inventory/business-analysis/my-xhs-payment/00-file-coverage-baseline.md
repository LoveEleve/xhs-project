# 文件覆盖基线（my-xhs-payment）

来自 `project-directories/my-xhs-payment/inventory.md` 与模块实际扫描。

## 顶层直接子项

| 项 | 说明 |
|---|---|
| docs/ | 含 CODE-REVIEW.md（历史评审） |
| src/ | 源码目录 |
| target/ | 构建输出（排除） |
| Dockerfile | 容器构建 |
| pom.xml | Maven 配置 |

## src/ 展开（实际扫描）

- `src/main/java/com/myxhs/payment/`：config 3、consumer 2、controller 1、dto(request 2/response 1)、entity 3、feign 3、job 4、mapper 1、service 1、simulator 1、strategy(接口 1 + impl 3)、PaymentApplication
- `src/main/resources/`：application.yml、application-datasource.properties、logback-spring.xml、mapper/(PaymentMapper.xml、RefundMapper.xml)
- `src/test/java/com/myxhs/payment/service/`：PaymentServiceTest.java

## 覆盖结论

- 主源码 25 个 java 全部覆盖（PaymentConfig 因死代码已删除）
- 资源覆盖 5/6（application-datasource.properties 为残留不深究）
- 测试 1 个覆盖
- 详见 `03-file-review-matrix.md`
