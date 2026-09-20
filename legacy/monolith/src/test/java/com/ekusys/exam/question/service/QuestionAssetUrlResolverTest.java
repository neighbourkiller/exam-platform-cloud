package com.ekusys.exam.question.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ekusys.exam.common.config.MinioProperties;
import com.ekusys.exam.repository.entity.QuestionAsset;
import org.junit.jupiter.api.Test;

class QuestionAssetUrlResolverTest {

    @Test
    void resolveShouldUseCurrentPublicEndpointWhenObjectKeyExists() {
        MinioProperties properties = new MinioProperties();
        properties.setEndpoint("http://127.0.0.1:19000");
        properties.setPublicEndpoint("http://127.0.0.1:19000/");
        properties.setBucket("question-images");
        QuestionAssetUrlResolver resolver = new QuestionAssetUrlResolver(properties);

        QuestionAsset asset = new QuestionAsset();
        asset.setUrl("http://127.0.0.1:9000/question-images/question/old.png");
        asset.setObjectKey("question/2026/04/20/new.png");

        assertEquals(
            "http://127.0.0.1:19000/question-images/question/2026/04/20/new.png",
            resolver.resolve(asset)
        );
    }

    @Test
    void resolveShouldFallbackToStoredUrlWhenObjectKeyMissing() {
        MinioProperties properties = new MinioProperties();
        properties.setPublicEndpoint("http://127.0.0.1:19000");
        properties.setBucket("question-images");
        QuestionAssetUrlResolver resolver = new QuestionAssetUrlResolver(properties);

        QuestionAsset asset = new QuestionAsset();
        asset.setUrl("http://example.com/file.png");

        assertEquals("http://example.com/file.png", resolver.resolve(asset));
    }
}
