package co.edu.corhuila.telemedia.workflow.adapter.out.participants;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.MDC;

/**
 * One saga participant over HTTP, called with the workflow's service token. A rejected token (401)
 * is renewed once; a transient failure (5xx or unreachable) is retried up to {@code attempts}
 * times with the same request, so the participant's {@code Idempotency-Key} keeps a retry from
 * repeating the effect. Any other answer is returned to the adapter, which decides.
 *
 * <p>Failure messages name the method, path and status only: never a body, a token or a secret.
 */
public final class ParticipantClient {

    /** Set by the correlation filter for the current request. */
    private static final String CORRELATION_MDC_KEY = "correlationId";

    public record Settings(URI baseUrl, Duration requestTimeout, int attempts, Duration backoff) {

        public Settings {
            Objects.requireNonNull(baseUrl, "baseUrl");
            Objects.requireNonNull(requestTimeout, "requestTimeout");
            Objects.requireNonNull(backoff, "backoff");
            if (!requestTimeout.isPositive() || attempts < 1 || backoff.isNegative()) {
                throw new IllegalArgumentException("a positive timeout, at least one attempt and a backoff >= 0");
            }
        }
    }

    private final HttpClient http;
    private final ServiceTokens tokens;
    private final Settings settings;

    public ParticipantClient(HttpClient http, ServiceTokens tokens, Settings settings) {
        this.http = Objects.requireNonNull(http, "http");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /** @param idempotencyKey sent as {@code Idempotency-Key} when not null */
    public HttpResponse<String> send(String method, String path, String jsonBody, String idempotencyKey) {
        String call = method + " " + path;
        boolean renewed = false;
        int attempt = 0;
        while (true) {
            attempt++;
            String token = tokens.token();
            HttpResponse<String> response;
            try {
                response = http.send(request(method, path, jsonBody, idempotencyKey, token),
                        HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                if (attempt >= settings.attempts()) {
                    throw new ParticipantException(call + ": the participant could not be reached");
                }
                pause(call);
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ParticipantException(call + ": interrupted");
            }
            if (response.statusCode() == 401 && !renewed) {
                tokens.invalidate(token);
                renewed = true;
                attempt--; // the renewal does not use up an attempt
                continue;
            }
            if (response.statusCode() >= 500 && attempt < settings.attempts()) {
                pause(call);
                continue;
            }
            return response;
        }
    }

    private HttpRequest request(String method, String path, String jsonBody, String idempotencyKey, String token) {
        HttpRequest.Builder request = HttpRequest.newBuilder(settings.baseUrl().resolve(path))
                .timeout(settings.requestTimeout())
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        String correlationId = MDC.get(CORRELATION_MDC_KEY);
        if (correlationId != null) {
            request.header("X-Correlation-Id", correlationId);
        }
        return request.build();
    }

    private void pause(String call) {
        try {
            Thread.sleep(settings.backoff());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ParticipantException(call + ": interrupted");
        }
    }
}
