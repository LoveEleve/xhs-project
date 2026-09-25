package com.harnessrunner.llm;

public record AcceptanceCriterion(String id, String statement) {

    public AcceptanceCriterion {
        id = id == null ? "" : id.strip();
        statement = statement == null ? "" : statement.strip();
    }
}
