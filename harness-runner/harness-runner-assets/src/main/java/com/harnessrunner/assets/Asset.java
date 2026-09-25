package com.harnessrunner.assets;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record Asset(String name, String description, Path path, Map<String, String> attributes) {

    public Asset {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(path, "path");
        description = description == null ? "" : description;
        attributes = attributes == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }
}
