package co.edu.corhuila.telemedia.workflow.application.port.in;

import co.edu.corhuila.telemedia.workflow.domain.saga.SagaInstance;

/**
 * Runs the professional onboarding saga to its end: COMPLETED, COMPENSATED or FAILED.
 *
 * <p>Anexo E: repeating the {@code Idempotency-Key} returns the same saga and runs no step again.
 */
public interface StartProfessionalOnboarding {

    /**
     * @throws IdempotencyKeyReusedException when the key already started a saga for another request
     */
    Started start(ProfessionalOnboardingCommand command, String idempotencyKey);

    /** The saga, and whether this call created it ({@code 201}) or found it from a retry ({@code 200}). */
    record Started(SagaInstance saga, boolean created) {
    }
}
