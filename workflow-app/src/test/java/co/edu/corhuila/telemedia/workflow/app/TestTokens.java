package co.edu.corhuila.telemedia.workflow.app;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;

/** A throwaway RSA key pair for the HTTP tests: the service gets the public key, the tests sign. */
final class TestTokens {

    private static final KeyPair KEYS = generate();

    private TestTokens() {
    }

    static String publicKeyPem() {
        return "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder().encodeToString(KEYS.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
    }

    static String bearer(String subject, String role) {
        String payload = "{\"sub\":\"" + subject + "\",\"role\":\"" + role + "\",\"exp\":"
                + Instant.now().plusSeconds(300).getEpochSecond() + "}";
        Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
        String unsigned = url.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8))
                + "." + url.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(KEYS.getPrivate());
            signature.update(unsigned.getBytes(StandardCharsets.US_ASCII));
            return "Bearer " + unsigned + "." + url.encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
