package com.ekusys.exam.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class TrustedClientIpResolverTest {

    @Test
    void ignoresForwardedHeaderFromUntrustedPeer() throws Exception {
        TrustedClientIpResolver resolver = resolver("10.0.0.0/8");

        String resolved = resolver.resolve(socket("203.0.113.9"), "198.51.100.7");

        assertThat(resolved).isEqualTo("203.0.113.9");
    }

    @Test
    void resolvesFirstUntrustedAddressFromRightToLeft() throws Exception {
        TrustedClientIpResolver resolver = resolver("10.0.0.0/8", "192.168.0.0/16");

        String resolved = resolver.resolve(
            socket("10.0.0.3"),
            "198.51.100.7, 192.168.1.20, 10.0.0.2"
        );

        assertThat(resolved).isEqualTo("198.51.100.7");
    }

    @Test
    void supportsIpv6TrustedProxyCidrs() throws Exception {
        TrustedClientIpResolver resolver = resolver("2001:db8:1::/48");

        String resolved = resolver.resolve(
            socket("2001:db8:1::2"),
            "2001:db8:ffff::10"
        );

        assertThat(InetAddress.getByName(resolved)).isEqualTo(InetAddress.getByName("2001:db8:ffff::10"));
    }

    @Test
    void fallsBackToPeerForMalformedOrOversizedChain() throws Exception {
        TrustedClientIpResolver resolver = resolver("10.0.0.0/8");
        String tooManyHops = String.join(",", Collections.nCopies(17, "10.0.0.2"));

        assertThat(resolver.resolve(socket("10.0.0.3"), "attacker.example"))
            .isEqualTo("10.0.0.3");
        assertThat(resolver.resolve(socket("10.0.0.3"), "198.51.100.7:1234"))
            .isEqualTo("10.0.0.3");
        assertThat(resolver.resolve(
            socket("10.0.0.3"), "1".repeat(TrustedClientIpResolver.MAX_FORWARDED_HEADER_LENGTH + 1)
        )).isEqualTo("10.0.0.3");
        assertThat(resolver.resolve(socket("10.0.0.3"), tooManyHops))
            .isEqualTo("10.0.0.3");
    }

    private TrustedClientIpResolver resolver(String... cidrs) {
        ClientIpProperties properties = new ClientIpProperties();
        properties.setTrustedProxyCidrs(java.util.List.of(cidrs));
        return new TrustedClientIpResolver(properties);
    }

    private InetSocketAddress socket(String address) throws Exception {
        return new InetSocketAddress(InetAddress.getByName(address), 443);
    }
}
