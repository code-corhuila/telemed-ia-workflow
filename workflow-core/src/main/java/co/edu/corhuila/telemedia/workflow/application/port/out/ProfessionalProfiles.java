package co.edu.corhuila.telemedia.workflow.application.port.out;

import java.util.UUID;

/**
 * The professional-management participant. Any failure is thrown as a runtime exception, after
 * the adapter's bounded retries.
 */
public interface ProfessionalProfiles {

    /** Creates the profile of an existing account and returns the professional id. */
    UUID createProfile(UUID accountId, String licenseNumber, int yearsExperience, String idempotencyKey);

    /** Compensation of the profile creation. Idempotent: deactivating twice is not an error. */
    void deactivateProfile(UUID professionalId);
}
