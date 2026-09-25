package com.harnessrunner.assets;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateRendererTest {

    private final TemplateRenderer renderer = new TemplateRenderer();

    @Test
    void replacesKnownPlaceholders() {
        String template = "项目 {{PROJECT_NAME}} 使用 {{LANGUAGE}} + {{BUILD_TOOL}}";

        String rendered = renderer.render(template, Map.of(
                "PROJECT_NAME", "my-xhs",
                "LANGUAGE", "Java",
                "BUILD_TOOL", "Maven"));

        assertEquals("项目 my-xhs 使用 Java + Maven", rendered);
    }

    @Test
    void keepsUnknownPlaceholdersUntouched() {
        String rendered = renderer.render("Hello {{UNKNOWN}}", Map.of("OTHER", "x"));

        assertEquals("Hello {{UNKNOWN}}", rendered);
    }

    @Test
    void toleratesSpacesAndRepeatedPlaceholders() {
        String rendered = renderer.render("{{ A }} and {{A}} then {{ B }}",
                Map.of("A", "1", "B", "2"));

        assertEquals("1 and 1 then 2", rendered);
    }

    @Test
    void doesNotBreakOnDollarOrBackslashValues() {
        String rendered = renderer.render("path={{P}}", Map.of("P", "$HOME\\bin"));

        assertTrue(rendered.equals("path=$HOME\\bin"));
    }

    @Test
    void emptyTemplateReturnsEmpty() {
        assertEquals("", renderer.render(null, Map.of()));
        assertEquals("", renderer.render("", Map.of()));
    }
}
