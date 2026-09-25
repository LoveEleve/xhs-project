package com.harnessrunner.gate;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public final class GitDiffProvider implements DiffProvider {

    private static final Duration DIFF_TIMEOUT = Duration.ofSeconds(60);

    private final CommandExecutor executor;

    public GitDiffProvider(CommandExecutor executor) {
        this.executor = executor;
    }

    @Override
    public String diff(Path workingDir, String baseRef) {
        GateCommand command = GateCommand.of(
                List.of("git", "diff", "--unified=0", baseRef, "--"),
                workingDir, DIFF_TIMEOUT);
        ProcessResult result = executor.execute(command);
        if (!result.succeeded()) {
            throw new IllegalStateException("git diff 失败（exit=" + result.exitCode() + "）: "
                    + result.output().strip());
        }
        return result.output();
    }
}
