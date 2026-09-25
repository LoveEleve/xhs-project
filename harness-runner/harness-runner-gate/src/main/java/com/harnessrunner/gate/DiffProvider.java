package com.harnessrunner.gate;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public interface DiffProvider {

    String diff(Path workingDir, String baseRef);

    default Map<String, List<LineRange>> changedLines(String diffOutput) {
        return new UnifiedDiffParser().parse(diffOutput);
    }
}
