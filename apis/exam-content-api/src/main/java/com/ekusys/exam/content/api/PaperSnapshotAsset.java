package com.ekusys.exam.content.api;

public record PaperSnapshotAsset(String assetId, String url, String objectKey, String originalName,
                                 Long size, String fileType) {
}
