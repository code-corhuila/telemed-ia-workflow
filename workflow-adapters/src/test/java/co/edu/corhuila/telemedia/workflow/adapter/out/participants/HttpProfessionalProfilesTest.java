package co.edu.corhuila.telemedia.workflow.adapter.out.participants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpProfessionalProfilesTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID PROFESSIONAL_ID = UUID.fromString("77777777-7777-4777-8777-777777777777");

    private StubServer tokenIssuer;
    private StubServer professionals;
    private HttpProfessionalProfiles profiles;

    @BeforeEach
    void start() throws Exception {
        tokenIssuer = StubServer.start().answer(200, "{\"accessToken\":\"token-1\",\"tokenType\":\"Bearer\","
                + "\"expiresAt\":\"" + Instant.now().plusSeconds(600) + "\"}");
        professionals = StubServer.start();
        HttpClient http = HttpClient.newHttpClient();
        ServiceTokens tokens = new ServiceTokens(http, new ServiceTokens.Settings(tokenIssuer.uri(), "workflow",
                "secret", Duration.ofSeconds(2), Duration.ofSeconds(60)), Clock.systemUTC());
        profiles = new HttpProfessionalProfiles(new ParticipantClient(http, tokens,
                new ParticipantClient.Settings(professionals.uri(), Duration.ofSeconds(2), 3, Duration.ZERO)));
    }

    @AfterEach
    void stop() {
        tokenIssuer.close();
        professionals.close();
    }

    @Test
    void createsTheProfileOfTheAccountWithTheStepKey() {
        professionals.answer(201, professional(PROFESSIONAL_ID, ACCOUNT_ID));

        assertThat(profiles.createProfile(ACCOUNT_ID, "LIC-123", 7, "saga-1:create-profile"))
                .isEqualTo(PROFESSIONAL_ID);

        StubServer.Seen request = professionals.seen().get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/internal/professionals");
        assertThat(request.header("Authorization")).isEqualTo("Bearer token-1");
        assertThat(request.header("Idempotency-Key")).isEqualTo("saga-1:create-profile");
        assertThat(request.body()).isEqualTo(
                "{\"accountId\":\"" + ACCOUNT_ID + "\",\"licenseNumber\":\"LIC-123\",\"yearsExperience\":7}");
    }

    @Test
    void acceptsTheReplayOfAProfileAlreadyCreated() {
        professionals.answer(200, professional(PROFESSIONAL_ID, ACCOUNT_ID));

        assertThat(profiles.createProfile(ACCOUNT_ID, "LIC-123", 7, "saga-1:create-profile"))
                .isEqualTo(PROFESSIONAL_ID);
    }

    /** A profile of another account would be compensated by mistake later, so it is a failure. */
    @Test
    void rejectsAProfileOfAnotherAccount() {
        professionals.answer(201, professional(PROFESSIONAL_ID, UUID.randomUUID()));

        assertThatThrownBy(() -> profiles.createProfile(ACCOUNT_ID, "LIC-123", 7, "saga-1:create-profile"))
                .isInstanceOf(ParticipantException.class);
    }

    @Test
    void failsOnAConflictWithoutRetrying() {
        professionals.answer(409, "{\"error\":\"CONFLICT\"}");

        assertThatThrownBy(() -> profiles.createProfile(ACCOUNT_ID, "LIC-123", 7, "saga-1:create-profile"))
                .isInstanceOf(ParticipantException.class)
                .hasMessageContaining("409");
        assertThat(professionals.seen()).hasSize(1);
    }

    @Test
    void deactivatesTheProfileToCompensate() {
        professionals.answer(200, professional(PROFESSIONAL_ID, ACCOUNT_ID));

        profiles.deactivateProfile(PROFESSIONAL_ID);

        StubServer.Seen request = professionals.seen().get(0);
        assertThat(request.method()).isEqualTo("PATCH");
        assertThat(request.path()).isEqualTo("/api/professionals/" + PROFESSIONAL_ID + "/status");
        assertThat(request.body()).isEqualTo("{\"status\":\"INACTIVE\"}");
    }

    @Test
    void failsWhenTheDeactivationIsRefused() {
        professionals.answer(403, "{\"error\":\"FORBIDDEN\"}");

        assertThatThrownBy(() -> profiles.deactivateProfile(PROFESSIONAL_ID))
                .isInstanceOf(ParticipantException.class)
                .hasMessageContaining("403");
    }

    private static String professional(UUID id, UUID accountId) {
        return "{\"id\":\"" + id + "\",\"identityUserId\":\"" + accountId + "\",\"licenseNumber\":\"LIC-123\","
                + "\"professionalType\":\"GENERAL_PRACTITIONER\",\"status\":\"ACTIVE\"}";
    }
}
