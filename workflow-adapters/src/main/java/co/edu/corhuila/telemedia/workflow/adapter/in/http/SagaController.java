package co.edu.corhuila.telemedia.workflow.adapter.in.http;

import co.edu.corhuila.telemedia.workflow.application.port.in.GetSaga;
import co.edu.corhuila.telemedia.workflow.application.port.in.ProfessionalOnboardingCommand;
import co.edu.corhuila.telemedia.workflow.application.port.in.StartProfessionalOnboarding;
import co.edu.corhuila.telemedia.workflow.domain.saga.SagaInstance;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entry point of the sagas (WF-004): an {@code ADMIN} starts the professional onboarding and reads
 * a saga back. The saga runs inside the request, so the answer carries its final state.
 */
@RestController
public class SagaController {

    static final String SAGAS = "/api/v1/sagas";
    private static final String ADMIN = "ADMIN";
    private static final int MIN_KEY_LENGTH = 8;
    private static final int MAX_KEY_LENGTH = 128;
    private static final int MAX_YEARS_EXPERIENCE = 70;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final StartProfessionalOnboarding onboarding;
    private final GetSaga sagas;

    public SagaController(StartProfessionalOnboarding onboarding, GetSaga sagas) {
        this.onboarding = onboarding;
        this.sagas = sagas;
    }

    /** The body of the start; {@link #toString()} never prints the password. */
    public record OnboardingRequest(String fullName, String email, String identityDocument, String password,
                                    String licenseNumber, Integer yearsExperience) {
        @Override
        public String toString() {
            return "OnboardingRequest[email=" + email + ", licenseNumber=" + licenseNumber + "]";
        }
    }

    @PostMapping(path = SAGAS + "/professional-onboarding", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SagaResponse> start(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody OnboardingRequest body,
            HttpServletRequest request) {
        requireAdmin(request);
        validate(idempotencyKey, body);
        StartProfessionalOnboarding.Started started = onboarding.start(new ProfessionalOnboardingCommand(
                body.fullName().trim(), body.email().trim(), body.identityDocument().trim(), body.password(),
                body.licenseNumber().trim(), body.yearsExperience()), idempotencyKey);
        SagaResponse saga = SagaResponse.of(started.saga());
        return started.created()
                ? ResponseEntity.created(URI.create(SAGAS + "/" + saga.id())).body(saga)
                : ResponseEntity.ok(saga);
    }

    @GetMapping(path = SAGAS + "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public SagaResponse get(@PathVariable String id, HttpServletRequest request) {
        requireAdmin(request);
        UUID sagaId;
        try {
            sagaId = UUID.fromString(id);
        } catch (IllegalArgumentException notAUuid) {
            throw new InvalidRequestException(List.of(new ApiError.FieldDetail("id", "must be a saga id (UUID)")));
        }
        return SagaResponse.of(sagas.get(sagaId));
    }

    private static void requireAdmin(HttpServletRequest request) {
        if (!AuthFilter.currentCaller(request).hasRole(ADMIN)) {
            throw new ForbiddenException();
        }
    }

    private static void validate(String idempotencyKey, OnboardingRequest body) {
        List<ApiError.FieldDetail> details = new ArrayList<>();
        if (blank(idempotencyKey) || idempotencyKey.length() < MIN_KEY_LENGTH
                || idempotencyKey.length() > MAX_KEY_LENGTH) {
            details.add(new ApiError.FieldDetail("Idempotency-Key", "is required, from 8 to 128 characters"));
        }
        requireText(details, "fullName", body.fullName());
        if (blank(body.email()) || !EMAIL.matcher(body.email().trim()).matches()) {
            details.add(new ApiError.FieldDetail("email", "must be an e-mail address"));
        }
        requireText(details, "identityDocument", body.identityDocument());
        requireText(details, "password", body.password());
        requireText(details, "licenseNumber", body.licenseNumber());
        Integer years = body.yearsExperience();
        if (years == null || years < 0 || years > MAX_YEARS_EXPERIENCE) {
            details.add(new ApiError.FieldDetail("yearsExperience", "must be between 0 and 70"));
        }
        if (!details.isEmpty()) {
            throw new InvalidRequestException(details);
        }
    }

    private static void requireText(List<ApiError.FieldDetail> details, String field, String value) {
        if (blank(value)) {
            details.add(new ApiError.FieldDetail(field, "is required"));
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** What a saga looks like from outside: no failure detail, no request, no password. */
    public record SagaResponse(String id, String type, String status, String failedStep,
                               List<String> completedSteps, List<String> compensatedSteps,
                               String accountId, String professionalId, String createdAt, String updatedAt) {
        static SagaResponse of(SagaInstance saga) {
            return new SagaResponse(saga.id().toString(), saga.type(), saga.status().name(), saga.failedStep(),
                    saga.completedSteps(), saga.compensatedSteps(),
                    saga.get("accountId").orElse(null), saga.get("professionalId").orElse(null),
                    saga.createdAt().toString(), saga.updatedAt().toString());
        }
    }
}
