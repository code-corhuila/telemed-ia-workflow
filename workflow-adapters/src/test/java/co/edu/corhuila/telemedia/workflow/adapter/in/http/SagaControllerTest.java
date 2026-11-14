package co.edu.corhuila.telemedia.workflow.adapter.in.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.edu.corhuila.telemedia.workflow.application.port.in.GetSaga;
import co.edu.corhuila.telemedia.workflow.application.port.in.IdempotencyKeyReusedException;
import co.edu.corhuila.telemedia.workflow.application.port.in.ProfessionalOnboardingCommand;
import co.edu.corhuila.telemedia.workflow.application.port.in.StartProfessionalOnboarding;
import co.edu.corhuila.telemedia.workflow.domain.saga.SagaInstance;
import co.edu.corhuila.telemedia.workflow.domain.saga.UnknownSagaException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SagaControllerTest {

    private static final UUID SAGA_ID = UUID.fromString("35920dc9-bd55-41a0-8e41-5832377383e5");
    private static final String ACCOUNT_ID = "6f1c2b9e-0d4a-4e8b-9a77-3f5c1d2e8a10";
    private static final String PROFESSIONAL_ID = "a0b1c2d3-e4f5-4a6b-8c7d-9e0f1a2b3c4d";
    private static final Instant NOW = Instant.parse("2026-11-09T15:00:00Z");
    private static final String BODY = """
            {"fullName":"Ana Pérez","email":"ana@example.com","identityDocument":"1075000000",
             "password":"S3cret-pass!","licenseNumber":"RM-12345","yearsExperience":7}""";

    private final FakeSagas sagas = new FakeSagas();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new SagaController(sagas, sagas))
                .setControllerAdvice(new ErrorHandler())
                .build();
    }

    @Test
    void anAdminStartsTheOnboardingAndGets201WithTheSaga() throws Exception {
        sagas.next = new StartProfessionalOnboarding.Started(completedSaga(), true);

        mvc.perform(start("ADMIN").header("Idempotency-Key", "saga-key-1").content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/sagas/" + SAGA_ID))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(SAGA_ID.toString()))
                .andExpect(jsonPath("$.type").value("professional-onboarding"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedSteps[2]").value("activate-account"))
                .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID))
                .andExpect(jsonPath("$.professionalId").value(PROFESSIONAL_ID))
                .andExpect(jsonPath("$.createdAt").value("2026-11-09T15:00:00Z"))
                .andExpect(jsonPath("$.updatedAt").value("2026-11-09T15:00:00Z"))
                .andExpect(content().string(not(containsString("S3cret-pass!"))));

        assertThat(sagas.keys).containsExactly("saga-key-1");
        ProfessionalOnboardingCommand command = sagas.commands.get(0);
        assertThat(command.email()).isEqualTo("ana@example.com");
        assertThat(command.password()).isEqualTo("S3cret-pass!");
        assertThat(command.yearsExperience()).isEqualTo(7);
    }

    @Test
    void repeatingTheKeyAnswers200WithTheSameSaga() throws Exception {
        sagas.next = new StartProfessionalOnboarding.Started(completedSaga(), false);

        mvc.perform(start("ADMIN").header("Idempotency-Key", "saga-key-1").content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(SAGA_ID.toString()));
    }

    @Test
    void aCompensatedSagaIsStillCreatedAndNamesTheFailedStepWithoutItsDetail() throws Exception {
        sagas.next = new StartProfessionalOnboarding.Started(compensatedSaga(), true);

        mvc.perform(start("ADMIN").header("Idempotency-Key", "saga-key-2").content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPENSATED"))
                .andExpect(jsonPath("$.failedStep").value("create-profile"))
                .andExpect(jsonPath("$.compensatedSteps[0]").value("create-account"))
                .andExpect(content().string(not(containsString("profile service answered 409"))));
    }

    @Test
    void withoutAnIdempotencyKeyNothingStarts() throws Exception {
        mvc.perform(start("ADMIN").header("X-Correlation-Id", "trace-key").content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("Idempotency-Key"))
                .andExpect(jsonPath("$.traceId").exists());

        assertThat(sagas.commands).isEmpty();
    }

    @Test
    void invalidFieldsAreListedAndNothingStarts() throws Exception {
        String body = """
                {"fullName":" ","email":"not-an-email","identityDocument":"1075000000",
                 "password":"","licenseNumber":"RM-12345","yearsExperience":-1}""";

        mvc.perform(start("ADMIN").header("Idempotency-Key", "saga-key-3").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.length()").value(4))
                .andExpect(jsonPath("$.details[?(@.field == 'fullName')]").exists())
                .andExpect(jsonPath("$.details[?(@.field == 'email')]").exists())
                .andExpect(jsonPath("$.details[?(@.field == 'password')]").exists())
                .andExpect(jsonPath("$.details[?(@.field == 'yearsExperience')]").exists());

        assertThat(sagas.commands).isEmpty();
    }

    @Test
    void onlyAnAdminCanStartTheOnboarding() throws Exception {
        mvc.perform(start("PROFESSIONAL").header("Idempotency-Key", "saga-key-4").content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        assertThat(sagas.commands).isEmpty();
    }

    @Test
    void aKeyReusedForAnotherRequestAnswers422() throws Exception {
        sagas.failure = new IdempotencyKeyReusedException();

        mvc.perform(start("ADMIN").header("Idempotency-Key", "saga-key-1").content(BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("BUSINESS_RULE_VIOLATION"));
    }

    @Test
    void anAdminReadsASaga() throws Exception {
        sagas.stored = completedSaga();

        mvc.perform(get("/api/v1/sagas/" + SAGA_ID).with(caller("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(SAGA_ID.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void anUnknownSagaAnswers404() throws Exception {
        mvc.perform(get("/api/v1/sagas/" + UUID.randomUUID()).with(caller("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void aSagaIdThatIsNotAUuidAnswers400() throws Exception {
        mvc.perform(get("/api/v1/sagas/42").with(caller("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("id"));
    }

    @Test
    void onlyAnAdminCanReadASaga() throws Exception {
        sagas.stored = completedSaga();

        mvc.perform(get("/api/v1/sagas/" + SAGA_ID).with(caller("PATIENT")))
                .andExpect(status().isForbidden());
    }

    @Test
    void anIdempotencyKeyOutsideEightTo128CharactersIsRejected() throws Exception {
        for (String key : List.of("short-7", "k".repeat(129))) {
            mvc.perform(start("ADMIN").header("Idempotency-Key", key).content(BODY))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details[0].field").value("Idempotency-Key"));
        }
        assertThat(sagas.commands).isEmpty();
    }

    @Test
    void anIdempotencyKeyOfEightOr128CharactersIsAccepted() throws Exception {
        sagas.next = new StartProfessionalOnboarding.Started(completedSaga(), true);

        for (String key : List.of("eight-ch", "k".repeat(128))) {
            mvc.perform(start("ADMIN").header("Idempotency-Key", key).content(BODY))
                    .andExpect(status().isCreated());
        }
        assertThat(sagas.keys).containsExactly("eight-ch", "k".repeat(128));
    }

    private static MockHttpServletRequestBuilder start(String role) {
        return post("/api/v1/sagas/professional-onboarding")
                .with(caller(role))
                .contentType(MediaType.APPLICATION_JSON);
    }

    private static RequestPostProcessor caller(String role) {
        return request -> {
            request.setAttribute(AuthFilter.ATTRIBUTE, new Caller("user-1", Set.of(role)));
            return request;
        };
    }

    private static SagaInstance completedSaga() {
        SagaInstance saga = SagaInstance.start(SAGA_ID, "professional-onboarding", "saga-key-1", "hash", NOW);
        saga.stepCompleted("create-account", NOW);
        saga.put("accountId", ACCOUNT_ID);
        saga.stepCompleted("create-profile", NOW);
        saga.put("professionalId", PROFESSIONAL_ID);
        saga.stepCompleted("activate-account", NOW);
        saga.complete(NOW);
        return saga;
    }

    private static SagaInstance compensatedSaga() {
        SagaInstance saga = SagaInstance.start(SAGA_ID, "professional-onboarding", "saga-key-2", "hash", NOW);
        saga.stepCompleted("create-account", NOW);
        saga.put("accountId", ACCOUNT_ID);
        saga.stepFailed("create-profile", "profile service answered 409", NOW);
        saga.stepCompensated("create-account", NOW);
        saga.compensated(NOW);
        return saga;
    }

    private static final class FakeSagas implements StartProfessionalOnboarding, GetSaga {
        private final List<ProfessionalOnboardingCommand> commands = new ArrayList<>();
        private final List<String> keys = new ArrayList<>();
        private Started next;
        private RuntimeException failure;
        private SagaInstance stored;

        @Override
        public Started start(ProfessionalOnboardingCommand command, String idempotencyKey) {
            commands.add(command);
            keys.add(idempotencyKey);
            if (failure != null) {
                throw failure;
            }
            return next;
        }

        @Override
        public SagaInstance get(UUID id) {
            if (stored != null && stored.id().equals(id)) {
                return stored;
            }
            throw new UnknownSagaException(id);
        }
    }
}
