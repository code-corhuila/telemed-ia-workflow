package co.edu.corhuila.telemedia.workflow.application.port.out;

/** Another saga with the same Idempotency-Key was stored first. */
public class DuplicateSagaException extends RuntimeException {

    public DuplicateSagaException(String idempotencyKey) {
        super("A saga already exists for Idempotency-Key " + idempotencyKey);
    }
}
