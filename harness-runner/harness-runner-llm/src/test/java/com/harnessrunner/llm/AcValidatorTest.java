package com.harnessrunner.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcValidatorTest {

    @Test
    void validCriteriaHaveNoProblems() {
        List<String> problems = AcValidator.problems(List.of(
                new AcceptanceCriterion("AC-1", "主流程可验证"),
                new AcceptanceCriterion("AC-2", "边界可验证")));

        assertTrue(problems.isEmpty(), problems.toString());
    }

    @Test
    void detectsEmptyList() {
        assertEquals(List.of("AC 列表为空"), AcValidator.problems(List.of()));
        assertEquals(List.of("AC 列表为空"), AcValidator.problems(null));
    }

    @Test
    void detectsDuplicateIdAndBlankFields() {
        List<String> problems = AcValidator.problems(List.of(
                new AcceptanceCriterion("AC-1", "有效"),
                new AcceptanceCriterion("AC-1", "重复 id"),
                new AcceptanceCriterion("", "缺 id"),
                new AcceptanceCriterion("AC-4", " ")));

        assertEquals(3, problems.size());
        assertTrue(problems.stream().anyMatch(problem -> problem.contains("重复")));
        assertTrue(problems.stream().anyMatch(problem -> problem.contains("缺少 id")));
        assertTrue(problems.stream().anyMatch(problem -> problem.contains("缺少可测描述")));
    }
}
