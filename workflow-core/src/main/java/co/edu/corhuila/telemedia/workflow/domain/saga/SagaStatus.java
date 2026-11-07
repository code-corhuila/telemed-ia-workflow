package co.edu.corhuila.telemedia.workflow.domain.saga;

/** Anexo E: where a saga stands. Only {@link #RUNNING} can still change. */
public enum SagaStatus {
    /** Running its steps, or undoing them after a failure ({@link SagaInstance#isCompensating()}). */
    RUNNING,
    /** Every step succeeded. */
    COMPLETED,
    /** A step failed and every completed step was undone. */
    COMPENSATED,
    /** A compensation failed: automatic recovery is no longer safe and a person must decide. */
    FAILED
}
