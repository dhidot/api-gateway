package com.careertrack.gateway.security;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RevocationListTest {

    private static final String ALICE = "11111111-1111-1111-1111-111111111111";
    private static final String BOB = "22222222-2222-2222-2222-222222222222";
    private static final Instant NOW = Instant.parse("2026-11-27T10:00:00Z");

    private static RevocationList with(RevocationList.Entry... entries) {
        RevocationList list = new RevocationList();
        list.replace(new RevocationList.Payload(0L, Arrays.asList(entries)), NOW);
        return list;
    }

    private static RevocationList.Entry session(String sub, String sid) {
        return new RevocationList.Entry("SESSION", sub, sid, null, 9_999_999_999L);
    }

    private static RevocationList.Entry user(String sub, long notBefore) {
        return new RevocationList.Entry("USER", sub, null, notBefore, 9_999_999_999L);
    }

    @Test
    void emptyListRevokesNothing() {
        assertThat(new RevocationList().isRevoked(ALICE, "s1", NOW)).isFalse();
        assertThat(new RevocationList().loadedAt()).isNull();
    }

    @Test
    void revokesTheLoggedOutSessionOnly() {
        RevocationList list = with(session(ALICE, "laptop"));

        assertThat(list.isRevoked(ALICE, "laptop", NOW)).isTrue();
        assertThat(list.isRevoked(ALICE, "phone", NOW)).isFalse();
        assertThat(list.isRevoked(ALICE, null, NOW)).isFalse();
    }

    @Test
    void userCutOffRevokesTokensIssuedStrictlyBefore() {
        long cutOff = NOW.getEpochSecond();
        RevocationList list = with(user(ALICE, cutOff));

        assertThat(list.isRevoked(ALICE, "any", Instant.ofEpochSecond(cutOff - 1))).isTrue();
        assertThat(list.isRevoked(ALICE, "any", Instant.ofEpochSecond(cutOff))).isFalse();
        assertThat(list.isRevoked(BOB, "any", Instant.ofEpochSecond(cutOff - 1))).isFalse();
    }

    @Test
    void tokenWithoutIatCannotProveItIsNewer() {
        assertThat(with(user(ALICE, NOW.getEpochSecond())).isRevoked(ALICE, "any", null)).isTrue();
    }

    @Test
    void latestCutOffWinsWhenThereAreSeveral() {
        long early = NOW.getEpochSecond() - 100;
        long late = NOW.getEpochSecond();
        RevocationList list = with(user(ALICE, early), user(ALICE, late));

        assertThat(list.isRevoked(ALICE, "any", Instant.ofEpochSecond(late - 1))).isTrue();
    }

    @Test
    void ignoresMalformedEntries() {
        RevocationList list = with(
                null,
                new RevocationList.Entry("SESSION", null, "x", null, 1L),
                new RevocationList.Entry("SESSION", ALICE, null, null, 1L),
                new RevocationList.Entry("USER", ALICE, null, null, 1L),
                new RevocationList.Entry("SOMETHING", ALICE, "y", 1L, 1L)
        );

        assertThat(list.size()).isZero();
        assertThat(list.loadedAt()).isEqualTo(NOW);
    }

    @Test
    void replaceSwapsTheWholeSnapshot() {
        RevocationList list = with(session(ALICE, "laptop"));
        list.replace(new RevocationList.Payload(0L, List.of()), NOW.plusSeconds(5));

        assertThat(list.isRevoked(ALICE, "laptop", NOW)).isFalse();
        assertThat(list.loadedAt()).isEqualTo(NOW.plusSeconds(5));
    }
}
