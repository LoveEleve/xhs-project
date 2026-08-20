# service-analysis — 代码分析文档（⚠️ 历史参考，可能过时）

> 2026-08-12 标注 | 本目录为 8/6~8/9 期间对 16 个服务的代码分析（120+ 文件）。

## ⚠️ 过时声明

**代码经多轮修复（P0×4、P1×5、P2×8、O1/O2、P-B4 等），本目录结论可能已不成立。**

- 修复后行为请以 **`../review-fresh/`** 为准（review-consolidated.md 汇总 + 15 模块逐篇）
- 生产配置/架构现状以 **`../review-fresh/review-production-config.md`** 为准
- 本目录仅用于**理解代码架构/设计意图**（模块划分、数据流、模式），不用于判断当前缺陷

## 模块 → 最新结论映射

| 本目录 | 对应最新 review |
|---|---|
| 01-user ~ 16-gateway | `../review-fresh/review-<module>.md`（同名模块）|
| 全部模块汇总 | `../review-fresh/review-consolidated.md` |

## 模块清单（16 个）

01-user / 02-content（19 子主题）/ 03-analytics / 04-counter / 05-product / 06-cart / 07-inventory / 08-coupon / 09-order / 10-payment / 11-notification / 12-im / 13-home / 14-search / 15-common / 16-gateway
