package com.ekusys.exam.common.web;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.regex.Pattern;

public final class ClientIpUtils {
    public static final String CLIENT_IP_HEADER = "X-Exam-Client-IP";

    private static final Pattern IPV4_PATTERN = Pattern.compile("(?:\\d{1,3}\\.){3}\\d{1,3}");
    private static final Pattern IPV6_CHARACTERS = Pattern.compile("[0-9a-fA-F:.]+");

    private ClientIpUtils() {
    }

    public static Optional<String> normalizeLiteral(String value) {
        return parseLiteral(value).map(InetAddress::getHostAddress);
    }

    public static Optional<InetAddress> parseLiteral(String value) {
        String host = extractHost(value);
        if (host == null) {
            return Optional.empty();
        }
        if (IPV4_PATTERN.matcher(host).matches()) {
            String[] parts = host.split("\\.");
            byte[] bytes = new byte[4];
            for (int index = 0; index < parts.length; index++) {
                int octet;
                try {
                    octet = Integer.parseInt(parts[index]);
                } catch (NumberFormatException exception) {
                    return Optional.empty();
                }
                if (octet > 255) {
                    return Optional.empty();
                }
                bytes[index] = (byte) octet;
            }
            try {
                return Optional.of(InetAddress.getByAddress(bytes));
            } catch (UnknownHostException exception) {
                return Optional.empty();
            }
        }
        if (!host.contains(":") || !IPV6_CHARACTERS.matcher(host).matches()) {
            return Optional.empty();
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            return address instanceof Inet6Address || address instanceof Inet4Address
                ? Optional.of(address)
                : Optional.empty();
        } catch (UnknownHostException exception) {
            return Optional.empty();
        }
    }

    private static String extractHost(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String candidate = value.trim();
        if (candidate.contains("%") || candidate.contains("/") || candidate.contains(" ")
            || candidate.contains("[") || candidate.contains("]")) {
            return null;
        }
        return candidate.isBlank() ? null : candidate;
    }
}
