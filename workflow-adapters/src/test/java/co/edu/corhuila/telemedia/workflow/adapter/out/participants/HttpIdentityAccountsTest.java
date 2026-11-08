package co.edu.corhuila.telemedia.workflow.adapter.out.participants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.telemedia.workflow.application.port.out.NewProfessionalAccount;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class HttpIdentityAccountsTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final String PASSWORD = "Doctor-Secret-1";
    private static final NewProfessionalAccount ACCOUNT =
            new NewProfessionalAccount("Ana Doctor", "ana@telemed.co", "DOC-1", PASSWORD);

    private StubServer tokenIssuer;
    private StubServer identity;
    private HttpIdentityAccounts accounts;

    @BeforeEach
    void start() throws Exception {
        tokenIssuer = StubServer.start()
                .answer(200, token("token-1"))
                .answer(200, token("token-2"));
        identity = StubServer.start();
        accounts = accountsAt(identity.uri());
    }

    @AfterEach
    void stop() {
        MDC.clear();
        tokenIssuer.close();
        identity.close();
    }

    @Test
    void createsTheAccountInactiveWithTheKeyTheServiceTokenAndTheCorrelationId() {
        identity.answer(201, "{\"id\":\"" + ACCOUNT_ID + "\"}");
        MDC.put("correlationId", "corr-1");

        assertThat(accounts.createInactiveProfessionalAccount(ACCOUNT, "saga-1:create-account")).isEqualTo(ACCOUNT_ID);

        StubServer.Seen request = identity.seen().get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/internal/accounts");
        assertThat(request.header("Authorization")).isEqualTo("Bearer token-1");
        assertThat(request.header("Idempotency-Key")).isEqualTo("saga-1:create-account");
        assertThat(request.header("X-Correlation-Id")).isEqualTo("corr-1");
        assertThat(request.body())
                .contains("\"fullName\":\"Ana Doctor\"", "\"email\":\"ana@telemed.co\"",
                        "\"identityDocument\":\"DOC-1\"", "\"password\":\"" + PASSWORD + "\"",
                        "\"role\":\"PROFESSIONAL\"", "\"active\":false");
    }

    @Test
    void acceptsTheReplayOfAnAccountAlreadyCreated() {
        identity.answer(200, "{\"id\":\"" + ACCOUNT_ID + "\"}");

        assertThat(accounts.createInactiveProfessionalAccount(ACCOUNT, "saga-1:create-account")).isEqualTo(ACCOUNT_ID);
    }

    @Test
    void activatesAndDeactivatesTheAccount() {
        identity.answer(204, "");

        accounts.activateAccount(ACCOUNT_ID, "saga-1:activate-account");
        accounts.deactivateAccount(ACCOUNT_ID);

        assertThat(identity.seen()).extracting(StubServer.Seen::method).containsExactly("PUT", "PUT");
        assertThat(identity.seen()).extracting(StubServer.Seen::path)
                .containsOnly("/internal/accounts/" + ACCOUNT_ID + "/status");
        assertThat(identity.seen()).extracting(StubServer.Seen::body)
                .containsExactly("{\"active\":true}", "{\"active\":false}");
    }

    @Test
    void renewsARejectedTokenOnceAndRepeatsTheCall() {
        identity.answer(401, "").answer(204, "");

        accounts.activateAccount(ACCOUNT_ID, "saga-1:activate-account");

        assertThat(identity.seen()).extracting(seen -> seen.header("Authorization"))
                .containsExactly("Bearer token-1", "Bearer token-2");
    }

    @Test
    void retriesATransientFailureWithTheSameKey() {
        identity.answer(503, "").answer(502, "").answer(201, "{\"id\":\"" + ACCOUNT_ID + "\"}");

        assertThat(accounts.createInactiveProfessionalAccount(ACCOUNT, "saga-1:create-account")).isEqualTo(ACCOUNT_ID);

        assertThat(identity.seen()).hasSize(3)
                .extracting(seen -> seen.header("Idempotency-Key")).containsOnly("saga-1:create-account");
    }

    @Test
    void givesUpAfterTheLastAttemptWithoutTheBody() {
        identity.answer(503, "");

        assertThatThrownBy(() -> accounts.createInactiveProfessionalAccount(ACCOUNT, "saga-1:create-account"))
                .isInstanceOf(ParticipantException.class)
                .hasMessageContaining("503")
                .hasMessageNotContaining(PASSWORD);
        assertThat(identity.seen()).hasSize(3);
    }

    @Test
    void doesNotRetryARejection() {
        identity.answer(409, "{\"error\":\"REGISTRATION_CONFLICT\"}");

        assertThatThrownBy(() -> accounts.createInactiveProfessionalAccount(ACCOUNT, "saga-1:create-account"))
                .isInstanceOf(ParticipantException.class)
                .hasMessageContaining("409");
        assertThat(identity.seen()).hasSize(1);
    }

    @Test
    void failsWhenTheAnswerHasNoAccountId() {
        identity.answer(201, "{}");

        assertThatThrownBy(() -> accounts.createInactiveProfessionalAccount(ACCOUNT, "saga-1:create-account"))
                .isInstanceOf(ParticipantException.class);
    }

    @Test
    void failsWhenIdentityCannotBeReached() {
        URI closed = identity.uri();
        identity.close();

        assertThatThrownBy(() -> accountsAt(closed).deactivateAccount(ACCOUNT_ID))
                .isInstanceOf(ParticipantException.class)
                .hasMessageContaining("could not be reached");
    }

    private HttpIdentityAccounts accountsAt(URI identityUrl) {
        HttpClient http = HttpClient.newHttpClient();
        ServiceTokens tokens = new ServiceTokens(http, new ServiceTokens.Settings(tokenIssuer.uri(), "workflow",
                "secret", Duration.ofSeconds(2), Duration.ofSeconds(60)), Clock.systemUTC());
        return new HttpIdentityAccounts(new ParticipantClient(http, tokens,
                new ParticipantClient.Settings(identityUrl, Duration.ofSeconds(2), 3, Duration.ZERO)));
    }

    private static String token(String value) {
        return "{\"accessToken\":\"" + value + "\",\"tokenType\":\"Bearer\",\"expiresAt\":\""
                + Instant.now().plusSeconds(600) + "\"}";
    }
}
