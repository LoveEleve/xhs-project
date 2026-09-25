package com.harnessrunner.assets;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class AssetLoader {

    private final FrontmatterParser parser = new FrontmatterParser();

    public List<Asset> load(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return List.of();
        }
        List<Asset> assets = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(path -> parse(path).ifPresent(assets::add));
        } catch (IOException e) {
            throw new UncheckedIOException("扫描资产目录失败: " + root, e);
        }
        return List.copyOf(assets);
    }

    private java.util.Optional<Asset> parse(Path path) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            return parser.parse(content).map(frontmatter -> new Asset(
                    nameOf(path, frontmatter),
                    frontmatter.attribute("description"),
                    path,
                    frontmatter.attributes()));
        } catch (IOException e) {
            throw new UncheckedIOException("读取资产失败: " + path, e);
        }
    }

    private static String nameOf(Path path, Frontmatter frontmatter) {
        String declared = frontmatter.attribute("name");
        if (declared != null && !declared.isBlank()) {
            return declared.strip();
        }
        Path parent = path.getParent();
        if (parent != null && path.getFileName().toString().equalsIgnoreCase("SKILL.md")) {
            return parent.getFileName().toString();
        }
        return path.getFileName().toString();
    }
}
