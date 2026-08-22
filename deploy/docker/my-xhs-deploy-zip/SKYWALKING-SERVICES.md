# SkyWalking 服务清单(2026-08-13 14:05 窗口)

说明: normal=True 为真实注册服务; normal=False 为 SkyWalking 按调用链推断的虚拟中间件节点。

| 服务名 | 类型 | 层级 | 备注 |
|---|---|---|---|
| 21.130.247.89:13306 | 虚拟节点 | VIRTUAL_DATABASE | ← 旧架构端口, 当前未监听! |
| 21.130.247.89:13307 | 虚拟节点 | VIRTUAL_DATABASE | ← 旧架构端口, 当前未监听! |
| 21.130.247.89:13308 | 虚拟节点 | VIRTUAL_DATABASE | ← 旧架构端口, 当前未监听! |
| 21.130.247.89:13309 | 虚拟节点 | VIRTUAL_DATABASE | ← 旧架构端口, 当前未监听! |
| 21.130.247.89:13310 | 虚拟节点 | VIRTUAL_DATABASE | ← 旧架构端口, 当前未监听! |
| 21.130.247.89:13311 | 虚拟节点 | VIRTUAL_DATABASE | ← 旧架构端口, 当前未监听! |
| 21.130.247.89:13313 | 虚拟节点 | VIRTUAL_DATABASE | ← 旧架构端口, 当前未监听! |
| 21.130.247.89:16379 | 虚拟节点 | VIRTUAL_CACHE | ← 旧架构端口, 当前未监听! |
| 21.130.247.89:26379 | 虚拟节点 | VIRTUAL_CACHE | ← 旧架构端口, 当前未监听! |
| 21.130.247.89:3306 | 虚拟节点 | VIRTUAL_DATABASE | (当前正常) |
| 21.130.247.89:3307 | 虚拟节点 | VIRTUAL_DATABASE | (当前正常) |
| 21.130.247.89:6379 | 虚拟节点 | VIRTUAL_CACHE | (当前正常) |
| 21.130.247.89:6380 | 虚拟节点 | VIRTUAL_CACHE | (当前正常) |
| 21.130.247.89:9876 | 虚拟节点 | VIRTUAL_MQ | (当前正常) |
| Redis-local | 虚拟节点 | VIRTUAL_CACHE | ← 未识别地址 |
| localhost:-1 | 虚拟节点 | VIRTUAL_DATABASE | ← 未识别地址 |
| my-xhs-analytics | 正常服务 | GENERAL | |
| my-xhs-cart | 正常服务 | GENERAL | |
| my-xhs-content | 正常服务 | GENERAL | |
| my-xhs-counter | 正常服务 | GENERAL | |
| my-xhs-coupon | 正常服务 | GENERAL | |
| my-xhs-gateway | 正常服务 | GENERAL | |
| my-xhs-home | 正常服务 | GENERAL | |
| my-xhs-im | 正常服务 | GENERAL | |
| my-xhs-inventory | 正常服务 | GENERAL | |
| my-xhs-notification | 正常服务 | GENERAL | |
| my-xhs-order | 正常服务 | GENERAL | |
| my-xhs-payment | 正常服务 | GENERAL | |
| my-xhs-product | 正常服务 | GENERAL | |
| my-xhs-search | 正常服务 | GENERAL | |
| my-xhs-user | 正常服务 | GENERAL | |
| order | 正常服务 | GENERAL | |
| payment | 正常服务 | GENERAL | |

共 33 个: 正常服务 17 个, 虚拟节点 16 个。