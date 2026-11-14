package co.edu.corhuila.telemedia.workflow.application.usecase;

import co.edu.corhuila.telemedia.workflow.application.port.in.GetSaga;
import co.edu.corhuila.telemedia.workflow.application.port.in.IdempotencyKeyReusedException;
import co.edu.corhuila.telemedia.workflow.application.port.in.ProfessionalOnboardingCommand;
import co.edu.corhuila.telemedia.workflow.application.port.in.RecoverInterruptedSagas;
import co.edu.corhuila.telemedia.workflow.application.port.in.StartProfessionalOnboarding;
import co.edu.corhuila.telemedia.workflow.application.port.out.DuplicateSagaException;
import co.edu.corhuila.telemedia.workflow.application.port.out.IdGenerator;
import co.edu.corhuila.telemedia.workflow.application.port.out.IdentityAccounts;
import co.edu.corhuila.telemedia.workflow.application.port.out.NewProfessionalAccount;
import co.edu.corhuila.telemedia.workflow.application.port.out.ProfessionalProfiles;
import co.edu.corhuila.telemedia.workflow.application.port.out.SagaStore;
import co.edu.corhuila.telemedia.workflow.domain.saga.SagaInstance;
import co.edu.corhuila.telemedia.workflow.domain.saga.UnknownSagaException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The orchestrator of the professional onboarding saga (Anexo E):
 *
 * <ol>
 *   <li>{@code create-account}: identity creates the account inactive; undone by deactivating it;
 *   <li>{@code create-profile}: professional-management creates the profile; undone by deactivating it;
 *   <li>{@code activate-account}: identity activates the account. It cannot be undone, so it goes last.
 * </ol>
 *
 * The state is stored after every step and every compensation. A failed step undoes the completed
 * ones in reverse order; a failed compensation stops there and leaves the saga FAILED. Repeating the
 * Idempotency-Key with the same request returns the saga it started and runs no step again.
 */
public class RunProfessionalOnboardingSaga implements StartProfessionalOnboarding, GetSaga, RecoverInterruptedSagas {

    public static final String TYPE = "professional-onboarding";
    static final String ACCOUNT_ID = "accountId";
    static final String PROFESSIONAL_ID = "professionalId";
    /** The failure detail of a saga the recovery found stopped (WF-006). */
    static final String INTERRUPTED = "interrupted";

    private final IdentityAccounts identity;
    private final ProfessionalProfiles profiles;
    private final SagaStore store;
    private final IdGenerator ids;
    private final Clock clock;
    private final List<Step> steps;

    public RunProfessionalOnboardingSaga(IdentityAccounts identity, ProfessionalProfiles profiles, SagaStore store,
                                         IdGenerator ids, Clock clock) {
        this.identity = identity;
        this.profiles = profiles;
        this.store = store;
        this.ids = ids;
        this.clock = clock;
        this.steps = steps();
    }

    @Override
    public Started start(ProfessionalOnboardingCommand command, String idempotencyKey) {
        String requestHash = requestHash(command);
        Optional<SagaInstance> previous = store.findByIdempotencyKey(idempotencyKey);
        if (previous.isPresent()) {
            return replay(previous.get(), requestHash);
        }
        SagaInstance saga = SagaInstance.start(ids.newId(), TYPE, idempotencyKey, requestHash, clock.instant());
        try {
            store.insert(saga);
        } catch (DuplicateSagaException twin) {
            // A concurrent request with the same key stored its saga after the lookup: this is its retry.
            return replay(store.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> twin), requestHash);
        }
        run(saga, command);
        return new Started(saga, true);
    }

    @Override
    public SagaInstance get(UUID id) {
        return store.findById(id).orElseThrow(() -> new UnknownSagaException(id));
    }

    @Override
    public List<SagaInstance> recover(Duration unchangedFor, int limit) {
        List<SagaInstance> recovered = new ArrayList<>();
        for (SagaInstance saga : store.findRunningNotUpdatedSince(clock.instant().minus(unchangedFor), limit)) {
            recover(saga);
            recovered.add(saga);
        }
        return recovered;
    }

    private static Started replay(SagaInstance previous, String requestHash) {
        if (!previous.requestHash().equals(requestHash)) {
            throw new IdempotencyKeyReusedException();
        }
        return new Started(previous, false);
    }

    /**
     * Each step declares its compensation next to it (Anexo E, rule 1). Only the actions need the
     * request; the compensations read what the saga stored, so a recovery can run them without it.
     */
    private List<Step> steps() {
        return List.of(
                new Step("create-account",
                        (saga, command) -> saga.put(ACCOUNT_ID, identity.createInactiveProfessionalAccount(
                                new NewProfessionalAccount(command.fullName(), command.email(),
                                        command.identityDocument(), command.password()),
                                stepKey(saga, "create-account")).toString()),
                        saga -> identity.deactivateAccount(uuid(saga, ACCOUNT_ID))),
                new Step("create-profile",
                        (saga, command) -> saga.put(PROFESSIONAL_ID, profiles.createProfile(uuid(saga, ACCOUNT_ID),
                                command.licenseNumber(), command.yearsExperience(),
                                stepKey(saga, "create-profile")).toString()),
                        saga -> profiles.deactivateProfile(uuid(saga, PROFESSIONAL_ID))),
                Step.last("activate-account",
                        (saga, command) -> identity.activateAccount(uuid(saga, ACCOUNT_ID),
                                stepKey(saga, "activate-account"))));
    }

    private void run(SagaInstance saga, ProfessionalOnboardingCommand command) {
        for (Step step : steps) {
            try {
                step.action().accept(saga, command);
            } catch (RuntimeException failure) {
                saga.stepFailed(step.name(), detail(failure), clock.instant());
                store.update(saga);
                compensate(saga);
                return;
            }
            saga.stepCompleted(step.name(), clock.instant());
            store.update(saga);
        }
        saga.complete(clock.instant());
        store.update(saga);
    }

    /**
     * WF-006: a stopped saga is never resumed, because the password it needs is not stored. If every
     * step was done, only its final state was lost; otherwise it fails at its next step and is undone.
     */
    private void recover(SagaInstance saga) {
        if (saga.isCompensating()) {
            compensate(saga);
            return;
        }
        int done = saga.completedSteps().size();
        if (done == steps.size()) {
            saga.complete(clock.instant());
            store.update(saga);
            return;
        }
        saga.stepFailed(steps.get(done).name(), INTERRUPTED, clock.instant());
        store.update(saga);
        compensate(saga);
    }

    private void compensate(SagaInstance saga) {
        for (String name : saga.pendingCompensations()) {
            Step step = steps.stream().filter(candidate -> candidate.name().equals(name)).findFirst().orElseThrow();
            try {
                step.compensation().accept(saga);
            } catch (RuntimeException failure) {
                saga.compensationFailed(name, detail(failure), clock.instant());
                store.update(saga);
                return;
            }
            saga.stepCompensated(name, clock.instant());
            store.update(saga);
        }
        saga.compensated(clock.instant());
        store.update(saga);
    }

    /** Anexo E: a step retried with the same key never creates anything twice. */
    private static String stepKey(SagaInstance saga, String step) {
        return saga.id() + ":" + step;
    }

    private static UUID uuid(SagaInstance saga, String key) {
        return UUID.fromString(saga.get(key).orElseThrow(() -> new IllegalStateException("Missing " + key)));
    }

    private static String detail(RuntimeException failure) {
        return failure.getClass().getSimpleName() + ": " + failure.getMessage();
    }

    /** What identifies the request behind a key. The password is left out, so nothing derived from it is stored. */
    static String requestHash(ProfessionalOnboardingCommand command) {
        String canonical = String.join("\n",
                trim(command.fullName()),
                trim(command.email()).toLowerCase(Locale.ROOT),
                trim(command.identityDocument()),
                trim(command.licenseNumber()),
                String.valueOf(command.yearsExperience()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", impossible);
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private record Step(String name, BiConsumer<SagaInstance, ProfessionalOnboardingCommand> action,
                        Consumer<SagaInstance> compensation) {

        /** A step without compensation cannot be undone, so only the last step may lack one. */
        static Step last(String name, BiConsumer<SagaInstance, ProfessionalOnboardingCommand> action) {
            return new Step(name, action, saga -> {
                throw new IllegalStateException("The last step " + name + " is never undone");
            });
        }
    }
}
