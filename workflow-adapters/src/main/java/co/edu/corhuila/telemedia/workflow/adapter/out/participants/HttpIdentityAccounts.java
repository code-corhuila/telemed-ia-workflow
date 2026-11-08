package co.edu.corhuila.telemedia.workflow.adapter.out.participants;

import co.edu.corhuila.telemedia.workflow.application.port.out.IdentityAccounts;
import co.edu.corhuila.telemedia.workflow.application.port.out.NewProfessionalAccount;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Identity-and-access as a saga participant: {@code POST /internal/accounts} creates the account
 * inactive (identity #143) and {@code PUT /internal/accounts/{id}/status} activates or deactivates
 * it (identity #144). The status change is idempotent in identity, so it sends no key.
 */
public final class HttpIdentityAccounts implements IdentityAccounts {

    static final String ACCOUNTS = "/internal/accounts";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ParticipantClient identity;

    public HttpIdentityAccounts(ParticipantClient identity) {
        this.identity = Objects.requireNonNull(identity, "identity");
    }

    @Override
    public UUID createInactiveProfessionalAccount(NewProfessionalAccount account, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fullName", account.fullName());
        body.put("email", account.email());
        body.put("identityDocument", account.identityDocument());
        body.put("password", account.password());
        body.put("role", "PROFESSIONAL");
        body.put("active", false);

        HttpResponse<String> response = identity.send("POST", ACCOUNTS, json(body), idempotencyKey);
        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new ParticipantException("POST " + ACCOUNTS + ": status " + response.statusCode());
        }
        return accountId(response.body());
    }

    @Override
    public void activateAccount(UUID accountId, String idempotencyKey) {
        changeStatus(accountId, true);
    }

    @Override
    public void deactivateAccount(UUID accountId) {
        changeStatus(accountId, false);
    }

    private void changeStatus(UUID accountId, boolean active) {
        String path = ACCOUNTS + "/" + accountId + "/status";
        HttpResponse<String> response = identity.send("PUT", path, json(Map.of("active", active)), null);
        if (response.statusCode() != 204) {
            throw new ParticipantException("PUT " + path + ": status " + response.statusCode());
        }
    }

    private static String json(Object body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("the request body could not be written");
        }
    }

    private static UUID accountId(String body) {
        try {
            JsonNode root = body == null ? null : JSON.readTree(body);
            JsonNode id = root == null ? null : root.get("id");
            if (id == null || !id.isTextual()) {
                throw new IllegalArgumentException("no id");
            }
            return UUID.fromString(id.asText());
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new ParticipantException("POST " + ACCOUNTS + ": invalid account answer");
        }
    }
}
