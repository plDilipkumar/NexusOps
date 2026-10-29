package com.neurosql.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class TrustedClientAddressResolverTest {

    @Test
    void ignoresForwardedHeaderWhenDirectPeerIsNotTrusted() {
        TrustedClientAddressResolver resolver = new TrustedClientAddressResolver("10.0.0.0/8");
        MockHttpServletRequest request = request("198.51.100.10");
        request.addHeader("X-Forwarded-For", "203.0.113.90");

        assertEquals("198.51.100.10", resolver.resolve(request));
    }

    @Test
    void ignoresForwardedHeaderWhenNoTrustedProxyRangesAreConfigured() {
        TrustedClientAddressResolver resolver = new TrustedClientAddressResolver("");
        MockHttpServletRequest request = request("10.0.0.2");
        request.addHeader("X-Forwarded-For", "203.0.113.90");

        assertEquals("10.0.0.2", resolver.resolve(request));
    }

    @Test
    void resolvesClientThroughTrustedProxyChainAndIgnoresSpoofedPrefix() {
        TrustedClientAddressResolver resolver = new TrustedClientAddressResolver("10.0.0.0/8");
        MockHttpServletRequest request = request("10.0.0.2");
        request.addHeader("X-Forwarded-For", "198.51.100.99, 203.0.113.7, 10.0.0.1");

        assertEquals("203.0.113.7", resolver.resolve(request));
    }

    @Test
    void fallsBackToDirectPeerWhenForwardedHeaderIsMalformed() {
        TrustedClientAddressResolver resolver = new TrustedClientAddressResolver("10.0.0.0/8");
        MockHttpServletRequest request = request("10.0.0.2");
        request.addHeader("X-Forwarded-For", "203.0.113.7, untrusted-hostname");

        assertEquals("10.0.0.2", resolver.resolve(request));
    }

    @Test
    void fallsBackToDirectPeerWhenForwardedHeaderIsRepeated() {
        TrustedClientAddressResolver resolver = new TrustedClientAddressResolver("10.0.0.0/8");
        MockHttpServletRequest request = request("10.0.0.2");
        request.addHeader("X-Forwarded-For", "203.0.113.7");
        request.addHeader("X-Forwarded-For", "198.51.100.10");

        assertEquals("10.0.0.2", resolver.resolve(request));
    }

    @Test
    void supportsIpv6TrustedProxyRanges() throws Exception {
        TrustedClientAddressResolver resolver = new TrustedClientAddressResolver("2001:db8::/32");
        MockHttpServletRequest request = request("2001:db8::2");
        request.addHeader("X-Forwarded-For", "2001:db8::10");

        assertEquals(InetAddress.getByName("2001:db8::10").getHostAddress(), resolver.resolve(request));
    }

    @Test
    void rejectsInvalidTrustedProxyCidrAtConstruction() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TrustedClientAddressResolver("10.0.0.0/33"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TrustedClientAddressResolver("10.0.0.0/8,"));
    }

    private static MockHttpServletRequest request(String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        return request;
    }
}
