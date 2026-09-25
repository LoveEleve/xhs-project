package com.harnessrunner.gate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitDiffProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void producesUnifiedDiffAgainstBaseRef() throws Exception {
        git("init");
        git("config", "user.email", "test@example.com");
        git("config", "user.name", "test");
        write("src/A.java", "class A {}\n");
        git("add", ".");
        git("-c", "commit.gpgsign=false", "commit", "-m", "base");

        write("src/A.java", "class A { int x; }\n");

        String diff = new GitDiffProvider(new ProcessExecutor()).diff(tempDir, "HEAD");

        assertTrue(diff.contains("+++ b/src/A.java"), diff);
        Map<String, List<LineRange>> changed = new UnifiedDiffParser().parse(diff);
        assertEquals(List.of(new LineRange(1, 1)), changed.get("src/A.java"));
    }

    private void write(String relative, String content) throws IOException {
        Path file = tempDir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private void git(String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command)
                .directory(tempDir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), "git " + String.join(" ", args) + " 失败: " + output);
    }
}
