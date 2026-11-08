package co.edu.corhuila.telemedia.workflow.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.corhuila.telemedia.workflow.application.port.in.ProfessionalOnboardingCommand;
import co.edu.corhuila.telemedia.workflow.application.port.out.IdentityAccounts;
import co.edu.corhuila.telemedia.workflow.application.port.out.NewProfessionalAccount;
import co.edu.corhuila.telemedia.workflow.application.port.out.ProfessionalProfiles;
import co.edu.corhuila.telemedia.workflow.application.port.out.SagaStore;
import co.edu.corhuila.telemedia.workflow.domain.saga.SagaInstance;
import co.edu.corhuila.telemedia.workflow.domain.saga.SagaStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RunProfessionalOnboardingSagaTest {

    private static final UUID SAGA_ID = UUID.fromString("35920dc9-bd55-41a0-8e41-5832377383e5");
    private static final UUID ACCOUNT_ID = UUID.fromString("7a1e9c3f-0000-4000-8000-000000000003");
    private static final UUID PROFESSIONAL_ID = UUID.fromString("9cfcfeec-c4b6-4d76-a0b4-f0db602d7caa");
    private static final String PASSWORD = "S3cret-Passw0rd!";
    private static final ProfessionalOnboardingCommand COMMAND = new ProfessionalOnboardingCommand(
            "Laura Gómez", "laura.gomez@example.com", "1075000111", PASSWORD, "MP-12345", 7);

    private final List<String> calls = new ArrayList<>();
    private final Participants participants = new Participants(calls);
    private final RecordingStore store = new RecordingStore();
    private final RunProfessionalOnboardingSaga saga = new RunProfessionalOnboardingSaga(participants, participants,
            store, () -> SAGA_ID, Clock.fixed(Instant.parse("2026-11-07T15:00:00Z"), ZoneOffset.UTC));

    @Test
    void createsTheInactiveAccountThenTheProfileThenActivatesTheAccount() {
        SagaInstance result = saga.start(COMMAND, "key-1");

        assertThat(calls).containsExactly("create-account", "create-profile", "activate-account");
        assertThat(result.status()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(result.completedSteps()).containsExactly("create-account", "create-profile", "activate-account");
        assertThat(result.get("accountId")).contains(ACCOUNT_ID.toString());
        assertThat(result.get("professionalId")).contains(PROFESSIONAL_ID.toString());
        assertThat(participants.profileAccountId).isEqualTo(ACCOUNT_ID);
        assertThat(participants.activatedAccountId).isEqualTo(ACCOUNT_ID);
    }

    @Test
    void sendsTheAccountDataToIdentityAndTheProfileDataToProfessionalManagement() {
        saga.start(COMMAND, "key-1");

        assertThat(participants.newAccount).isEqualTo(new NewProfessionalAccount(
                "Laura Gómez", "laura.gomez@example.com", "1075000111", PASSWORD));
        assertThat(participants.licenseNumber).isEqualTo("MP-12345");
        assertThat(participants.yearsExperience).isEqualTo(7);
    }

    @Test
    void everyStepSendsAnIdempotencyKeyDerivedFromTheSaga() {
        saga.start(COMMAND, "key-1");

        assertThat(participants.stepKeys).containsExactly(
                SAGA_ID + ":create-account", SAGA_ID + ":create-profile", SAGA_ID + ":activate-account");
    }

    @Test
    void storesTheStateAfterEveryStep() {
        saga.start(COMMAND, "key-1");

        assertThat(store.history).containsExactly(
                "RUNNING []",
                "RUNNING [create-account]",
                "RUNNING [create-account, create-profile]",
                "RUNNING [create-account, create-profile, activate-account]",
                "COMPLETED [create-account, create-profile, activate-account]");
    }

    @Test
    void whenTheProfileFailsItDeactivatesTheAccountAndEndsCompensated() {
        participants.failing.add("create-profile");

        SagaInstance result = saga.start(COMMAND, "key-1");

        assertThat(calls).containsExactly("create-account", "create-profile", "deactivate-account");
        assertThat(result.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(result.failedStep()).isEqualTo("create-profile");
        assertThat(result.compensatedSteps()).containsExactly("create-account");
        assertThat(participants.deactivatedAccountId).isEqualTo(ACCOUNT_ID);
    }

    @Test
    void whenTheActivationFailsItUndoesTheProfileAndThenTheAccount() {
        participants.failing.add("activate-account");

        SagaInstance result = saga.start(COMMAND, "key-1");

        assertThat(calls).containsExactly("create-account", "create-profile", "activate-account",
                "deactivate-profile", "deactivate-account");
        assertThat(result.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(result.failedStep()).isEqualTo("activate-account");
        assertThat(participants.deactivatedProfessionalId).isEqualTo(PROFESSIONAL_ID);
        assertThat(store.history).last().isEqualTo(
                "COMPENSATED [create-account, create-profile] undone [create-profile, create-account]");
    }

    @Test
    void whenTheAccountCannotBeCreatedThereIsNothingToUndo() {
        participants.failing.add("create-account");

        SagaInstance result = saga.start(COMMAND, "key-1");

        assertThat(calls).containsExactly("create-account");
        assertThat(result.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(result.failedStep()).isEqualTo("create-account");
    }

    @Test
    void whenACompensationFailsTheSagaEndsFailedAndStopsUndoing() {
        participants.failing.add("activate-account");
        participants.failing.add("deactivate-profile");

        SagaInstance result = saga.start(COMMAND, "key-1");

        assertThat(calls).containsExactly("create-account", "create-profile", "activate-account",
                "deactivate-profile");
        assertThat(result.status()).isEqualTo(SagaStatus.FAILED);
        assertThat(result.failedStep()).isEqualTo("activate-account");
        assertThat(result.failedCompensation()).isEqualTo("create-profile");
        assertThat(result.pendingCompensations()).containsExactly("create-profile", "create-account");
    }

    @Test
    void keepsTheFailureDetailInsideTheSaga() {
        participants.failing.add("create-profile");

        SagaInstance result = saga.start(COMMAND, "key-1");

        assertThat(result.failureDetail()).contains("create-profile refused");
    }

    @Test
    void neverKeepsThePasswordInTheSaga() {
        participants.failing.add("activate-account");

        SagaInstance result = saga.start(COMMAND, "key-1");

        assertThat(result.data().values()).doesNotContain(PASSWORD);
        assertThat(result.requestHash()).doesNotContain(PASSWORD);
        assertThat(COMMAND.toString()).doesNotContain(PASSWORD);
        assertThat(participants.newAccount.toString()).doesNotContain(PASSWORD);
    }

    /** Both participants, failing on demand and writing every call to one journal to check the order. */
    private static final class Participants implements IdentityAccounts, ProfessionalProfiles {
        private final List<String> calls;
        private final Set<String> failing = new HashSet<>();
        private final List<String> stepKeys = new ArrayList<>();
        private NewProfessionalAccount newAccount;
        private UUID profileAccountId;
        private UUID activatedAccountId;
        private UUID deactivatedAccountId;
        private UUID deactivatedProfessionalId;
        private String licenseNumber;
        private int yearsExperience;

        private Participants(List<String> calls) {
            this.calls = calls;
        }

        private void call(String operation) {
            calls.add(operation);
            if (failing.contains(operation)) {
                throw new IllegalStateException(operation + " refused with 503");
            }
        }

        @Override
        public UUID createInactiveProfessionalAccount(NewProfessionalAccount account, String idempotencyKey) {
            newAccount = account;
            stepKeys.add(idempotencyKey);
            call("create-account");
            return ACCOUNT_ID;
        }

        @Override
        public void activateAccount(UUID accountId, String idempotencyKey) {
            activatedAccountId = accountId;
            stepKeys.add(idempotencyKey);
            call("activate-account");
        }

        @Override
        public void deactivateAccount(UUID accountId) {
            deactivatedAccountId = accountId;
            call("deactivate-account");
        }

        @Override
        public UUID createProfile(UUID accountId, String license, int years, String idempotencyKey) {
            profileAccountId = accountId;
            licenseNumber = license;
            yearsExperience = years;
            stepKeys.add(idempotencyKey);
            call("create-profile");
            return PROFESSIONAL_ID;
        }

        @Override
        public void deactivateProfile(UUID professionalId) {
            deactivatedProfessionalId = professionalId;
            call("deactivate-profile");
        }
    }

    /** Writes down the state of the saga each time it is stored. */
    private static final class RecordingStore implements SagaStore {
        private final List<String> history = new ArrayList<>();

        @Override
        public void insert(SagaInstance saga) {
            record(saga);
        }

        @Override
        public void update(SagaInstance saga) {
            record(saga);
        }

        private void record(SagaInstance saga) {
            String undone = saga.compensatedSteps().isEmpty() ? "" : " undone " + saga.compensatedSteps();
            history.add(saga.status() + " " + saga.completedSteps() + undone);
        }
    }
}
