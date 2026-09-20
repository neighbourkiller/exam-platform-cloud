package com.ekusys.exam.exam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ekusys.exam.exam.dto.ProctoringPolicyView;
import com.ekusys.exam.exam.service.ExamProctoringPolicyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ExamProctoringPolicyServiceTest {

    private final ExamProctoringPolicyService service = new ExamProctoringPolicyService(new ObjectMapper());

    @Test
    void standardPolicyShouldKeepExistingStrictChecksEnabled() {
        ProctoringPolicyView policy = service.normalizeForCreate(null, null);

        assertEquals("STANDARD", policy.getLevel());
        assertTrue(policy.getRequireCamera());
        assertTrue(policy.getRequireScreenShare());
        assertTrue(policy.getBlockMultiMonitor());
        assertTrue(policy.getTrackLongInactivity());
        assertEquals(180, policy.getInactivityThresholdSeconds());
    }

    @Test
    void customPolicyShouldOverridePresetAndClampThresholds() {
        ProctoringPolicyView request = ProctoringPolicyView.builder()
            .level("CUSTOM")
            .requireCamera(false)
            .requireScreenShare(false)
            .inactivityThresholdSeconds(5)
            .repeatEventThreshold(99)
            .build();

        ProctoringPolicyView policy = service.normalizeForCreate("CUSTOM", request);

        assertEquals("CUSTOM", policy.getLevel());
        assertFalse(policy.getRequireCamera());
        assertFalse(policy.getRequireScreenShare());
        assertEquals(30, policy.getInactivityThresholdSeconds());
        assertEquals(20, policy.getRepeatEventThreshold());
    }

    @Test
    void disabledPolicyEventShouldNotRecord() {
        ProctoringPolicyView policy = service.normalizeForCreate("LOW", null);

        assertFalse(service.shouldRecordEvent(policy, "FULLSCREEN_EXIT"));
        assertFalse(service.shouldRecordEvent(policy, "CAMERA_STREAM_ENDED"));
        assertTrue(service.shouldRecordEvent(policy, "COPY_ATTEMPT"));
    }
}
