package co.edu.corhuila.telemedia.workflow.adapter.out.participants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ServiceTokensTest {

    private static final Instant NOW = Instant.parse("2026-11-07T15:00:00Z");
    private static final String SECRET = "very-secret-value";

    private StubServer identity;
    private MutableClock clock;
    private ServiceTokens tokens;

    @BeforeEach
    void start() throws Exception {
        identity = StubServer.start();
        clock = new MutableClock(NOW);
        tokens = new ServiceTokens(HttpClient.newHttpClient(), new ServiceTokens.Settings(identity.uri(), "workflow",
                SECRET, Duration.ofSeconds(2), Duration.ofSeconds(60)), clock);
    }

    @AfterEach
    void stop() {
        identity.close();
    }

    @Test
    void asksIdentityForATokenWithTheClientCredentials() {
        identity.answer(200, issued("token-1", NOW.plusSeconds(600)));

        assertThat(tokens.token()).isEqualTo("token-1");

        StubServer.Seen request = identity.seen().get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/internal/service-token");
        assertThat(request.body()).contains("\"clientId\":\"workflow\"").contains("\"clientSecret\":\"" + SECRET + "\"");
    }

    @Test
    void keepsTheTokenUntilTheRefreshMarginBeforeItExpires() {
        identity.answer(200, issued("token-1", NOW.plusSeconds(600)))
                .answer(200, issued("token-2", NOW.plusSeconds(1200)));

        tokens.token();
        clock.now = NOW.plusSeconds(539);
        assertThat(tokens.token()).isEqualTo("token-1");

        clock.now = NOW.plusSeconds(540);
        assertThat(tokens.token()).isEqualTo("token-2");
        assertThat(identity.seen()).hasSize(2);
    }

    @Test
    void asksAgainAfterTheTokenWasRejected() {
        identity.answer(200, issued("token-1", NOW.plusSeconds(600)))
                .answer(200, issued("token-2", NOW.plusSeconds(600)));
        tokens.token();

        tokens.invalidate("token-1");

        assertThat(tokens.token()).isEqualTo("token-2");
    }

    @Test
    void forgettingAnOlderTokenKeepsTheCurrentOne() {
        identity.answer(200, issued("token-1", NOW.plusSeconds(600)));
        tokens.token();

        tokens.invalidate("token-0");

        assertThat(tokens.token()).isEqualTo("token-1");
        assertThat(identity.seen()).hasSize(1);
    }

    @Test
    void aRefusedRequestFailsWithoutTheSecret() {
        identity.answer(401, "{\"error\":\"UNAUTHORIZED\"}");

        assertThatThrownBy(tokens::token).isInstanceOf(ParticipantException.class)
                .hasMessageContaining("401").hasMessageNotContaining(SECRET);
    }

    @Test
    void anAnswerWithoutABearerTokenIsRejected() {
        identity.answer(200, "{\"accessToken\":\"x\",\"tokenType\":\"Basic\",\"expiresAt\":\"2026-11-07T16:00:00Z\"}");

        assertThatThrownBy(tokens::token).isInstanceOf(ParticipantException.class);
    }

    @Test
    void theSettingsHideTheSecret() {
        assertThat(new ServiceTokens.Settings(identity.uri(), "workflow", SECRET, Duration.ofSeconds(2),
                Duration.ofSeconds(60)).toString()).doesNotContain(SECRET);
        assertThatThrownBy(() -> new ServiceTokens.Settings(identity.uri(), "workflow", " ", Duration.ofSeconds(2),
                Duration.ofSeconds(60))).isInstanceOf(IllegalArgumentException.class);
    }

    private static String issued(String token, Instant expiresAt) {
        return "{\"accessToken\":\"" + token + "\",\"tokenType\":\"Bearer\",\"expiresAt\":\"" + expiresAt + "\"}";
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
