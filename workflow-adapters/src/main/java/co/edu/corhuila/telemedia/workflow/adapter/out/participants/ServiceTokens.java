package co.edu.corhuila.telemedia.workflow.adapter.out.participants;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;

/**
 * The workflow's own credential (Anexo E): a service token from identity's
 * {@code POST /internal/service-token}, never the person's token, which could expire in the middle
 * of a compensation. The token is kept in memory and requested again {@code refreshMargin} before
 * it expires, or after a participant rejected it.
 *
 * <p>Neither the secret nor a token is ever logged or put in an exception message.
 */
public final class ServiceTokens {

    static final String PATH = "/internal/service-token";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** @param clientSecret from the environment, never versioned; left out of {@link #toString()} */
    public record Settings(URI identityUrl, String clientId, String clientSecret, Duration requestTimeout,
                           Duration refreshMargin) {

        public Settings {
            Objects.requireNonNull(identityUrl, "identityUrl");
            Objects.requireNonNull(requestTimeout, "requestTimeout");
            Objects.requireNonNull(refreshMargin, "refreshMargin");
            if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
                throw new IllegalArgumentException("the service client id and secret are required");
            }
            if (!requestTimeout.isPositive() || refreshMargin.isNegative()) {
                throw new IllegalArgumentException("the timeout must be positive and the margin not negative");
            }
        }

        @Override
        public String toString() {
            return "Settings[identityUrl=" + identityUrl + ", clientId=" + clientId + ", clientSecret=***"
                    + ", requestTimeout=" + requestTimeout + ", refreshMargin=" + refreshMargin + "]";
        }
    }

    private record Issued(String value, Instant expiresAt) {
    }

    private final HttpClient http;
    private final Settings settings;
    private final Clock clock;
    private final URI endpoint;
    private Issued current;

    public ServiceTokens(HttpClient http, Settings settings, Clock clock) {
        this.http = Objects.requireNonNull(http, "http");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.endpoint = settings.identityUrl().resolve(PATH);
    }

    public synchronized String token() {
        if (current == null || !clock.instant().isBefore(current.expiresAt().minus(settings.refreshMargin()))) {
            current = request();
        }
        return current.value();
    }

    /** Forgets the token a participant rejected, so the next call asks for a new one. */
    public synchronized void invalidate(String rejected) {
        if (current != null && current.value().equals(rejected)) {
            current = null;
        }
    }

    private Issued request() {
        HttpResponse<String> response;
        try {
            response = http.send(HttpRequest.newBuilder(endpoint)
                    .timeout(settings.requestTimeout())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(credentials()))
                    .build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ParticipantException("POST " + PATH + ": identity could not be reached");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ParticipantException("POST " + PATH + ": interrupted");
        }
        if (response.statusCode() != 200) {
            throw new ParticipantException("POST " + PATH + ": status " + response.statusCode());
        }
        return issued(response.body());
    }

    private String credentials() {
        try {
            return JSON.writeValueAsString(Map.of("clientId", settings.clientId(), "clientSecret",
                    settings.clientSecret()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("the credentials could not be written");
        }
    }

    private static Issued issued(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode token = root == null ? null : root.get("accessToken");
            JsonNode type = root == null ? null : root.get("tokenType");
            JsonNode expiresAt = root == null ? null : root.get("expiresAt");
            if (token == null || !token.isTextual() || token.asText().isBlank()
                    || type == null || !"Bearer".equalsIgnoreCase(type.asText())
                    || expiresAt == null || !expiresAt.isTextual()) {
                throw invalid();
            }
            return new Issued(token.asText(), Instant.parse(expiresAt.asText()));
        } catch (IOException | DateTimeParseException e) {
            throw invalid();
        }
    }

    private static ParticipantException invalid() {
        return new ParticipantException("POST " + PATH + ": invalid service token answer");
    }
}
