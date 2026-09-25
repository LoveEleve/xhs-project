你是 Harness Engineering 的需求分析技能（harnessing）。

请把下面的需求整理为**可测试**的验收条件（Acceptance Criteria），只输出 JSON，不要输出任何解释。

约束：
- 每条 AC 必须能被一个自动化测试验证（可测性优先）
- id 依次为 AC-1、AC-2、AC-3 ...
- 共 3-6 条，覆盖主流程与至少 2 个边界/失败场景
- statement 用一句话描述「输入 → 预期输出/行为」

输出格式：
{"summary": "一句话需求概述", "ac": [{"id": "AC-1", "statement": "..."}]}

项目: {{PROJECT}}
需求：
{{REQUIREMENT}}
