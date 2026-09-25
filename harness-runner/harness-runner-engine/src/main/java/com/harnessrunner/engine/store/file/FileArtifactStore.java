package com.harnessrunner.engine.store.file;

import com.harnessrunner.domain.artifact.Artifact;
import com.harnessrunner.domain.artifact.ArtifactType;
import com.harnessrunner.engine.store.ArtifactStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class FileArtifactStore implements ArtifactStore {

    private static final Pattern VERSION = Pattern.compile("^v(\\d+)-.*\\.md$");

    private final Workspace workspace;

    public FileArtifactStore(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public Artifact save(String changeId, ArtifactType type, String content) {
        Path dir = workspace.artifactDir(changeId, type);
        try {
            Files.createDirectories(dir);
            int version = nextVersion(dir);
            String fileName = "v" + version + "-" + type.name().toLowerCase(Locale.ROOT) + ".md";
            Path file = dir.resolve(fileName);
            AtomicFiles.writeString(file, content);
            Instant createdAt = Files.getLastModifiedTime(file).toInstant();
            return new Artifact(changeId, type, version, file.toString(), sha256(content), createdAt);
        } catch (IOException e) {
            throw new UncheckedIOException("保存产物失败: " + dir, e);
        }
    }

    @Override
    public List<Artifact> list(String changeId) {
        Path artifactsRoot = workspace.changeDir(changeId).resolve("artifacts");
        if (!Files.isDirectory(artifactsRoot)) {
            return List.of();
        }
        List<Artifact> artifacts = new ArrayList<>();
        try (Stream<Path> files = Files.walk(artifactsRoot)) {
            files.filter(Files::isRegularFile)
                    .map(path -> toArtifact(changeId, path))
                    .flatMap(Optional::stream)
                    .forEach(artifacts::add);
        } catch (IOException e) {
            throw new UncheckedIOException("读取产物失败: " + artifactsRoot, e);
        }
        artifacts.sort(Comparator.comparing((Artifact artifact) -> artifact.type().name())
                .thenComparingInt(Artifact::version));
        return List.copyOf(artifacts);
    }

    @Override
    public Optional<Artifact> latest(String changeId, ArtifactType type) {
        return list(changeId).stream()
                .filter(artifact -> artifact.type() == type)
                .max(Comparator.comparingInt(Artifact::version));
    }

    private Optional<Artifact> toArtifact(String changeId, Path file) {
        Matcher matcher = VERSION.matcher(file.getFileName().toString());
        Path parent = file.getParent();
        if (!matcher.matches() || parent == null || parent.getParent() == null) {
            return Optional.empty();
        }
        try {
            ArtifactType type = ArtifactType.valueOf(
                    parent.getFileName().toString().toUpperCase(Locale.ROOT));
            String content = Files.readString(file, StandardCharsets.UTF_8);
            Instant createdAt = Files.getLastModifiedTime(file).toInstant();
            return Optional.of(new Artifact(changeId, type,
                    Integer.parseInt(matcher.group(1)), file.toString(), sha256(content), createdAt));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("读取产物失败: " + file, e);
        }
    }

    private static int nextVersion(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(path -> VERSION.matcher(path.getFileName().toString()))
                    .filter(Matcher::matches)
                    .mapToInt(matcher -> Integer.parseInt(matcher.group(1)))
                    .max()
                    .orElse(0) + 1;
        }
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
