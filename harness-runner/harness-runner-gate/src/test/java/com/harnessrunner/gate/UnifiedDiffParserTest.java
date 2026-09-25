package com.harnessrunner.gate;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnifiedDiffParserTest {

    private final UnifiedDiffParser parser = new UnifiedDiffParser();

    @Test
    void parsesModifiedAndNewFiles() {
        String diff = """
                diff --git a/src/main/java/demo/Calc.java b/src/main/java/demo/Calc.java
                index 111..222 100644
                --- a/src/main/java/demo/Calc.java
                +++ b/src/main/java/demo/Calc.java
                @@ -1,3 +1,4 @@
                 package demo;
                 public class Calc {
                +    // comment
                 }
                @@ -10 +11,2 @@
                -old
                +new1
                +new2
                diff --git a/new.md b/new.md
                new file mode 100644
                --- /dev/null
                +++ b/new.md
                @@ -0,0 +1,3 @@
                +a
                +b
                +c
                """;

        Map<String, List<LineRange>> changed = parser.parse(diff);

        assertEquals(2, changed.size());
        assertEquals(List.of(new LineRange(1, 4), new LineRange(11, 12)),
                changed.get("src/main/java/demo/Calc.java"));
        assertEquals(List.of(new LineRange(1, 3)), changed.get("new.md"));
    }

    @Test
    void ignoresDeletedFilesAndEmptyInput() {
        String deletion = """
                diff --git a/old.txt b/old.txt
                deleted file mode 100644
                --- a/old.txt
                +++ /dev/null
                @@ -1 +0,0 @@
                -gone
                """;

        assertTrue(parser.parse(deletion).isEmpty());
        assertTrue(parser.parse("").isEmpty());
        assertTrue(parser.parse(null).isEmpty());
    }
}
