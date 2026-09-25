package com.harnessrunner.engine.store.file;

import com.harnessrunner.engine.store.LlmCall;
import com.harnessrunner.engine.store.LlmCallStore;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

public final class FileLlmCallStore implements LlmCallStore {

    private final Workspace workspace;
    private final JsonCodec codec;

    public FileLlmCallStore(Workspace workspace, JsonCodec codec) {
        this.workspace = workspace;
        this.codec = codec;
    }

    @Override
    public void save(LlmCall call, String prompt, String response) {
        Path dir = workspace.llmDir(call.changeId());
        try {
            Files.createDirectories(dir);
            AtomicFiles.writeString(dir.resolve(call.id() + "-prompt.txt"), prompt);
            AtomicFiles.writeString(dir.resolve(call.id() + "-response.json"),
                    response.isEmpty() ? "(empty)" : response);
            Path index = workspace.llmCallsFile(call.changeId());
            boolean tornTail = Files.isRegularFile(index) && !endsWithNewline(index);
            try (BufferedWriter writer = Files.newBufferedWriter(index, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (tornTail) {
                    writer.newLine();
                }
                writer.write(codec.writeCompact(call));
                writer.newLine();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("保存 LLM 调用记录失败: " + call.id(), e);
        }
    }

    @Override
    public List<LlmCall> findByChangeId(String changeId) {
        Path index = workspace.llmCallsFile(changeId);
        if (!Files.isRegularFile(index)) {
            return List.of();
        }
        try {
            List<LlmCall> calls = new ArrayList<>();
            for (String line : Files.readAllLines(index, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                LlmCall call = tryParse(line);
                if (call != null) {
                    calls.add(call);
                }
            }
            return List.copyOf(calls);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 LLM 调用记录失败: " + index, e);
        }
    }

    private LlmCall tryParse(String line) {
        try {
            return codec.read(line, LlmCall.class);
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private static boolean endsWithNewline(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        return bytes.length > 0 && bytes[bytes.length - 1] == '\n';
    }
}
