package com.ekusys.exam.exam.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProctoringPolicyView {

    private String level;
    private Boolean trackWindowBlur;
    private Boolean trackPageHidden;
    private Boolean trackNavigationLeave;
    private Boolean trackFullscreenExit;
    private Boolean trackCopyPaste;
    private Boolean trackContextMenu;
    private Boolean trackNetworkOffline;
    private Boolean trackLongInactivity;
    private Boolean requireFullscreen;
    private Boolean requireCamera;
    private Boolean requireMicrophone;
    private Boolean requireScreenShare;
    private Boolean blockMultiMonitor;
    private Boolean captureEvidence;
    private Integer inactivityThresholdSeconds;
    private Integer offscreenLongThresholdSeconds;
    private Integer repeatEventWindowMinutes;
    private Integer repeatEventThreshold;
}
