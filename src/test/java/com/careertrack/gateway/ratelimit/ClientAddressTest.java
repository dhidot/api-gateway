package com.careertrack.gateway.ratelimit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ClientAddressTest {

    @Test
    void keepsIpv4AsIs() {
        assertThat(ClientAddress.toKey("203.0.113.7")).contains("203.0.113.7");
        assertThat(ClientAddress.toKey("  203.0.113.7 ")).contains("203.0.113.7");
    }

    @Test
    void groupsIpv6ByPrefix64() {
        String a = ClientAddress.toKey("2001:db8:1:2::1").orElseThrow();
        String b = ClientAddress.toKey("2001:db8:1:2:ffff:ffff:ffff:ffff").orElseThrow();
        String other = ClientAddress.toKey("2001:db8:1:3::1").orElseThrow();

        assertThat(a).isEqualTo(b).endsWith("/64");
        assertThat(other).isNotEqualTo(a);
    }

    @Test
    void treatsIpv4MappedIpv6AsIpv4() {
        assertThat(ClientAddress.toKey("::ffff:203.0.113.7")).contains("203.0.113.7");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "256.1.1.1",
            "1.2.3",
            "localhost",
            "example.com",
            "1:2",
            "2001:db8::1%eth0",
            "1.2.3.4, 5.6.7.8",
            "<script>",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    })
    void rejectsAnythingThatIsNotAnIpLiteral(String value) {
        assertThat(ClientAddress.toKey(value)).isEmpty();
    }

    @Test
    void rejectsNull() {
        assertThat(ClientAddress.toKey(null)).isEmpty();
    }
}
