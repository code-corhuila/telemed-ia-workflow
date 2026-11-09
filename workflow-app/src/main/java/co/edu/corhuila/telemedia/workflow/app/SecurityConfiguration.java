package co.edu.corhuila.telemedia.workflow.app;

import co.edu.corhuila.telemedia.workflow.adapter.in.http.Rs256Verifier;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The identity service's public key; the workflow verifies tokens but never signs them. */
@Configuration
class SecurityConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Fails at startup when the key is missing or not a valid RSA PUBLIC KEY PEM. */
    @Bean
    Rs256Verifier rs256Verifier(@Value("${workflow.jwt.public-key}") String pem, Clock clock) {
        return new Rs256Verifier(Rs256Verifier.parsePublicKey(pem), clock);
    }
}
