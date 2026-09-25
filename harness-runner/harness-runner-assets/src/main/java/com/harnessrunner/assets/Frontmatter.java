package com.harnessrunner.assets;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record Frontmatter(Map<String, String> attributes, String body) {

    public Frontmatter {
        attributes = attributes == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        body = body == null ? "" : body;
    }

    public String attribute(String name) {
        return attributes.get(name);
    }
}
