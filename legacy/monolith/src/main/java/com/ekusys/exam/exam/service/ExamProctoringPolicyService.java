package com.ekusys.exam.exam.service;

import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.exam.dto.ProctoringPolicyView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

@Service
public class ExamProctoringPolicyService {

    public static final String LEVEL_LOW = "LOW";
    public static final String LEVEL_STANDARD = "STANDARD";
    public static final String LEVEL_STRICT = "STRICT";
    public static final String LEVEL_CUSTOM = "CUSTOM";

    private static final int MIN_INACTIVITY_SECONDS = 30;
    private static final int MAX_INACTIVITY_SECONDS = 1800;
    private static final int MIN_OFFSCREEN_SECONDS = 5;
    private static final int MAX_OFFSCREEN_SECONDS = 600;
    private static final int MIN_REPEAT_WINDOW_MINUTES = 1;
    private static final int MAX_REPEAT_WINDOW_MINUTES = 60;
    private static final int MIN_REPEAT_THRESHOLD = 2;
    private static final int MAX_REPEAT_THRESHOLD = 20;

    private final ObjectMapper objectMapper;

    public ExamProctoringPolicyService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ProctoringPolicyView normalizeForCreate(String requestedLevel, ProctoringPolicyView requestedPolicy) {
        String level = normalizeLevel(requestedPolicy == null || requestedPolicy.getLevel() == null
            ? requestedLevel
            : requestedPolicy.getLevel());
        ProctoringPolicyView policy = preset(LEVEL_CUSTOM.equals(level) ? LEVEL_STANDARD : level);
        applyOverrides(policy, requestedPolicy);
        policy.setLevel(level);
        validate(policy);
        return policy;
    }

    public ProctoringPolicyView resolve(String storedLevel, String storedPolicyJson) {
        String level = normalizeLevel(storedLevel);
        ProctoringPolicyView policy = preset(LEVEL_CUSTOM.equals(level) ? LEVEL_STANDARD : level);
        if (storedPolicyJson != null && !storedPolicyJson.isBlank()) {
            try {
                applyOverrides(policy, objectMapper.readValue(storedPolicyJson, ProctoringPolicyView.class));
            } catch (Exception ex) {
                policy = preset(LEVEL_STANDARD);
                level = LEVEL_STANDARD;
            }
        }
        policy.setLevel(level);
        validate(policy);
        return policy;
    }

    public String toJson(ProctoringPolicyView policy) {
        try {
            return objectMapper.writeValueAsString(policy);
        } catch (Exception ex) {
            throw new BusinessException("防作弊策略配置无效");
        }
    }

    public boolean shouldRecordEvent(ProctoringPolicyView policy, String eventType) {
        if (policy == null || eventType == null) {
            return true;
        }
        return switch (eventType) {
            case "WINDOW_BLUR" -> enabled(policy.getTrackWindowBlur());
            case "TAB_HIDDEN" -> enabled(policy.getTrackPageHidden());
            case "NAVIGATION_LEAVE_ATTEMPT" -> enabled(policy.getTrackNavigationLeave());
            case "FULLSCREEN_EXIT" -> enabled(policy.getTrackFullscreenExit());
            case "COPY_ATTEMPT", "PASTE_ATTEMPT", "CUT_ATTEMPT" -> enabled(policy.getTrackCopyPaste());
            case "CONTEXT_MENU" -> enabled(policy.getTrackContextMenu());
            case "NETWORK_OFFLINE" -> enabled(policy.getTrackNetworkOffline());
            case "LONG_INACTIVITY" -> enabled(policy.getTrackLongInactivity());
            case "CAMERA_START_FAILED", "CAMERA_STREAM_ENDED", "CAMERA_TRACK_MUTED", "CAMERA_FRAME_DARK" ->
                enabled(policy.getRequireCamera());
            case "MULTI_MONITOR_DETECTED", "SCREEN_CHECK_UNAVAILABLE" -> enabled(policy.getBlockMultiMonitor());
            case "SCREEN_SHARE_START_FAILED", "SCREEN_SHARE_ENDED" -> enabled(policy.getRequireScreenShare());
            default -> true;
        };
    }

    private ProctoringPolicyView preset(String level) {
        return switch (normalizeLevel(level)) {
            case LEVEL_LOW -> ProctoringPolicyView.builder()
                .level(LEVEL_LOW)
                .trackWindowBlur(true)
                .trackPageHidden(true)
                .trackNavigationLeave(true)
                .trackFullscreenExit(false)
                .trackCopyPaste(true)
                .trackContextMenu(true)
                .trackNetworkOffline(true)
                .trackLongInactivity(true)
                .requireFullscreen(false)
                .requireCamera(false)
                .requireMicrophone(false)
                .requireScreenShare(false)
                .blockMultiMonitor(false)
                .captureEvidence(false)
                .inactivityThresholdSeconds(300)
                .offscreenLongThresholdSeconds(60)
                .repeatEventWindowMinutes(10)
                .repeatEventThreshold(4)
                .build();
            case LEVEL_STRICT -> ProctoringPolicyView.builder()
                .level(LEVEL_STRICT)
                .trackWindowBlur(true)
                .trackPageHidden(true)
                .trackNavigationLeave(true)
                .trackFullscreenExit(true)
                .trackCopyPaste(true)
                .trackContextMenu(true)
                .trackNetworkOffline(true)
                .trackLongInactivity(true)
                .requireFullscreen(true)
                .requireCamera(true)
                .requireMicrophone(true)
                .requireScreenShare(true)
                .blockMultiMonitor(true)
                .captureEvidence(true)
                .inactivityThresholdSeconds(90)
                .offscreenLongThresholdSeconds(15)
                .repeatEventWindowMinutes(10)
                .repeatEventThreshold(2)
                .build();
            default -> ProctoringPolicyView.builder()
                .level(LEVEL_STANDARD)
                .trackWindowBlur(true)
                .trackPageHidden(true)
                .trackNavigationLeave(true)
                .trackFullscreenExit(true)
                .trackCopyPaste(true)
                .trackContextMenu(true)
                .trackNetworkOffline(true)
                .trackLongInactivity(true)
                .requireFullscreen(true)
                .requireCamera(true)
                .requireMicrophone(true)
                .requireScreenShare(true)
                .blockMultiMonitor(true)
                .captureEvidence(true)
                .inactivityThresholdSeconds(180)
                .offscreenLongThresholdSeconds(30)
                .repeatEventWindowMinutes(10)
                .repeatEventThreshold(3)
                .build();
        };
    }

    private void applyOverrides(ProctoringPolicyView target, ProctoringPolicyView source) {
        if (source == null) {
            return;
        }
        if (source.getTrackWindowBlur() != null) target.setTrackWindowBlur(source.getTrackWindowBlur());
        if (source.getTrackPageHidden() != null) target.setTrackPageHidden(source.getTrackPageHidden());
        if (source.getTrackNavigationLeave() != null) target.setTrackNavigationLeave(source.getTrackNavigationLeave());
        if (source.getTrackFullscreenExit() != null) target.setTrackFullscreenExit(source.getTrackFullscreenExit());
        if (source.getTrackCopyPaste() != null) target.setTrackCopyPaste(source.getTrackCopyPaste());
        if (source.getTrackContextMenu() != null) target.setTrackContextMenu(source.getTrackContextMenu());
        if (source.getTrackNetworkOffline() != null) target.setTrackNetworkOffline(source.getTrackNetworkOffline());
        if (source.getTrackLongInactivity() != null) target.setTrackLongInactivity(source.getTrackLongInactivity());
        if (source.getRequireFullscreen() != null) target.setRequireFullscreen(source.getRequireFullscreen());
        if (source.getRequireCamera() != null) target.setRequireCamera(source.getRequireCamera());
        if (source.getRequireMicrophone() != null) target.setRequireMicrophone(source.getRequireMicrophone());
        if (source.getRequireScreenShare() != null) target.setRequireScreenShare(source.getRequireScreenShare());
        if (source.getBlockMultiMonitor() != null) target.setBlockMultiMonitor(source.getBlockMultiMonitor());
        if (source.getCaptureEvidence() != null) target.setCaptureEvidence(source.getCaptureEvidence());
        if (source.getInactivityThresholdSeconds() != null) {
            target.setInactivityThresholdSeconds(source.getInactivityThresholdSeconds());
        }
        if (source.getOffscreenLongThresholdSeconds() != null) {
            target.setOffscreenLongThresholdSeconds(source.getOffscreenLongThresholdSeconds());
        }
        if (source.getRepeatEventWindowMinutes() != null) {
            target.setRepeatEventWindowMinutes(source.getRepeatEventWindowMinutes());
        }
        if (source.getRepeatEventThreshold() != null) {
            target.setRepeatEventThreshold(source.getRepeatEventThreshold());
        }
    }

    private void validate(ProctoringPolicyView policy) {
        policy.setInactivityThresholdSeconds(clamp(policy.getInactivityThresholdSeconds(), 180,
            MIN_INACTIVITY_SECONDS, MAX_INACTIVITY_SECONDS));
        policy.setOffscreenLongThresholdSeconds(clamp(policy.getOffscreenLongThresholdSeconds(), 30,
            MIN_OFFSCREEN_SECONDS, MAX_OFFSCREEN_SECONDS));
        policy.setRepeatEventWindowMinutes(clamp(policy.getRepeatEventWindowMinutes(), 10,
            MIN_REPEAT_WINDOW_MINUTES, MAX_REPEAT_WINDOW_MINUTES));
        policy.setRepeatEventThreshold(clamp(policy.getRepeatEventThreshold(), 3,
            MIN_REPEAT_THRESHOLD, MAX_REPEAT_THRESHOLD));
    }

    private int clamp(Integer value, int fallback, int min, int max) {
        int normalized = value == null ? fallback : value;
        if (normalized < min) {
            return min;
        }
        if (normalized > max) {
            return max;
        }
        return normalized;
    }

    private boolean enabled(Boolean value) {
        return Boolean.TRUE.equals(value);
    }

    private String normalizeLevel(String level) {
        if (level == null || level.isBlank()) {
            return LEVEL_STANDARD;
        }
        String normalized = level.trim().toUpperCase();
        return switch (normalized) {
            case LEVEL_LOW, LEVEL_STANDARD, LEVEL_STRICT, LEVEL_CUSTOM -> normalized;
            default -> throw new BusinessException("无效的防作弊严格程度");
        };
    }
}
