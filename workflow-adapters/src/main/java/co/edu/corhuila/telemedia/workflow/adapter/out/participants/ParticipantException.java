package co.edu.corhuila.telemedia.workflow.adapter.out.participants;

/**
 * A participant call that did not succeed. The message is internal (it ends in the saga state and
 * the log, never in a response) and never carries a request body, a token or a secret.
 */
public class ParticipantException extends RuntimeException {

    public ParticipantException(String message) {
        super(message);
    }

    public ParticipantException(String message, Throwable cause) {
        super(message, cause);
    }
}
