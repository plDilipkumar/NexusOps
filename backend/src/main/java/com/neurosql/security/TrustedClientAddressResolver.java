package com.neurosql.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves a client IP without trusting caller-controlled forwarding headers.
 *
 * <p>{@code X-Forwarded-For} is considered only when the direct peer is inside
 * an explicitly configured trusted-proxy CIDR. The chain is walked from right
 * to left, stopping at the first untrusted hop so a caller cannot prepend a
 * spoofed address. With no trusted CIDRs configured, the direct peer is always
 * used. Configure only proxy ranges controlled by the deployment operator.</p>
 */
@Component
public final class TrustedClientAddressResolver {
    static final int MAX_FORWARDED_HEADER_LENGTH = 1_024;
    static final int MAX_FORWARDED_HOPS = 20;

    private final List<Cidr> trustedProxies;

    public TrustedClientAddressResolver(
            @Value("${neurosql.security.trusted-proxy-cidrs:}") String trustedProxyCidrs) {
        this.trustedProxies = parseTrustedProxies(trustedProxyCidrs);
    }

    public String resolve(HttpServletRequest request) {
        Objects.requireNonNull(request, "request");

        String remoteAddress = canonicalAddress(request.getRemoteAddr());
        if ("unknown".equals(remoteAddress) || !isTrustedProxy(remoteAddress)) {
            return remoteAddress;
        }

        List<String> headerValues = Collections.list(request.getHeaders("X-Forwarded-For"));
        if (headerValues.size() != 1) {
            return remoteAddress;
        }

        String forwardedFor = headerValues.get(0);
        if (forwardedFor == null
                || forwardedFor.isBlank()
                || forwardedFor.length() > MAX_FORWARDED_HEADER_LENGTH) {
            return remoteAddress;
        }

        String[] rawHops = forwardedFor.split(",", -1);
        if (rawHops.length == 0 || rawHops.length > MAX_FORWARDED_HOPS) {
            return remoteAddress;
        }

        List<String> hops = new ArrayList<>(rawHops.length);
        for (String rawHop : rawHops) {
            String hop = canonicalAddress(rawHop.trim());
            if ("unknown".equals(hop)) {
                // Malformed proxy metadata must never be partially trusted.
                return remoteAddress;
            }
            hops.add(hop);
        }

        String currentHop = remoteAddress;
        for (int index = hops.size() - 1; index >= 0; index--) {
            if (!isTrustedProxy(currentHop)) {
                return currentHop;
            }
            currentHop = hops.get(index);
        }
        return currentHop;
    }

    private boolean isTrustedProxy(String address) {
        return trustedProxies.stream().anyMatch(cidr -> cidr.contains(address));
    }

    private static List<Cidr> parseTrustedProxies(String configured) {
        if (configured == null || configured.isBlank()) {
            return List.of();
        }

        String[] entries = configured.split(",", -1);
        List<Cidr> parsed = new ArrayList<>(entries.length);
        for (String entry : entries) {
            if (entry.isBlank()) {
                throw new IllegalArgumentException("Trusted proxy CIDR list contains an empty entry.");
            }
            parsed.add(Cidr.parse(entry.trim()));
        }
        return List.copyOf(parsed);
    }

    private static String canonicalAddress(String value) {
        InetAddress address = parseIpLiteral(value);
        return address == null ? "unknown" : address.getHostAddress();
    }

    /**
     * Accepts only numeric IPv4/IPv6 literals; hostnames are never resolved.
     */
    private static InetAddress parseIpLiteral(String value) {
        if (value == null || value.isBlank() || !value.equals(value.trim()) || value.indexOf('%') >= 0) {
            return null;
        }

        if (value.indexOf(':') >= 0) {
            if (!value.matches("[0-9a-fA-F:.]+")) {
                return null;
            }
            try {
                InetAddress address = InetAddress.getByName(value);
                return address.getAddress().length == 16 ? address : null;
            } catch (UnknownHostException invalidAddress) {
                return null;
            }
        }

        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) {
            return null;
        }
        byte[] bytes = new byte[4];
        for (int index = 0; index < octets.length; index++) {
            String octet = octets[index];
            if (octet.isEmpty() || octet.length() > 3 || !octet.chars().allMatch(Character::isDigit)) {
                return null;
            }
            try {
                int parsed = Integer.parseInt(octet);
                if (parsed > 255) {
                    return null;
                }
                bytes[index] = (byte) parsed;
            } catch (NumberFormatException invalidOctet) {
                return null;
            }
        }

        try {
            return InetAddress.getByAddress(bytes);
        } catch (UnknownHostException impossibleLength) {
            return null;
        }
    }

    private static final class Cidr {
        private final byte[] network;
        private final int prefixLength;

        private Cidr(byte[] network, int prefixLength) {
            this.network = network;
            this.prefixLength = prefixLength;
        }

        static Cidr parse(String value) {
            int slash = value.lastIndexOf('/');
            if (slash <= 0 || slash == value.length() - 1 || value.indexOf('/') != slash) {
                throw new IllegalArgumentException("Trusted proxy entries must use CIDR notation.");
            }

            InetAddress address = parseIpLiteral(value.substring(0, slash).trim());
            if (address == null) {
                throw new IllegalArgumentException("Trusted proxy CIDR contains an invalid IP address.");
            }

            final int prefix;
            try {
                String prefixText = value.substring(slash + 1);
                if (!prefixText.chars().allMatch(Character::isDigit)) {
                    throw new NumberFormatException("Non-numeric prefix");
                }
                prefix = Integer.parseInt(prefixText);
            } catch (NumberFormatException invalidPrefix) {
                throw new IllegalArgumentException("Trusted proxy CIDR contains an invalid prefix length.", invalidPrefix);
            }

            byte[] bytes = address.getAddress();
            if (prefix < 0 || prefix > bytes.length * 8) {
                throw new IllegalArgumentException("Trusted proxy CIDR prefix is outside the address range.");
            }
            byte[] network = bytes.clone();
            int remaining = prefix;
            for (int index = 0; index < network.length; index++) {
                int mask;
                if (remaining >= 8) {
                    mask = 0xff;
                    remaining -= 8;
                } else if (remaining <= 0) {
                    mask = 0;
                } else {
                    mask = (0xff << (8 - remaining)) & 0xff;
                    remaining = 0;
                }
                network[index] = (byte) (network[index] & mask);
            }
            return new Cidr(network, prefix);
        }

        boolean contains(String candidate) {
            InetAddress address = parseIpLiteral(candidate);
            if (address == null) {
                return false;
            }
            byte[] bytes = address.getAddress();
            if (bytes.length != network.length) {
                return false;
            }

            int remaining = prefixLength;
            for (int index = 0; index < network.length; index++) {
                if (remaining <= 0) {
                    return true;
                }
                int bits = Math.min(8, remaining);
                int mask = bits == 8 ? 0xff : (0xff << (8 - bits)) & 0xff;
                if (((bytes[index] & 0xff) & mask) != ((network[index] & 0xff) & mask)) {
                    return false;
                }
                remaining -= bits;
            }
            return true;
        }
    }
}
