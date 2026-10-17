# CONTEXT.md 格式规范

> 本文件定义 `.harness/CONTEXT.md` 的编写规范。它是"领域语言"（Ubiquitous Language）的活词典，
> 不是规格、不是草稿、不是实现决策的仓库。**它只存术语，其余一律不存。**

---

## 结构

```md
# {Context Name}

{一两句话描述这个上下文是什么、为什么存在。}

## Language

**{术语}:**
{词条定义，说"是什么"，不说"做什么"。}
_Avoid_: {同义词/别名，被淘汰的词}

**{另一个术语}:**
{a request for payment sent to a customer after delivery.}
_Avoid_: {Bill, payment request}

**Customer:**
A person or organization that places orders.
_Avoid_: {Client, buyer, account}
```

## 规则

- **要有主见（Be opinionated）**。同一概念有多个词时，选最好的一个，其余列在 `_Avoid_` 下。
- **定义要克制**。一两句封顶，定义它"是什么"而不是"做什么"。
- **只收本项目上下文特有的概念**。通用编程概念（timeout、error type、工具 pattern）即使大量使用也不收录。加词前自问：这是本上下文独有概念，还是通用编程概念？只有前者属于这里。
- **自然聚类时可加子标题分组**。若所有词条属于同一领域，平铺列表即可。

## 单上下文 vs 多上下文

**单上下文（大多数项目）**：在 `.harness/CONTEXT.md` 一个文件即可。

**多上下文**：在根目录建 `CONTEXT-MAP.md`，列出各上下文、位置、关系：

```md
# Context Map

## Contexts
- [Ordering](./src/ordering/CONTEXT.md) — receives and tracks customer orders
- [Billing](./src/billing/CONTEXT.md) — generates invoices and processes payments

## Relationships
- **Ordering → Billing**: Ordering 发出 `OrderPlaced` 事件；Billing 消费它生成发票
```

推演适用结构：
- 若存在 `CONTEXT-MAP.md` → 读它定位各上下文
- 若只有根 `CONTEXT.md` → 单上下文
- 若都没有 → 在首个术语被敲定时惰性创建根 `CONTEXT.md`

多上下文时，推断当前话题属于哪个上下文；不确定就询问。