package com.harnessrunner.gate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessExecutorTest {

    @TempDir
    Path tempDir;

    private final ProcessExecutor executor = new ProcessExecutor();

    private GateCommand sh(String script, Duration timeout) {
        return GateCommand.of(List.of("/bin/sh", "-c", script), tempDir, timeout);
    }

    @Test
    void capturesExitCodeAndMergedOutput() {
        ProcessResult result = executor.execute(sh("echo hello; echo oops >&2; exit 0", Duration.ofSeconds(10)));
        assertTrue(result.succeeded());
        assertEquals(0, result.exitCode());
        assertFalse(result.timedOut());
        assertTrue(result.output().contains("hello"), result.output());
        assertTrue(result.output().contains("oops"), result.output());
    }

    @Test
    void reportsNonZeroExitCode() {
        ProcessResult result = executor.execute(sh("echo boom; exit 3", Duration.ofSeconds(10)));
        assertFalse(result.succeeded());
        assertEquals(3, result.exitCode());
        assertFalse(result.timedOut());
    }

    @Test
    void startupFailureIsReportedAsResultNotException() {
        GateCommand command = GateCommand.of(List.of("/definitely/missing/binary-xyz"),
                tempDir, Duration.ofSeconds(5));
        ProcessResult result = executor.execute(command);
        assertEquals(-1, result.exitCode());
        assertFalse(result.timedOut());
        assertTrue(result.output().contains("命令启动失败"), result.output());
    }

    @Test
    void timeoutKillsProcessAndReportsTimedOut() {
        long start = System.nanoTime();
        ProcessResult result = executor.execute(sh("sleep 30", Duration.ofMillis(400)));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(result.timedOut());
        assertFalse(result.succeeded());
        assertTrue(elapsedMs < 5000, "超时后应立即返回，实际耗时 " + elapsedMs + " ms");
    }

    @Test
    void timeoutKillsWholeProcessTree() throws Exception {
        Path marker = tempDir.resolve("survivor.txt");
        ProcessResult result = executor.execute(sh(
                "(sleep 1; touch '" + marker + "') & sleep 30", Duration.ofMillis(300)));
        assertTrue(result.timedOut());
        TimeUnit.MILLISECONDS.sleep(2000);
        assertFalse(Files.exists(marker), "子进程未被清理，进程树强杀失效");
    }

    @Test
    void floodsOutputWithoutBlockingAndTruncatesInMemory() {
        ProcessExecutor small = new ProcessExecutor(8192);
        long start = System.nanoTime();
        ProcessResult result = small.execute(sh(
                "i=0; while [ $i -lt 200000 ]; do echo \"line-$i-padding-padding-padding-padding\"; i=$((i+1)); done",
                Duration.ofSeconds(20)));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(result.succeeded(), result.output());
        assertTrue(elapsedMs < 15000, "输出未并发读取，子进程被管道缓冲阻塞，耗时 " + elapsedMs + " ms");
        assertTrue(result.outputTruncated());
        assertTrue(result.output().contains("保留末尾 8192 字符"), result.output().substring(0, 200));
    }

    @Test
    void writesFullLogToFileEvenWhenMemoryTruncated() throws Exception {
        ProcessExecutor small = new ProcessExecutor(4096);
        Path logFile = tempDir.resolve("logs").resolve("gate.log");
        ProcessResult result = small.execute(sh(
                "i=0; while [ $i -lt 50000 ]; do echo \"line-$i-padding-padding-padding\"; i=$((i+1)); done",
                Duration.ofSeconds(20)), logFile);
        assertTrue(result.succeeded(), result.output());
        assertEquals(logFile.toString(), result.outputRef());
        assertTrue(Files.size(logFile) > 500_000, "完整日志落盘失败，实际 " + Files.size(logFile) + " 字节");
        assertTrue(Files.readString(logFile).contains("line-49999"));
    }

    @Test
    void injectsEnvironmentVariables() {
        GateCommand command = sh("echo value=$HARNESS_TEST_ENV", Duration.ofSeconds(10))
                .withEnv("HARNESS_TEST_ENV", "injected");
        ProcessResult result = executor.execute(command);
        assertTrue(result.output().contains("value=injected"), result.output());
    }
}
