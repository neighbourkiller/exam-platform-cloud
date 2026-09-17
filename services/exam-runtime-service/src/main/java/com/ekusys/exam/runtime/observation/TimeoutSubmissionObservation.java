package com.ekusys.exam.runtime.observation;

/**
 * Internal, opt-in observation hooks for timeout-submission experiments.
 *
 * <p>The production implementation is deliberately empty. Implementations
 * used by isolated load tests may persist evidence or wait at a test-only
 * boundary, but must never be exposed as an HTTP control surface.</p>
 */
public interface TimeoutSubmissionObservation {
    TimeoutSubmissionObservation NOOP = new TimeoutSubmissionObservation() {
    };

    /**
     * Called after the claim transaction has committed and the renewal task
     * has been registered, immediately before draft loading begins.
     */
    default void onClaimProcessingStarted(Long examId, Long taskId, int attemptCount,
                                          String claimToken, long elapsedNanos) {
    }

    /**
     * Called after a real database lease renewal succeeds.
     */
    default void onLeaseRenewed(Long examId, Long taskId, int attemptCount,
                                String claimToken, long elapsedNanos) {
    }

    /**
     * Called after the finalization transaction has returned successfully,
     * which means its commit has completed.
     */
    default void onFinalizationCommitted(Long examId, Long taskId, int attemptCount,
                                         String claimToken, long elapsedNanos) {
    }

    /**
     * Called after a submission-status response has selected its source.
     * The default implementation performs no lookup, logging, or waiting.
     */
    default void onStatusLookup(Long examId, Long studentId, String statusSource,
                                boolean runtimeFinalized, long elapsedNanos) {
    }
}
