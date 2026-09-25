package com.harnessrunner.engine.store.file;

import com.harnessrunner.domain.change.ChangeEvent;
import com.harnessrunner.engine.store.EventHasher;
import com.harnessrunner.engine.store.EventLog;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

public final class FileEventLog implements EventLog {

    private final Workspace workspace;
    private final JsonCodec codec;

    public FileEventLog(Workspace workspace, JsonCodec codec) {
        this.workspace = workspace;
        this.codec = codec;
    }

    @Override
    public void append(ChangeEvent event) {
        Path file = workspace.eventsFile(event.changeId());
        try {
            Files.createDirectories(file.getParent());
            ChangeEvent chained = event.withChain(previousHash(event.changeId()), null);
            chained = chained.withChain(chained.prevHash(), EventHasher.hash(chained, chained.prevHash()));
            boolean tornTail = Files.isRegularFile(file) && !endsWithNewline(file);
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (tornTail) {
                    writer.newLine();
                }
                writer.write(codec.writeCompact(chained));
                writer.newLine();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("追加事件失败: " + file, e);
        }
    }

    private String previousHash(String changeId) {
        List<ChangeEvent> existing = findByChangeId(changeId);
        for (int i = existing.size() - 1; i >= 0; i--) {
            if (existing.get(i).hashed()) {
                return existing.get(i).hash();
            }
        }
        return EventHasher.GENESIS;
    }

    @Override
    public List<ChangeEvent> findByChangeId(String changeId) {
        Path file = workspace.eventsFile(changeId);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            List<ChangeEvent> events = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                ChangeEvent event = tryParse(line);
                if (event != null) {
                    events.add(event);
                }
            }
            return List.copyOf(events);
        } catch (IOException e) {
            throw new UncheckedIOException("读取事件失败: " + file, e);
        }
    }

    private ChangeEvent tryParse(String line) {
        try {
            return codec.read(line, ChangeEvent.class);
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private static boolean endsWithNewline(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        return bytes.length > 0 && bytes[bytes.length - 1] == '\n';
    }
}
