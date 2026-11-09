package co.edu.corhuila.telemedia.workflow.app;

import co.edu.corhuila.telemedia.workflow.adapter.out.jdbc.JdbcSagaStore;
import co.edu.corhuila.telemedia.workflow.adapter.out.participants.HttpIdentityAccounts;
import co.edu.corhuila.telemedia.workflow.adapter.out.participants.HttpProfessionalProfiles;
import co.edu.corhuila.telemedia.workflow.adapter.out.participants.ParticipantClient;
import co.edu.corhuila.telemedia.workflow.adapter.out.participants.ServiceTokens;
import co.edu.corhuila.telemedia.workflow.application.port.out.IdGenerator;
import co.edu.corhuila.telemedia.workflow.application.port.out.IdentityAccounts;
import co.edu.corhuila.telemedia.workflow.application.port.out.ProfessionalProfiles;
import co.edu.corhuila.telemedia.workflow.application.port.out.SagaStore;
import co.edu.corhuila.telemedia.workflow.application.usecase.RunProfessionalOnboardingSaga;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The onboarding saga and its adapters: identity and professional-management over HTTP, and the store. */
@Configuration
class SagaConfiguration {

    @Bean
    HttpClient participantHttpClient(@Value("${workflow.participants.connect-timeout}") Duration connectTimeout) {
        return HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    /** Fails at startup without the client secret: the saga cannot call any participant without it. */
    @Bean
    ServiceTokens serviceTokens(HttpClient http, Clock clock,
                                @Value("${workflow.identity.url}") URI identityUrl,
                                @Value("${workflow.service-client.id}") String clientId,
                                @Value("${workflow.service-client.secret}") String clientSecret,
                                @Value("${workflow.participants.request-timeout}") Duration requestTimeout,
                                @Value("${workflow.service-client.refresh-margin}") Duration refreshMargin) {
        return new ServiceTokens(http, new ServiceTokens.Settings(identityUrl, clientId, clientSecret, requestTimeout,
                refreshMargin), clock);
    }

    @Bean
    IdentityAccounts identityAccounts(HttpClient http, ServiceTokens tokens,
                                      @Value("${workflow.identity.url}") URI identityUrl,
                                      @Value("${workflow.participants.request-timeout}") Duration requestTimeout,
                                      @Value("${workflow.participants.attempts}") int attempts,
                                      @Value("${workflow.participants.backoff}") Duration backoff) {
        return new HttpIdentityAccounts(new ParticipantClient(http, tokens,
                new ParticipantClient.Settings(identityUrl, requestTimeout, attempts, backoff)));
    }

    @Bean
    ProfessionalProfiles professionalProfiles(HttpClient http, ServiceTokens tokens,
                                              @Value("${workflow.professional-management.url}") URI url,
                                              @Value("${workflow.participants.request-timeout}") Duration requestTimeout,
                                              @Value("${workflow.participants.attempts}") int attempts,
                                              @Value("${workflow.participants.backoff}") Duration backoff) {
        return new HttpProfessionalProfiles(new ParticipantClient(http, tokens,
                new ParticipantClient.Settings(url, requestTimeout, attempts, backoff)));
    }

    /**
     * The saga state in the workflow schema of PostgreSQL (ADR-028), as {@code workflow_app}. Fails at
     * startup without the password: a saga whose state cannot be stored must not start.
     */
    @Bean
    SagaStore sagaStore(DataSource dataSource, ObjectMapper json,
                        @Value("${spring.datasource.password:}") String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("WORKFLOW_DB_PASSWORD is required: the saga state is stored in PostgreSQL");
        }
        return new JdbcSagaStore(dataSource, json);
    }

    @Bean
    IdGenerator idGenerator() {
        return UUID::randomUUID;
    }

    /** One bean for both input ports: StartProfessionalOnboarding and GetSaga. */
    @Bean
    RunProfessionalOnboardingSaga professionalOnboardingSaga(IdentityAccounts identity, ProfessionalProfiles profiles,
                                                             SagaStore store, IdGenerator ids, Clock clock) {
        return new RunProfessionalOnboardingSaga(identity, profiles, store, ids, clock);
    }
}
