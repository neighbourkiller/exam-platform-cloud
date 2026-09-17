package com.ekusys.exam.runtime.timeouttest;

import com.ekusys.exam.runtime.observation.TimeoutSubmissionObservation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Profiles;

/**
 * Explicit entry point for the isolated timeout-observation add-on.
 *
 * <p>The class is packaged only by the {@code timeout-observer-addon} Maven
 * profile and is never discovered from the normal Runtime executable JAR.</p>
 */
public final class TimeoutObservationTestInitializer
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    public static final String PROFILE = "timeout-observation";
    public static final String CHECKLIST_PROPERTY = "timeout.observation.checklist";

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        if (!context.getEnvironment().acceptsProfiles(Profiles.of(PROFILE))) {
            throw new IllegalStateException(
                "timeout-observation initializer requires the timeout-observation profile"
            );
        }
        String checklistPath = firstNonBlank(
            context.getEnvironment().getProperty(CHECKLIST_PROPERTY),
            System.getProperty(CHECKLIST_PROPERTY),
            System.getenv("TIMEOUT_OBSERVATION_CHECKLIST")
        );
        if (checklistPath == null) {
            throw new IllegalStateException("缺少 timeout-observation 控制清单");
        }
        ObservationChecklist checklist = ObservationChecklist.load(Path.of(checklistPath));
        context.getBeanFactory().registerSingleton(
            "timeoutObservationTestImplementation",
            new BlockingTimeoutSubmissionObservation(checklist)
        );
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    record ObservationChecklist(
        String runId,
        long examId,
        long targetTaskId,
        Path eventFile,
        Path claimLockFile,
        Path releaseFile,
        Path statusSessionIdsFile,
        boolean holdEnabled,
        long holdTimeoutSeconds
    ) {
        boolean examIdEquals(Long value) {
            return value != null && examId == value;
        }

        static ObservationChecklist load(Path path) {
            Properties values = new Properties();
            try (var input = Files.newInputStream(path)) {
                values.load(input);
            } catch (IOException exception) {
                throw new IllegalStateException("无法读取 timeout-observation 控制清单: " + path, exception);
            }

            String runId = required(values, "runId");
            long examId = positiveLong(values, "examId");
            long targetTaskId = positiveLong(values, "targetTaskId");
            Path eventFile = requiredPath(values, "eventFile");
            Path claimLockFile = optionalPath(values, "claimLockFile", Path.of(eventFile + ".claim.lock"));
            Path releaseFile = optionalPath(values, "releaseFile", Path.of(eventFile + ".release"));
            Path statusSessionIdsFile = optionalPath(values, "statusSessionIdsFile", null);
            boolean holdEnabled = Boolean.parseBoolean(
                values.getProperty("holdEnabled", "false").trim().toLowerCase(Locale.ROOT)
            );
            long holdTimeoutSeconds = parseLong(values.getProperty("holdTimeoutSeconds", "15"));
            if (holdTimeoutSeconds < 1 || holdTimeoutSeconds > 15) {
                throw new IllegalStateException("holdTimeoutSeconds 必须位于 1..15 秒");
            }
            return new ObservationChecklist(
                runId, examId, targetTaskId, eventFile, claimLockFile, releaseFile,
                statusSessionIdsFile, holdEnabled, holdTimeoutSeconds
            );
        }

        private static String required(Properties values, String key) {
            String value = values.getProperty(key);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("timeout-observation 控制清单缺少 " + key);
            }
            return value.trim();
        }

        private static long positiveLong(Properties values, String key) {
            long parsed = parseLong(required(values, key));
            if (parsed <= 0) {
                throw new IllegalStateException(key + " 必须为正整数");
            }
            return parsed;
        }

        private static long parseLong(String value) {
            try {
                return Long.parseLong(Objects.requireNonNull(value).trim());
            } catch (RuntimeException exception) {
                throw new IllegalStateException("控制清单中的数字无效: " + value, exception);
            }
        }

        private static Path requiredPath(Properties values, String key) {
            return Path.of(required(values, key));
        }

        private static Path optionalPath(Properties values, String key, Path fallback) {
            String value = values.getProperty(key);
            return value == null || value.isBlank() ? fallback : Path.of(value.trim());
        }
    }
}
