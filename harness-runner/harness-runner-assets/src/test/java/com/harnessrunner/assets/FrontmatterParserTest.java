package com.harnessrunner.assets;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontmatterParserTest {

    private final FrontmatterParser parser = new FrontmatterParser();

    @Test
    void parsesSkillFrontmatterLikeHarnessAssets() {
        String markdown = """
                ---
                name: coding-skill
                stage: ② 编码实现
                description: "按已确认的需求卡与编码规范，小步实现可编译的代码"
                ---

                # 编码实现技能

                正文内容
                """;

        Frontmatter frontmatter = parser.parse(markdown).orElseThrow();
        assertEquals("coding-skill", frontmatter.attribute("name"));
        assertEquals("② 编码实现", frontmatter.attribute("stage"));
        assertEquals("按已确认的需求卡与编码规范，小步实现可编译的代码",
                frontmatter.attribute("description"));
        assertTrue(frontmatter.body().contains("# 编码实现技能"));
        assertTrue(frontmatter.body().contains("正文内容"));
        assertFalse(frontmatter.body().contains("---"));
    }

    @Test
    void emptyWhenNoFrontmatter() {
        assertTrue(parser.parse("# 普通文档\n\n没有 frontmatter").isEmpty());
        assertTrue(parser.parse("").isEmpty());
        assertTrue(parser.parse(null).isEmpty());
    }

    @Test
    void emptyWhenFenceNotClosed() {
        assertTrue(parser.parse("---\nname: x\n\n正文").isEmpty());
    }

    @Test
    void toleratesCommentsAndMalformedLines() {
        String markdown = """
                ---
                # 注释行
                name: demo
                这一行没有冒号
                key:
                ---
                body
                """;

        Frontmatter frontmatter = parser.parse(markdown).orElseThrow();
        assertEquals("demo", frontmatter.attribute("name"));
        assertEquals("", frontmatter.attribute("key"));
        assertEquals("body", frontmatter.body().strip());
    }
}
