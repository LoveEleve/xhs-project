---
name: excel-toolkit
stage: 组件封装
description: Excel 工具封装——模板导出（填充+样式）、大数据量分批导出（SXSSF）、导入校验（行级别错误收集）、动态列/合并单元格
---

# Excel 工具封装（excel-toolkit）

## 1. 职责

为项目生成 Excel 导入导出基础设施代码，包括模板导出（数据填充+样式）、大数据量分批导出（SXSSF 流式写入）、导入校验（行级别错误收集）、动态列/合并单元格。**禁止在业务代码中直接操作 POI 而不经过封装层**。

## 2. 触发方式

| 方式 | 说明 |
|------|------|
| 独立命令 | `/excel-toolkit [export|import|template|bigdata|all]` |
| 自动加载 | coding-skill 阶段检测到 `Workbook` / `XSSFWorkbook` / `EasyExcel` / `openpyxl` / `xlsxwriter` 时 |

## 3. 前置条件

- 项目已识别技术栈+Excel 库（Java EasyExcel/POI / Python openpyxl/xlsxwriter / Go excelize）
- 已确定目标包路径（按语言规范）
- 已确定导入导出字段列表（至少列名+数据类型）
- 用户未指定 → 生成全部组件（`all`）

## 4. 工作流程

### Step 1: 确定生成目标

| 子命令 | 组件 | 检测条件 |
|--------|------|---------|
| `export` | 导出器 | 有导出需求 |
| `import` | 导入器+校验 | 有导入需求 |
| `template` | 模板导出 | 导出需要固定样式模板 |
| `bigdata` | 大数据量导出 | 导出数据量 > 10000 行 |
| `all`（默认） | 全部组件 | 首次引入 Excel 处理 |

### Step 2: 生成代码文件

```
# Java 示例
{basePackage}/excel/
├── core/
│   ├── ExcelExporter.java              — 导出器（通用导出/大数据量导出自动选择）
│   ├── ExcelImporter.java              — 导入器（流式读取+行级别校验）
│   ├── ExcelColumn.java                — 列定义注解（列名/宽度/格式化/排序）
│   └── ExcelError.java                 — 导入错误记录（行号/列名/错误信息）
├── export/
│   ├── SimpleExporter.java             — 普通导出（<10000 行，XSSFWorkbook）
│   ├── BigDataExporter.java            — 大数据量导出（SXSSF，流式写入）
│   ├── TemplateExporter.java           — 模板导出（加载模板+填充数据）
│   └── StyleConfig.java                — 样式配置（表头/数据行/奇偶行/合并单元格）
├── import/
│   ├── StreamImporter.java             — 流式导入器（逐行读取，不加载全部到内存）
│   ├── ImportValidator.java            — 导入校验器（必填/格式/长度/唯一性/枚举）
│   └── ImportResult.java               — 导入结果（成功行数/失败行数/错误列表）
├── config/
│   └── ExcelConfig.java                — 配置（大数据量阈值/默认列宽/最大行数）
└── web/
    └── ExcelWebSupport.java            — WEB 支持（响应封装/文件下载头/进度回调）

# 其他语言按对应命名规范生成
```

**每个组件的关键实现约束**：

- `ExcelExporter`：数据量 < 10000 行用 XSSFWorkbook（内存中生成），>= 10000 行自动切换 SXSSFWorkbook（流式写入磁盘），支持自定义列顺序、列宽自适应、日期格式化
- `TemplateExporter`：加载 .xlsx 模板文件，定位占位符（{varName}）替换为数据，保留模板样式（字体/颜色/边框/合并单元格），支持列表扩展（{list} 标记自动扩展行）
- 大数据量导出：SXSSF 窗口大小 100 行（内存中保持 100 行，其余刷到磁盘），每 1000 行刷一次磁盘，避免 OOM
- 导入校验：行级别校验（必填/数据类型/长度/枚举值/唯一性），收集所有错误行后统一返回（不遇到第一个错误就停止），校验通过后才写入数据库（事务保证）
- 导入错误 Excel 下载：将错误行+错误信息写入新 Excel，提供下载，方便用户修正后重新导入

### Step 3: 生成测试

```
{basePackage}/excel/
├── core/ExcelExporterTest.java
├── core/ExcelImporterTest.java
├── export/TemplateExporterTest.java
├── export/BigDataExporterTest.java
└── import/ImportValidatorTest.java
```

测试要求：
- 导出：验证普通导出、大数据量自动切换、列宽自适应、日期格式化
- 模板导出：验证占位符替换、列表扩展、样式保留
- 导入：验证流式读取、行级别校验、错误收集、错误 Excel 生成

### Step 4: 验证

1. 编译通过 + 测试通过
2. 检查清单：
   - [ ] 导出自动选择普通/大数据量模式
   - [ ] 大数据量导出使用 SXSSF 流式写入
   - [ ] 导入校验在内存中完成（非逐行写入数据库再校验）
   - [ ] 导入错误行可下载修正
   - [ ] 模板导出保留样式

## 5. 输出文件清单

```
{targetPackage}/excel/
├── core/ExcelExporter.{java|py|go}
├── core/ExcelImporter.{java|py|go}
├── core/ExcelColumn.{java|py|go}
├── core/ExcelError.{java|py|go}
├── export/SimpleExporter.{java|py|go}
├── export/BigDataExporter.{java|py|go}
├── export/TemplateExporter.{java|py|go}
├── export/StyleConfig.{java|py|go}
├── import/StreamImporter.{java|py|go}
├── import/ImportValidator.{java|py|go}
├── import/ImportResult.{java|py|go}
├── config/ExcelConfig.{java|py|go}
├── web/ExcelWebSupport.{java|py|go}
├── core/ExcelExporterTest.{java|py|go}
├── core/ExcelImporterTest.{java|py|go}
├── export/TemplateExporterTest.{java|py|go}
├── export/BigDataExporterTest.{java|py|go}
└── import/ImportValidatorTest.{java|py|go}
```

## 6. 质量门禁

- ✅ 编译通过 + 测试通过
- ✅ 导出自动选择普通/大数据量模式
- ✅ 大数据量导出使用流式写入
- ✅ 导入校验在内存中完成
- ✅ 导入错误行可下载修正
- ❌ 禁止大数据量导出使用 XSSFWorkbook（会 OOM）
- ❌ 禁止导入时逐行写入数据库（应全部校验通过后一次性写入）
- ❌ 禁止导入遇到第一个错误就停止（应收集所有错误）
- ❌ 禁止模板导出硬编码样式（应使用模板文件）

## 7. 约束

- ❌ 不生成 Excel 模板文件（用户提供模板，技能填充数据）
- ❌ 不修改已有的 Excel 处理代码（只新增封装层）
- ❌ 不引入未确认的 Excel 库版本
- ❌ 不生成 Word/PDF 处理代码（只处理 Excel）
---

> **来源 & 作者**
> - 公众号：华仔聊技术
> - 知识星球：华仔·AI高并发全栈训练营
> - 作者：王江华@huazai
