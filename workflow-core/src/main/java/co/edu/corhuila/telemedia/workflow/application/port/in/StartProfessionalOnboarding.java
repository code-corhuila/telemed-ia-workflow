package co.edu.corhuila.telemedia.workflow.application.port.in;

import co.edu.corhuila.telemedia.workflow.domain.saga.SagaInstance;

/** Runs the professional onboarding saga to its end: COMPLETED, COMPENSATED or FAILED. */
public interface StartProfessionalOnboarding {

    SagaInstance start(ProfessionalOnboardingCommand command, String idempotencyKey);
}
