package com.harnessrunner.gate;

import java.nio.file.Path;

public interface CommandExecutor {

    ProcessResult execute(GateCommand command, Path logFile);

    default ProcessResult execute(GateCommand command) {
        return execute(command, null);
    }
}
