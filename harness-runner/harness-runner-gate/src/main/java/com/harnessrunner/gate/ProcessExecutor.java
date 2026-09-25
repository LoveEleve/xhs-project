package com.harnessrunner.gate;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class ProcessExecutor implements CommandExecutor {

    public static final int DEFAULT_MAX_OUTPUT_CHARS = 256 * 1024;

    private final int maxOutputChars;

    public ProcessExecutor() {
        this(DEFAULT_MAX_OUTPUT_CHARS);
    }

    public ProcessExecutor(int maxOutputChars) {
        this.maxOutputChars = maxOutputChars;
    }

    @Override
    public ProcessResult execute(GateCommand command, Path logFile) {
        long startNanos = System.nanoTime();
        BoundedOutputCollector collector = new BoundedOutputCollector(maxOutputChars);
        BufferedWriter logWriter = openLog(logFile);

        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command.argv())
                    .directory(command.workingDir().toFile());
            builder.environment().putAll(command.env());
            process = builder.start();
        } catch (IOException e) {
            closeQuietly(logWriter);
            return new ProcessResult(command.display(), -1, false, elapsedMs(startNanos),
                    "命令启动失败: " + e.getMessage(), false,
                    logFile == null ? null : logFile.toString());
        }

        Thread stdout = startReader(process.getInputStream(), collector, logWriter, "stdout");
        Thread stderr = startReader(process.getErrorStream(), collector, logWriter, "stderr");

        boolean finished;
        try {
            finished = process.waitFor(command.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            killTree(process);
            awaitQuietly(process, 5, TimeUnit.SECONDS);
            finishCollecting(process, stdout, stderr, logWriter);
            throw new IllegalStateException("等待命令被中断: " + command.display(), e);
        }

        if (!finished) {
            killTree(process);
            awaitQuietly(process, 5, TimeUnit.SECONDS);
        }

        finishCollecting(process, stdout, stderr, logWriter);

        int exitCode = process.isAlive() ? -1 : process.exitValue();
        return new ProcessResult(command.display(), exitCode, !finished, elapsedMs(startNanos),
                collector.text(), collector.truncated(), logFile == null ? null : logFile.toString());
    }

    private Thread startReader(InputStream stream, BoundedOutputCollector collector,
                               BufferedWriter logWriter, String name) {
        Thread thread = new Thread(() -> {
            char[] buffer = new char[4096];
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    collector.append(buffer, read);
                    writeLog(logWriter, buffer, read);
                }
            } catch (IOException ignored) {
            }
        }, "gate-" + name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void killTree(Process process) {
        List<ProcessHandle> descendants = new ArrayList<>(process.descendants().toList());
        process.destroyForcibly();
        for (ProcessHandle descendant : descendants) {
            if (descendant.isAlive()) {
                descendant.destroyForcibly();
            }
        }
    }

    private static BufferedWriter openLog(Path logFile) {
        if (logFile == null) {
            return null;
        }
        try {
            if (logFile.getParent() != null) {
                Files.createDirectories(logFile.getParent());
            }
            return Files.newBufferedWriter(logFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("日志文件创建失败: " + logFile, e);
        }
    }

    private static void writeLog(BufferedWriter logWriter, char[] chars, int length) {
        if (logWriter == null) {
            return;
        }
        synchronized (logWriter) {
            try {
                logWriter.write(chars, 0, length);
            } catch (IOException ignored) {
            }
        }
    }

    private static void finishCollecting(Process process, Thread stdout, Thread stderr, BufferedWriter logWriter) {
        joinQuietly(stdout);
        joinQuietly(stderr);
        closeQuietly(process.getInputStream());
        closeQuietly(process.getErrorStream());
        closeQuietly(logWriter);
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitQuietly(Process process, long timeout, TimeUnit unit) {
        try {
            process.waitFor(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
