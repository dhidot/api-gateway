package com.careertrack.gateway.ratelimit;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Turns a client IP string into a stable rate-limit key.
 *
 * <ul>
 *   <li>Only IP literals are accepted. Hostnames are rejected, so a
 *       header value can never trigger a DNS lookup.</li>
 *   <li>IPv4 is keyed per address.</li>
 *   <li>IPv6 is keyed per /64: one subscriber usually owns a whole /64,
 *       so per-address keys would let them rotate through billions of
 *       buckets.</li>
 * </ul>
 */
public final class ClientAddress {

    public static final String UNKNOWN = "unknown";

    private static final int MAX_LITERAL_LENGTH = 45;

    private static final Pattern IPV4 = Pattern.compile(
            "^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");

    private static final Pattern IPV6_CHARS = Pattern.compile(
            "^[0-9A-Fa-f:.]+$");

    private ClientAddress() {
    }

    public static Optional<String> toKey(String raw) {
        if (raw == null) {
            return Optional.empty();
        }

        String value = raw.trim();

        if (value.isEmpty() || value.length() > MAX_LITERAL_LENGTH) {
            return Optional.empty();
        }

        try {
            if (IPV4.matcher(value).matches()) {
                return validIpv4(value) ? Optional.of(keyOf(InetAddress.getByName(value)))
                        : Optional.empty();
            }

            if (value.indexOf(':') >= 0 && IPV6_CHARS.matcher(value).matches()) {
                // Brackets force literal parsing: invalid input throws
                // instead of falling back to a DNS lookup.
                return Optional.of(keyOf(InetAddress.getByName("[" + value + "]")));
            }
        } catch (UnknownHostException e) {
            return Optional.empty();
        }

        return Optional.empty();
    }

    private static boolean validIpv4(String value) {
        for (String part : value.split("\\.")) {
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    private static String keyOf(InetAddress address) throws UnknownHostException {
        // ::ffff:a.b.c.d literals already come back as Inet4Address.
        if (address instanceof Inet6Address) {
            byte[] bytes = address.getAddress();
            Arrays.fill(bytes, 8, 16, (byte) 0);
            return InetAddress.getByAddress(bytes).getHostAddress() + "/64";
        }
        return address.getHostAddress();
    }
}
