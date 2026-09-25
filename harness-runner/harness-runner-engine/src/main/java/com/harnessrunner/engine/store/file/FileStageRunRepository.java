package com.harnessrunner.engine.store.file;

import com.fasterxml.jackson.core.type.TypeReference;
import com.harnessrunner.domain.change.Stage;
import com.harnessrunner.engine.store.StageRunRecord;
import com.harnessrunner.engine.store.StageRunRepository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class FileStageRunRepository implements StageRunRepository {

    private static final TypeReference<List<StageRunRecord>> RECORD_LIST = new TypeReference<>() {
    };

    private final Workspace workspace;
    private final JsonCodec codec;

    public FileStageRunRepository(Workspace workspace, JsonCodec codec) {
        this.workspace = workspace;
        this.codec = codec;
    }

    @Override
    public void save(StageRunRecord record) {
        List<StageRunRecord> records = new ArrayList<>(findByChangeId(record.changeId()));
        records.removeIf(existing -> existing.id().equals(record.id()));
        records.add(record);
        records.sort(Comparator.comparing(StageRunRecord::startedAt).thenComparing(StageRunRecord::attempt));
        Path file = workspace.stageRunsFile(record.changeId());
        try {
            Files.createDirectories(file.getParent());
            AtomicFiles.writeString(file, codec.write(records));
        } catch (IOException e) {
            throw new UncheckedIOException("保存阶段运行失败: " + file, e);
        }
    }

    @Override
    public List<StageRunRecord> findByChangeId(String changeId) {
        Path file = workspace.stageRunsFile(changeId);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            return codec.read(Files.readString(file, StandardCharsets.UTF_8), RECORD_LIST);
        } catch (IOException e) {
            throw new UncheckedIOException("读取阶段运行失败: " + file, e);
        }
    }

    @Override
    public int nextAttempt(String changeId, Stage stage) {
        return findByChangeId(changeId).stream()
                .filter(record -> record.stage() == stage)
                .mapToInt(StageRunRecord::attempt)
                .max()
                .orElse(0) + 1;
    }
}
