package co.edu.corhuila.telemedia.workflow.application.usecase;

import co.edu.corhuila.telemedia.workflow.application.port.in.ProfessionalOnboardingCommand;
import co.edu.corhuila.telemedia.workflow.application.port.in.StartProfessionalOnboarding;
import co.edu.corhuila.telemedia.workflow.application.port.out.IdGenerator;
import co.edu.corhuila.telemedia.workflow.application.port.out.IdentityAccounts;
import co.edu.corhuila.telemedia.workflow.application.port.out.NewProfessionalAccount;
import co.edu.corhuila.telemedia.workflow.application.port.out.ProfessionalProfiles;
import co.edu.corhuila.telemedia.workflow.application.port.out.SagaStore;
import co.edu.corhuila.telemedia.workflow.domain.saga.SagaInstance;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
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
 * ones in reverse order; a failed compensation stops there and leaves the saga FAILED.
 */
public class RunProfessionalOnboardingSaga implements StartProfessionalOnboarding {

    public static final String TYPE = "professional-onboarding";
    static final String ACCOUNT_ID = "accountId";
    static final String PROFESSIONAL_ID = "professionalId";

    private final IdentityAccounts identity;
    private final ProfessionalProfiles profiles;
    private final SagaStore store;
    private final IdGenerator ids;
    private final Clock clock;

    public RunProfessionalOnboardingSaga(IdentityAccounts identity, ProfessionalProfiles profiles, SagaStore store,
                                         IdGenerator ids, Clock clock) {
        this.identity = identity;
        this.profiles = profiles;
        this.store = store;
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public SagaInstance start(ProfessionalOnboardingCommand command, String idempotencyKey) {
        SagaInstance saga = SagaInstance.start(ids.newId(), TYPE, idempotencyKey, requestHash(command),
                clock.instant());
        store.insert(saga);
        run(saga, steps(command));
        return saga;
    }

    /** Each step declares its compensation next to it (Anexo E, rule 1). */
    private List<Step> steps(ProfessionalOnboardingCommand command) {
        return List.of(
                new Step("create-account",
                        saga -> saga.put(ACCOUNT_ID, identity.createInactiveProfessionalAccount(
                                new NewProfessionalAccount(command.fullName(), command.email(),
                                        command.identityDocument(), command.password()),
                                stepKey(saga, "create-account")).toString()),
                        saga -> identity.deactivateAccount(uuid(saga, ACCOUNT_ID))),
                new Step("create-profile",
                        saga -> saga.put(PROFESSIONAL_ID, profiles.createProfile(uuid(saga, ACCOUNT_ID),
                                command.licenseNumber(), command.yearsExperience(),
                                stepKey(saga, "create-profile")).toString()),
                        saga -> profiles.deactivateProfile(uuid(saga, PROFESSIONAL_ID))),
                Step.last("activate-account",
                        saga -> identity.activateAccount(uuid(saga, ACCOUNT_ID), stepKey(saga, "activate-account"))));
    }

    private void run(SagaInstance saga, List<Step> steps) {
        for (Step step : steps) {
            try {
                step.action().accept(saga);
            } catch (RuntimeException failure) {
                saga.stepFailed(step.name(), detail(failure), clock.instant());
                store.update(saga);
                compensate(saga, steps);
                return;
            }
            saga.stepCompleted(step.name(), clock.instant());
            store.update(saga);
        }
        saga.complete(clock.instant());
        store.update(saga);
    }

    private void compensate(SagaInstance saga, List<Step> steps) {
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

    private record Step(String name, Consumer<SagaInstance> action, Consumer<SagaInstance> compensation) {

        /** A step without compensation cannot be undone, so only the last step may lack one. */
        static Step last(String name, Consumer<SagaInstance> action) {
            return new Step(name, action, saga -> {
                throw new IllegalStateException("The last step " + name + " is never undone");
            });
        }
    }
}
