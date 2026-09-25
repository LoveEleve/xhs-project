package com.harnessrunner.engine.store.file;

import com.harnessrunner.engine.store.ChangeRecord;
import com.harnessrunner.engine.store.ChangeRepository;
import com.harnessrunner.engine.store.OptimisticLockException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class FileChangeRepository implements ChangeRepository {

    private final Workspace workspace;
    private final JsonCodec codec;

    public FileChangeRepository(Workspace workspace, JsonCodec codec) {
        this.workspace = workspace;
        this.codec = codec;
    }

    @Override
    public void save(ChangeRecord record) {
        Path file = workspace.changeFile(record.id());
        try {
            Files.createDirectories(file.getParent());
            findById(record.id()).ifPresent(existing -> {
                if (record.version() <= existing.version()) {
                    throw new OptimisticLockException(record.id(),
                            "版本未前进（已存 v" + existing.version() + "，待写 v" + record.version() + "）");
                }
            });
            AtomicFiles.writeString(file, codec.write(record));
        } catch (IOException e) {
            throw new UncheckedIOException("保存变更失败: " + file, e);
        }
    }

    @Override
    public Optional<ChangeRecord> findById(String changeId) {
        Path file = workspace.changeFile(changeId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(codec.read(Files.readString(file, StandardCharsets.UTF_8), ChangeRecord.class));
        } catch (IOException e) {
            throw new UncheckedIOException("读取变更失败: " + file, e);
        }
    }
}
