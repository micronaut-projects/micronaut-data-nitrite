package io.micronaut.data.jakarta.tck;

import ee.jakarta.tck.data.standalone.entity.JakartaEventBuiltInRepositoryTest;
import ee.jakarta.tck.data.standalone.entity.JakartaEventCustomRepositoryTest;
import ee.jakarta.tck.data.standalone.persistence.stateful.StatefulPersistenceEntityTests;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

public class FilterExtension implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        Class<?> testClass = context.getTestClass().orElse(null);
        if (testClass == StatefulPersistenceEntityTests.class) {
            // Micronaut Data does not implement Jakarta Data stateful repositories
            return ConditionEvaluationResult.disabled("Stateful repositories are not supported");
        }
        if (testClass == JakartaEventBuiltInRepositoryTest.class || testClass == JakartaEventCustomRepositoryTest.class) {
            // The TCK observes Jakarta Data lifecycle events through CDI. Micronaut Data delivers them to
            // ApplicationEventListener beans instead, and does not emit the upsert events these tests expect.
            return ConditionEvaluationResult.disabled("Jakarta Data lifecycle events are observed via CDI");
        }
        return ConditionEvaluationResult.enabled(null);
    }

}
