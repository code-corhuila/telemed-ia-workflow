package co.edu.corhuila.telemedia.workflow.domain.saga;

import java.util.UUID;

public class UnknownSagaException extends RuntimeException {

    public UnknownSagaException(UUID id) {
        super("Unknown saga " + id);
    }
}
