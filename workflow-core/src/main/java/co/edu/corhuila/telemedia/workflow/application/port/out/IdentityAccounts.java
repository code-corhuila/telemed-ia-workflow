package co.edu.corhuila.telemedia.workflow.application.port.out;

import java.util.UUID;

/**
 * The identity-and-access participant. Any failure is thrown as a runtime exception, after the
 * adapter's bounded retries.
 */
public interface IdentityAccounts {

    /** Creates the account inactive, so nobody can sign in before the whole saga succeeds. */
    UUID createInactiveProfessionalAccount(NewProfessionalAccount account, String idempotencyKey);

    void activateAccount(UUID accountId, String idempotencyKey);

    /** Compensation of the account creation. Idempotent: deactivating twice is not an error. */
    void deactivateAccount(UUID accountId);
}
