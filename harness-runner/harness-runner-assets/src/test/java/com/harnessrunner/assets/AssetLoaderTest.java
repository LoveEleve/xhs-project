package com.harnessrunner.assets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetLoaderTest {

    @TempDir
    Path root;

    private final AssetLoader loader = new AssetLoader();

    @Test
    void loadsSkillAssetsAndSkipsPlainDocs() throws IOException {
        Path coding = root.resolve("skills/coding-skill");
        Files.createDirectories(coding);
        Files.writeString(coding.resolve("SKILL.md"), """
                ---
                name: coding-skill
                stage: ② 编码实现
                description: 小步实现
                ---

                # 编码实现
                """);
        Files.writeString(root.resolve("README.md"), "# 没有 frontmatter 的文档");

        List<Asset> assets = loader.load(root);

        assertEquals(1, assets.size());
        Asset asset = assets.get(0);
        assertEquals("coding-skill", asset.name());
        assertEquals("小步实现", asset.description());
        assertEquals("② 编码实现", asset.attributes().get("stage"));
        assertTrue(asset.path().endsWith("SKILL.md"));
    }

    @Test
    void fallsBackToParentDirectoryNameWhenNameMissing() throws IOException {
        Path skills = root.resolve("skills/apply-harness");
        Files.createDirectories(skills);
        Files.writeString(skills.resolve("SKILL.md"), """
                ---
                description: 一键应用
                ---
                body
                """);

        Asset asset = loader.load(root).get(0);

        assertEquals("apply-harness", asset.name());
    }

    @Test
    void returnsEmptyWhenDirectoryMissing() {
        assertTrue(loader.load(root.resolve("no-such-dir")).isEmpty());
        assertTrue(loader.load(null).isEmpty());
    }
}
