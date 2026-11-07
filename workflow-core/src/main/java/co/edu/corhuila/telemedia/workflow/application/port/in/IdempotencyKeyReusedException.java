package co.edu.corhuila.telemedia.workflow.application.port.in;

/** The Idempotency-Key already started a saga for a different request. */
public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException() {
        super("The Idempotency-Key was already used for a different request");
    }
}
