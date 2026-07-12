package com.ekusys.exam.gateway;

import com.ekusys.exam.common.web.ClientIpUtils;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class TrustedClientIpResolver {
    static final int MAX_FORWARDED_HEADER_LENGTH = 2048;
    static final int MAX_FORWARDED_HOPS = 16;

    private final List<CidrBlock> trustedProxies;

    public TrustedClientIpResolver(ClientIpProperties properties) {
        this.trustedProxies = properties.getTrustedProxyCidrs().stream()
            .filter(value -> value != null && !value.isBlank())
            .map(CidrBlock::parse)
            .toList();
    }

    public String resolve(InetSocketAddress remoteAddress, String forwardedFor) {
        InetAddress remote = remoteAddress == null ? null : remoteAddress.getAddress();
        if (remote == null) {
            return "unknown";
        }
        String remoteIp = remote.getHostAddress();
        if (!isTrusted(remote) || forwardedFor == null || forwardedFor.isBlank()) {
            return remoteIp;
        }
        if (forwardedFor.length() > MAX_FORWARDED_HEADER_LENGTH) {
            return remoteIp;
        }
        String[] values = forwardedFor.split(",", -1);
        if (values.length > MAX_FORWARDED_HOPS) {
            return remoteIp;
        }
        List<InetAddress> chain = new ArrayList<>(values.length + 1);
        for (String value : values) {
            var address = ClientIpUtils.parseLiteral(value);
            if (address.isEmpty()) {
                return remoteIp;
            }
            chain.add(address.get());
        }
        chain.add(remote);
        for (int index = chain.size() - 1; index >= 0; index--) {
            InetAddress address = chain.get(index);
            if (!isTrusted(address)) {
                return address.getHostAddress();
            }
        }
        return chain.getFirst().getHostAddress();
    }

    private boolean isTrusted(InetAddress address) {
        return trustedProxies.stream().anyMatch(cidr -> cidr.matches(address));
    }

    private record CidrBlock(byte[] network, int prefixLength) {
        private static CidrBlock parse(String value) {
            String candidate = value.trim();
            int separator = candidate.lastIndexOf('/');
            String addressPart = separator < 0 ? candidate : candidate.substring(0, separator);
            InetAddress address = ClientIpUtils.parseLiteral(addressPart)
                .orElseThrow(() -> new IllegalArgumentException("Invalid trusted proxy CIDR: " + value));
            int maxPrefix = address.getAddress().length * Byte.SIZE;
            int prefix = maxPrefix;
            if (separator >= 0) {
                try {
                    prefix = Integer.parseInt(candidate.substring(separator + 1));
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException("Invalid trusted proxy CIDR: " + value, exception);
                }
            }
            if (prefix < 0 || prefix > maxPrefix) {
                throw new IllegalArgumentException("Invalid trusted proxy CIDR: " + value);
            }
            return new CidrBlock(address.getAddress(), prefix);
        }

        private boolean matches(InetAddress address) {
            byte[] candidate = address.getAddress();
            if (candidate.length != network.length) {
                return false;
            }
            int fullBytes = prefixLength / Byte.SIZE;
            if (!Arrays.equals(
                Arrays.copyOf(candidate, fullBytes),
                Arrays.copyOf(network, fullBytes)
            )) {
                return false;
            }
            int remainingBits = prefixLength % Byte.SIZE;
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xFF << (Byte.SIZE - remainingBits);
            return ((candidate[fullBytes] & 0xFF) & mask) == ((network[fullBytes] & 0xFF) & mask);
        }
    }
}
