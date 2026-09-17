package com.ekusys.exam.runtime.timeouttest;

import com.ekusys.exam.runtime.observation.TimeoutSubmissionObservation;
import com.ekusys.exam.runtime.timeouttest.TimeoutObservationTestInitializer.ObservationChecklist;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test-only observer for deterministic takeover injection and status-source
 * evidence. It never appears in the deployable Runtime JAR.
 */
final class BlockingTimeoutSubmissionObservation implements TimeoutSubmissionObservation {
    private static final Object FILE_APPEND_LOCK = new Object();

    private final ObservationChecklist checklist;
    private final String instanceId = resolveInstanceId();
    private final Map<Long, StatusSession> statusSessions;
    private final AtomicBoolean firstClaimAcquired = new AtomicBoolean();

    BlockingTimeoutSubmissionObservation(ObservationChecklist checklist) {
        this.checklist = checklist;
        this.statusSessions = loadSessionIds(checklist.statusSessionIdsFile());
    }

    @Override
    public void onClaimProcessingStarted(Long examId, Long taskId, int attemptCount,
                                         String claimToken, long elapsedNanos) {
        if (!matchesTarget(examId, taskId)) {
            return;
        }
        boolean firstClaim = attemptCount == 1 && acquireFirstClaim();
        appendEvent("CLAIM_PROCESSING_STARTED", examId, taskId, attemptCount, claimToken,
            null, false, elapsedNanos, firstClaim);
        if (firstClaim) {
            appendEvent(checklist.holdEnabled() ? "CLAIM_HELD" : "CLAIM_OBSERVED",
                examId, taskId, attemptCount, claimToken, null, false, elapsedNanos, true);
            if (checklist.holdEnabled()) {
                holdUntilReleasedOrExpired(examId, taskId, attemptCount, claimToken, elapsedNanos);
            }
        } else if (attemptCount > 1) {
            appendEvent("CLAIM_RECOVERY_OBSERVED", examId, taskId, attemptCount, claimToken,
                null, false, elapsedNanos, false);
        }
    }

    @Override
    public void onLeaseRenewed(Long examId, Long taskId, int attemptCount,
                               String claimToken, long elapsedNanos) {
        if (matchesTarget(examId, taskId)) {
            appendEvent("RENEW_SUCCEEDED", examId, taskId, attemptCount, claimToken,
                null, false, elapsedNanos, firstClaimAcquired.get());
        }
    }

    @Override
    public void onFinalizationCommitted(Long examId, Long taskId, int attemptCount,
                                        String claimToken, long elapsedNanos) {
        if (matchesTarget(examId, taskId)) {
            appendEvent("FINALIZATION_COMMITTED", examId, taskId, attemptCount, claimToken,
                null, true, elapsedNanos, false);
        }
    }

    @Override
    public void onStatusLookup(Long examId, Long studentId, String statusSource,
                               boolean runtimeFinalized, long elapsedNanos) {
        if (examId == null || !checklist.examIdEquals(examId)
            || checklist.statusSessionIdsFile() == null) {
            return;
        }
        // The status endpoint supplies studentId; the selected-session file is
        // written from the verified session/student mapping by the load tool.
        StatusSession session = statusSessions.get(studentId);
        if (session == null) {
            return;
        }
        appendEvent("STATUS_LOOKUP", examId, session.taskId(), 0, null, statusSource,
            runtimeFinalized, elapsedNanos, false, studentId, session.sessionId());
    }

    private void holdUntilReleasedOrExpired(Long examId, Long taskId, int attemptCount,
                                            String claimToken, long elapsedNanos) {
        long deadline = System.nanoTime()
            + TimeUnit.SECONDS.toNanos(checklist.holdTimeoutSeconds());
        while (true) {
            if (Files.exists(checklist.releaseFile())) {
                appendEvent("HOLD_RELEASED", examId, taskId, attemptCount, claimToken,
                    null, false, elapsedNanos, true);
                return;
            }
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                appendEvent("HOLD_INTERRUPTED", examId, taskId, attemptCount, claimToken,
                    null, false, elapsedNanos, true);
                return;
            }
            if (System.nanoTime() >= deadline) {
                appendEvent("HOLD_EXPIRED", examId, taskId, attemptCount, claimToken,
                    null, false, elapsedNanos, true);
                return;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                appendEvent("HOLD_INTERRUPTED", examId, taskId, attemptCount, claimToken,
                    null, false, elapsedNanos, true);
                return;
            }
        }
    }

    private boolean acquireFirstClaim() {
        if (firstClaimAcquired.get()) {
            return false;
        }
        try {
            Path parent = checklist.claimLockFile().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.createFile(checklist.claimLockFile());
            firstClaimAcquired.set(true);
            return true;
        } catch (java.nio.file.FileAlreadyExistsException exception) {
            return false;
        } catch (IOException exception) {
            throw new IllegalStateException("无法建立目标任务首次领取锁", exception);
        }
    }

    private void appendEvent(String eventType, Long examId, Long taskId, int attemptCount,
                             String claimToken, String statusSource, boolean runtimeFinalized,
                             long elapsedNanos, boolean firstTargetClaim) {
        appendEvent(eventType, examId, taskId, attemptCount, claimToken, statusSource,
            runtimeFinalized, elapsedNanos, firstTargetClaim, null);
    }

    private void appendEvent(String eventType, Long examId, Long taskId, int attemptCount,
                             String claimToken, String statusSource, boolean runtimeFinalized,
                             long elapsedNanos, boolean firstTargetClaim, Long studentId) {
        appendEvent(eventType, examId, taskId, attemptCount, claimToken, statusSource,
            runtimeFinalized, elapsedNanos, firstTargetClaim, studentId, null);
    }

    private void appendEvent(String eventType, Long examId, Long taskId, int attemptCount,
                             String claimToken, String statusSource, boolean runtimeFinalized,
                             long elapsedNanos, boolean firstTargetClaim, Long studentId,
                             Long sessionId) {
        StringBuilder json = new StringBuilder(320)
            .append('{')
            .append("\"runId\":").append(json(checklist.runId()))
            .append(",\"eventType\":").append(json(eventType))
            .append(",\"instanceId\":").append(json(instanceId))
            .append(",\"examId\":").append(examId == null ? "null" : examId)
            .append(",\"taskId\":").append(taskId == null ? "null" : taskId)
            .append(",\"attemptCount\":").append(attemptCount)
            .append(",\"claimTokenFingerprint\":")
            .append(claimToken == null ? "null" : json(fingerprint(claimToken)))
            .append(",\"wallClockTime\":").append(json(Instant.now().toString()))
            .append(",\"wallClockEpochMs\":").append(System.currentTimeMillis())
            .append(",\"elapsedNanos\":").append(Math.max(0L, elapsedNanos))
            .append(",\"firstTargetClaim\":").append(firstTargetClaim)
            .append(",\"runtimeFinalized\":").append(runtimeFinalized);
        if (statusSource != null) {
            json.append(",\"statusSource\":").append(json(statusSource));
        }
        if (studentId != null) {
            json.append(",\"studentId\":").append(studentId);
        }
        if (sessionId != null) {
            json.append(",\"sessionId\":").append(sessionId);
        }
        json.append('}').append('\n');
        try {
            Path parent = checklist.eventFile().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            synchronized (FILE_APPEND_LOCK) {
                try (FileChannel channel = FileChannel.open(
                    checklist.eventFile(), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                    FileLock lock = null;
                    try {
                        lock = channel.lock();
                    } catch (OverlappingFileLockException ignored) {
                        // The JVM-level lock above already serializes local writers.
                    }
                    try {
                        channel.position(channel.size());
                        channel.write(ByteBuffer.wrap(json.toString().getBytes(StandardCharsets.UTF_8)));
                    } finally {
                        if (lock != null) {
                            lock.release();
                        }
                    }
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("无法写入 timeout-observation 事件", exception);
        }
    }

    private boolean matchesTarget(Long examId, Long taskId) {
        return examId != null && taskId != null
            && checklist.examIdEquals(examId) && checklist.targetTaskId() == taskId;
    }

    private static Map<Long, StatusSession> loadSessionIds(Path path) {
        if (path == null || !Files.exists(path)) {
            return Map.of();
        }
        try {
            Map<Long, StatusSession> sessions = new java.util.HashMap<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String value = line.trim();
                if (value.isEmpty() || value.startsWith("#")) {
                    continue;
                }
                String[] fields = value.split("\\t");
                long studentId = Long.parseLong(fields[0]);
                Long sessionId = fields.length > 1 && !fields[1].isBlank()
                    ? Long.valueOf(fields[1]) : null;
                Long taskId = fields.length > 2 && !fields[2].isBlank()
                    ? Long.valueOf(fields[2]) : null;
                sessions.put(studentId, new StatusSession(sessionId, taskId));
            }
            return Map.copyOf(sessions);
        } catch (IOException | NumberFormatException exception) {
            throw new IllegalStateException("无法读取状态诊断会话清单", exception);
        }
    }

    private record StatusSession(Long sessionId, Long taskId) {
    }

    private static String resolveInstanceId() {
        String hostname = System.getenv("HOSTNAME");
        if (hostname != null && !hostname.isBlank()) {
            return hostname.trim();
        }
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception exception) {
            return "unknown-instance";
        }
    }

    private static String fingerprint(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(16);
            for (int index = 0; index < 8; index += 1) {
                result.append(String.format("%02x", digest[index]));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 不支持 SHA-256", exception);
        }
    }

    private static String json(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder result = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index += 1) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> result.append(character);
            }
        }
        return result.append('"').toString();
    }
}
