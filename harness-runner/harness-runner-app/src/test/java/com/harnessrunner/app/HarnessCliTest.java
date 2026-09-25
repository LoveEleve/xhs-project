package com.harnessrunner.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarnessCliTest {

    private static final Pattern CHANGE_ID = Pattern.compile("(chg-[0-9a-f]{8})");

    @TempDir
    Path tempDir;

    private final HarnessCli cli = new HarnessCli();

    private String runCli(String... args) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        cli.run(args, new PrintStream(buffer, true, StandardCharsets.UTF_8));
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private String createChange() {
        String output = runCli("create",
                "--requirement", "完善读写分离",
                "--project", tempDir.toString(),
                "--project-id", "proj-1",
                "--workspace", tempDir.resolve("ws").toString());
        Matcher matcher = CHANGE_ID.matcher(output);
        assertTrue(matcher.find(), output);
        return matcher.group(1);
    }

    @Test
    void createThenStatusShowsCreatedState() {
        String changeId = createChange();

        String status = runCli("status", "--change", changeId,
                "--workspace", tempDir.resolve("ws").toString());

        assertTrue(status.contains("状态: CREATED"), status);
        assertTrue(status.contains("完善读写分离"), status);
        assertTrue(status.contains("项目目录: " + tempDir), status);
    }

    @Test
    void advanceGeneratesAcThenWaitsForApproval() {
        String changeId = createChange();
        String workspace = tempDir.resolve("ws").toString();

        String advance = runCli("advance", "--change", changeId, "--workspace", workspace);

        assertTrue(advance.contains("需求分析"), advance);
        assertTrue(advance.contains("AC_TESTABLE"), advance);
        assertTrue(advance.contains("AC 可测性校验通过"), advance);
        assertTrue(advance.contains("等待人工确认"), advance);
        assertTrue(advance.contains("状态: AWAITING_APPROVAL"), advance);

        String refused = runCli("advance", "--change", changeId, "--workspace", workspace);
        assertTrue(refused.contains("请先 approve"), refused);

        ByteArrayOutputStream refusedBuffer = new ByteArrayOutputStream();
        int refusedCode = cli.run(new String[]{"advance", "--change", changeId, "--workspace", workspace},
                new PrintStream(refusedBuffer, true, StandardCharsets.UTF_8));
        assertEquals(3, refusedCode);

        String approve = runCli("approve", "--change", changeId, "--workspace", workspace);
        assertTrue(approve.contains("审批通过"), approve);
        assertTrue(approve.contains("当前阶段 编码实现"), approve);

        String status = runCli("status", "--change", changeId, "--workspace", workspace);
        assertTrue(status.contains("HARNESSING"), status);
        assertTrue(status.contains("PRD"), status);
        assertTrue(status.contains("LLM 调用:"), status);
        assertTrue(status.contains("APPROVED"), status);
    }

    @Test
    void verifyReportsIntactChainAndDetectsTampering() throws IOException {
        String changeId = createChange();
        String workspace = tempDir.resolve("ws").toString();

        String intact = runCli("verify", "--change", changeId, "--workspace", workspace);
        assertTrue(intact.contains("完整"), intact);
        assertTrue(intact.contains("已校验"), intact);

        Path events = Path.of(workspace, "changes", changeId, "events.jsonl");
        Files.writeString(events, Files.readString(events).replace("完善读写分离", "被篡改的需求"));

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int exitCode = cli.run(new String[]{"verify", "--change", changeId, "--workspace", workspace},
                new PrintStream(buffer, true, StandardCharsets.UTF_8));
        String tampered = buffer.toString(StandardCharsets.UTF_8);
        assertEquals(1, exitCode, tampered);
        assertTrue(tampered.contains("断裂"), tampered);
        assertTrue(tampered.contains("疑似篡改"), tampered);
    }

    @Test
    void approveWithoutAwaitingStateFails() {
        String changeId = createChange();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        int exitCode = cli.run(new String[]{"approve", "--change", changeId,
                        "--workspace", tempDir.resolve("ws").toString()},
                new PrintStream(buffer, true, StandardCharsets.UTF_8));

        assertEquals(1, exitCode);
        assertTrue(buffer.toString(StandardCharsets.UTF_8).contains("无需审批"));
    }

    @Test
    void usageErrorReturnsTwo() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int exitCode = cli.run(new String[]{"create"}, new PrintStream(buffer, true, StandardCharsets.UTF_8));

        assertEquals(2, exitCode);
        assertTrue(buffer.toString(StandardCharsets.UTF_8).contains("错误"));
    }

    @Test
    void unknownCommandPrintsUsage() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int exitCode = cli.run(new String[]{"deploy"}, new PrintStream(buffer, true, StandardCharsets.UTF_8));

        assertEquals(2, exitCode);
        assertTrue(buffer.toString(StandardCharsets.UTF_8).contains("用法"));
    }

    @Test
    void unknownChangeReturnsOne() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int exitCode = cli.run(new String[]{"status", "--change", "chg-nope",
                "--workspace", tempDir.resolve("ws").toString()},
                new PrintStream(buffer, true, StandardCharsets.UTF_8));

        assertEquals(1, exitCode);
        assertTrue(buffer.toString(StandardCharsets.UTF_8).contains("变更不存在"));
    }
}
