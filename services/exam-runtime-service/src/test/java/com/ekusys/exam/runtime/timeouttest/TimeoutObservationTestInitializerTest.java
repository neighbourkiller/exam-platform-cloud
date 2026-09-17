package com.ekusys.exam.runtime.timeouttest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

class TimeoutObservationTestInitializerTest {

    @Test
    void rejectsInitializerWithoutTheDedicatedProfile() {
        GenericApplicationContext context = new GenericApplicationContext();

        assertThatThrownBy(() -> new TimeoutObservationTestInitializer().initialize(context))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("profile");
    }

    @Test
    void rejectsInitializerWithoutTheChecklist() {
        GenericApplicationContext context = new GenericApplicationContext();
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(TimeoutObservationTestInitializer.PROFILE);
        context.setEnvironment(environment);

        assertThatThrownBy(() -> new TimeoutObservationTestInitializer().initialize(context))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("控制清单");
    }

    @Test
    void registersTheObserverOnlyForACompleteChecklist() throws Exception {
        Path root = Files.createTempDirectory("timeout-observer-initializer-");
        try {
            Path checklist = root.resolve("control.properties");
            Properties values = new Properties();
            values.setProperty("runId", "run");
            values.setProperty("examId", "10");
            values.setProperty("targetTaskId", "20");
            values.setProperty("eventFile", root.resolve("events.ndjson").toString());
            values.setProperty("claimLockFile", root.resolve("claim.lock").toString());
            values.setProperty("releaseFile", root.resolve("release").toString());
            values.setProperty("holdEnabled", "false");
            values.setProperty("holdTimeoutSeconds", "15");
            try (var output = Files.newOutputStream(checklist)) {
                values.store(output, "test");
            }

            GenericApplicationContext context = new GenericApplicationContext();
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles(TimeoutObservationTestInitializer.PROFILE);
            environment.setProperty(
                TimeoutObservationTestInitializer.CHECKLIST_PROPERTY, checklist.toString()
            );
            context.setEnvironment(environment);
            new TimeoutObservationTestInitializer().initialize(context);

            assertThat(context.containsBean("timeoutObservationTestImplementation")).isTrue();
            assertThat(context.getBeanFactory().getBean("timeoutObservationTestImplementation"))
                .isInstanceOf(BlockingTimeoutSubmissionObservation.class);
        } finally {
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    }
                });
            }
        }
    }
}
