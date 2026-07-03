package com.ekusys.exam.common.security;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

final class PemKeyReader {
    private PemKeyReader() {
    }

    static RSAPublicKey readPublicKey(String inlinePem, String location) {
        try {
            String pem = inlinePem;
            if ((pem == null || pem.isBlank()) && location != null && !location.isBlank()) {
                pem = Files.readString(Path.of(stripFilePrefix(location)));
            }
            if (pem == null || pem.isBlank()) {
                throw new IllegalStateException("JWT public key is not configured");
            }
            String encoded = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
            byte[] bytes = Base64.getDecoder().decode(encoded);
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid JWT RSA public key", exception);
        }
    }

    private static String stripFilePrefix(String location) {
        return location.startsWith("file:") ? location.substring(5) : location;
    }
}
