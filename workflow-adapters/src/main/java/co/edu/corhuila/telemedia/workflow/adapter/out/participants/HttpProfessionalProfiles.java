package co.edu.corhuila.telemedia.workflow.adapter.out.participants;

import co.edu.corhuila.telemedia.workflow.application.port.out.ProfessionalProfiles;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Professional-management as a saga participant (telemed-ia-docs#177, DEC-015 of that service):
 * {@code POST /internal/professionals} creates the profile of an account identity already created,
 * and {@code PATCH /api/professionals/{id}/status} with {@code INACTIVE} is the compensation. The
 * status change is idempotent in professional-management, so it sends no key.
 */
public final class HttpProfessionalProfiles implements ProfessionalProfiles {

    static final String PROFILES = "/internal/professionals";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ParticipantClient professionals;

    public HttpProfessionalProfiles(ParticipantClient professionals) {
        this.professionals = Objects.requireNonNull(professionals, "professionals");
    }

    @Override
    public UUID createProfile(UUID accountId, String licenseNumber, int yearsExperience, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", accountId.toString());
        body.put("licenseNumber", licenseNumber);
        body.put("yearsExperience", yearsExperience);

        HttpResponse<String> response = professionals.send("POST", PROFILES, json(body), idempotencyKey);
        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new ParticipantException("POST " + PROFILES + ": status " + response.statusCode());
        }
        JsonNode professional = parse(response.body());
        if (!accountId.equals(uuid(professional, "identityUserId"))) {
            throw new ParticipantException("POST " + PROFILES + ": the profile belongs to another account");
        }
        return uuid(professional, "id");
    }

    @Override
    public void deactivateProfile(UUID professionalId) {
        String path = "/api/professionals/" + professionalId + "/status";
        HttpResponse<String> response = professionals.send("PATCH", path, json(Map.of("status", "INACTIVE")), null);
        if (response.statusCode() != 200) {
            throw new ParticipantException("PATCH " + path + ": status " + response.statusCode());
        }
    }

    private static String json(Object body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("the request body could not be written");
        }
    }

    private static JsonNode parse(String body) {
        try {
            JsonNode root = body == null ? null : JSON.readTree(body);
            if (root == null || !root.isObject()) {
                throw invalid();
            }
            return root;
        } catch (JsonProcessingException e) {
            throw invalid();
        }
    }

    private static UUID uuid(JsonNode professional, String field) {
        JsonNode value = professional.get(field);
        if (value == null || !value.isTextual()) {
            throw invalid();
        }
        try {
            return UUID.fromString(value.asText());
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
    }

    private static ParticipantException invalid() {
        return new ParticipantException("POST " + PROFILES + ": invalid professional answer");
    }
}
