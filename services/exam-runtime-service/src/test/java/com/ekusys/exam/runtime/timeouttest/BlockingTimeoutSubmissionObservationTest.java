package com.ekusys.exam.runtime.timeouttest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BlockingTimeoutSubmissionObservationTest {

    @Test
    void onlyTheFirstClaimIsHeldAndRecoveryUsesANewToken() throws Exception {
        Path root = Files.createTempDirectory("timeout-observer-claim-");
        try {
            TimeoutObservationTestInitializer.ObservationChecklist checklist = checklist(root, true, 5, null);
            BlockingTimeoutSubmissionObservation first = new BlockingTimeoutSubmissionObservation(checklist);
            BlockingTimeoutSubmissionObservation recovery = new BlockingTimeoutSubmissionObservation(checklist);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> held = executor.submit(() -> first.onClaimProcessingStarted(
                    10L, 20L, 1, "old-token", 12L
                ));
                awaitEvent(checklist.eventFile(), "CLAIM_HELD");
                recovery.onClaimProcessingStarted(10L, 20L, 2, "new-token", 24L);
                Files.writeString(checklist.releaseFile(), "release\n", StandardCharsets.UTF_8);
                held.get(2, TimeUnit.SECONDS);

                String events = Files.readString(checklist.eventFile());
                assertThat(events).contains("\"eventType\":\"CLAIM_HELD\"")
                    .contains("\"eventType\":\"CLAIM_RECOVERY_OBSERVED\"")
                    .contains("\"eventType\":\"HOLD_RELEASED\"")
                    .doesNotContain("old-token")
                    .doesNotContain("new-token")
                    .contains(fingerprint("old-token"))
                    .contains(fingerprint("new-token"));
            } finally {
                executor.shutdownNow();
            }
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void renewalIsObservedWhileTheClaimCallbackIsBlocked() throws Exception {
        Path root = Files.createTempDirectory("timeout-observer-renew-");
        try {
            TimeoutObservationTestInitializer.ObservationChecklist checklist = checklist(root, true, 5, null);
            BlockingTimeoutSubmissionObservation observer = new BlockingTimeoutSubmissionObservation(checklist);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> held = executor.submit(() -> observer.onClaimProcessingStarted(
                    10L, 20L, 1, "renew-token", 12L
                ));
                awaitEvent(checklist.eventFile(), "CLAIM_HELD");
                observer.onLeaseRenewed(10L, 20L, 1, "renew-token", 100L);
                assertThat(Files.readString(checklist.eventFile()))
                    .contains("\"eventType\":\"RENEW_SUCCEEDED\"");
                Files.writeString(checklist.releaseFile(), "release\n", StandardCharsets.UTF_8);
                held.get(2, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void holdExpiresAutomaticallyAndConfigurationCannotExceedFifteenSeconds() throws Exception {
        Path root = Files.createTempDirectory("timeout-observer-expire-");
        try {
            TimeoutObservationTestInitializer.ObservationChecklist checklist = checklist(root, true, 1, null);
            BlockingTimeoutSubmissionObservation observer = new BlockingTimeoutSubmissionObservation(checklist);
            long started = System.nanoTime();
            observer.onClaimProcessingStarted(10L, 20L, 1, "token", 0L);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertThat(elapsedMs).isLessThan(3_000L);
            assertThat(Files.readString(checklist.eventFile())).contains("\"eventType\":\"HOLD_EXPIRED\"");

            Path invalid = writeChecklist(root.resolve("invalid"), true, 16, null);
            assertThatThrownBy(() -> TimeoutObservationTestInitializer.ObservationChecklist.load(invalid))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1..15");
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void statusSourceEventCarriesVerifiedSessionAndTaskMapping() throws Exception {
        Path root = Files.createTempDirectory("timeout-observer-status-");
        try {
            Path status = root.resolve("status.tsv");
            Files.writeString(status, "300\t400\t500\t0\n", StandardCharsets.UTF_8);
            TimeoutObservationTestInitializer.ObservationChecklist checklist = checklist(root, false, 15, status);
            BlockingTimeoutSubmissionObservation observer = new BlockingTimeoutSubmissionObservation(checklist);
            observer.onStatusLookup(10L, 300L, "DATABASE_FALLBACK", false, 99L);
            String event = Files.readString(checklist.eventFile());
            assertThat(event).contains("\"eventType\":\"STATUS_LOOKUP\"")
                .contains("\"studentId\":300")
                .contains("\"sessionId\":400")
                .contains("\"taskId\":500");
        } finally {
            deleteTree(root);
        }
    }

    private static TimeoutObservationTestInitializer.ObservationChecklist checklist(
        Path root, boolean holdEnabled, long holdTimeoutSeconds, Path statusFile) throws IOException {
        Path file = writeChecklist(root, holdEnabled, holdTimeoutSeconds, statusFile);
        return TimeoutObservationTestInitializer.ObservationChecklist.load(file);
    }

    private static Path writeChecklist(Path root, boolean holdEnabled, long holdTimeoutSeconds,
                                       Path statusFile) throws IOException {
        Files.createDirectories(root);
        Properties values = new Properties();
        values.setProperty("runId", "test-run");
        values.setProperty("examId", "10");
        values.setProperty("targetTaskId", "20");
        values.setProperty("eventFile", root.resolve("events.ndjson").toString());
        values.setProperty("claimLockFile", root.resolve("claim.lock").toString());
        values.setProperty("releaseFile", root.resolve("release").toString());
        if (statusFile != null) values.setProperty("statusSessionIdsFile", statusFile.toString());
        values.setProperty("holdEnabled", Boolean.toString(holdEnabled));
        values.setProperty("holdTimeoutSeconds", Long.toString(holdTimeoutSeconds));
        Path file = root.resolve("control.properties");
        try (var output = Files.newOutputStream(file)) {
            values.store(output, "test");
        }
        return file;
    }

    private static void awaitEvent(Path eventFile, String eventType) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (Files.exists(eventFile) && Files.readString(eventFile).contains(
                "\"eventType\":\"" + eventType + "\"")) return;
            Thread.sleep(20L);
        }
        throw new AssertionError("未等待到事件: " + eventType);
    }

    private static String fingerprint(String token) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(token.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest, 0, 8);
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new RuntimeException(exception);
                }
            });
        }
    }
}
