package com.ekusys.exam.question.service;

import com.ekusys.exam.common.config.AssetCleanupProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class QuestionAssetCleanupScheduler {

    private final QuestionAssetCleanupService questionAssetCleanupService;
    private final AssetCleanupProperties assetCleanupProperties;

    public QuestionAssetCleanupScheduler(QuestionAssetCleanupService questionAssetCleanupService,
                                         AssetCleanupProperties assetCleanupProperties) {
        this.questionAssetCleanupService = questionAssetCleanupService;
        this.assetCleanupProperties = assetCleanupProperties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void cleanupOnStartup() {
        if (!assetCleanupProperties.isStartupRun()) {
            return;
        }
        questionAssetCleanupService.cleanupNow("startup");
    }

    @Scheduled(fixedDelayString = "${app.asset-cleanup.interval-ms:600000}")
    public void cleanupOnSchedule() {
        questionAssetCleanupService.cleanupNow("scheduled");
    }
}
