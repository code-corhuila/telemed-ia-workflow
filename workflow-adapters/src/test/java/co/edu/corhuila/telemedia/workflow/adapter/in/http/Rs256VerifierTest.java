package co.edu.corhuila.telemedia.workflow.adapter.in.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.telemedia.workflow.adapter.in.http.Rs256Verifier.InvalidTokenException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class Rs256VerifierTest {

    private static final Instant NOW = Instant.parse("2026-11-07T15:00:00Z");
    private static final KeyPair KEYS = rsa();
    private static final KeyPair OTHER_KEYS = rsa();

    private final Rs256Verifier verifier = new Rs256Verifier((RSAPublicKey) KEYS.getPublic(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void acceptsAValidTokenAndReadsTheSubjectAndTheRole() {
        Caller caller = verifier.verify(token("{\"alg\":\"RS256\"}",
                "{\"sub\":\"admin-1\",\"role\":\"ADMIN\",\"exp\":" + (NOW.getEpochSecond() + 300) + "}"));

        assertThat(caller.subject()).isEqualTo("admin-1");
        assertThat(caller.hasRole("ADMIN")).isTrue();
        assertThat(caller.hasRole("PATIENT")).isFalse();
    }

    @Test
    void mergesTheRoleStringAndTheRolesArray() {
        Caller caller = verifier.verify(token("{\"alg\":\"RS256\"}",
                "{\"sub\":\"u\",\"role\":\"ADMIN\",\"roles\":[\"PROFESSIONAL\",7],\"exp\":"
                        + (NOW.getEpochSecond() + 300) + "}"));

        assertThat(caller.roles()).containsExactlyInAnyOrder("ADMIN", "PROFESSIONAL");
    }

    @Test
    void toleratesThirtySecondsOfClockSkew() {
        assertThat(verifier.verify(token("{\"alg\":\"RS256\"}",
                "{\"sub\":\"u\",\"exp\":" + (NOW.getEpochSecond() - 20) + "}")).subject()).isEqualTo("u");
    }

    @Test
    void rejectsAnExpiredToken() {
        assertRejected(token("{\"alg\":\"RS256\"}", "{\"sub\":\"u\",\"exp\":" + (NOW.getEpochSecond() - 31) + "}"),
                "the token has expired");
    }

    @Test
    void rejectsATokenWithoutExpiry() {
        assertRejected(token("{\"alg\":\"RS256\"}", "{\"sub\":\"u\"}"), "exp is required");
    }

    @Test
    void rejectsATokenWithoutSubject() {
        assertRejected(token("{\"alg\":\"RS256\"}", "{\"exp\":" + (NOW.getEpochSecond() + 300) + "}"),
                "sub is required");
    }

    @Test
    void rejectsAnyAlgorithmButRs256() {
        String payload = "{\"sub\":\"u\",\"exp\":" + (NOW.getEpochSecond() + 300) + "}";
        assertRejected(token("{\"alg\":\"none\"}", payload), "alg must be RS256");
        assertRejected(token("{\"alg\":\"HS256\"}", payload), "alg must be RS256");
    }

    @Test
    void rejectsATokenSignedWithAnotherKey() {
        assertRejected(signed("{\"alg\":\"RS256\"}", "{\"sub\":\"u\",\"exp\":" + (NOW.getEpochSecond() + 300) + "}",
                OTHER_KEYS.getPrivate()), "invalid signature");
    }

    @Test
    void rejectsAMalformedToken() {
        assertRejected("not-a-token", "the token must have three parts");
        assertRejected("a.b.c", "the token header is malformed");
        assertRejected(" ", "missing token");
    }

    @Test
    void readsAPublicKeyPemWrittenOnOneLine() {
        String body = Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded());
        String pem = "-----BEGIN PUBLIC KEY-----\\n" + body + "\\n-----END PUBLIC KEY-----";

        assertThat(Rs256Verifier.parsePublicKey(pem)).isEqualTo(KEYS.getPublic());
        assertThatThrownBy(() -> Rs256Verifier.parsePublicKey(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    private void assertRejected(String token, String reason) {
        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(InvalidTokenException.class).hasMessage(reason);
    }

    private static String token(String header, String payload) {
        return signed(header, payload, KEYS.getPrivate());
    }

    static String signed(String header, String payload, PrivateKey key) {
        Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
        String unsigned = url.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
                + url.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(key);
            signature.update(unsigned.getBytes(StandardCharsets.US_ASCII));
            return unsigned + "." + url.encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair rsa() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
