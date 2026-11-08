package co.edu.corhuila.telemedia.workflow.application.port.in;

/** What an administrator sends to onboard a professional. The password only travels to identity. */
public record ProfessionalOnboardingCommand(
        String fullName,
        String email,
        String identityDocument,
        String password,
        String licenseNumber,
        int yearsExperience) {

    @Override
    public String toString() {
        return "ProfessionalOnboardingCommand[email=" + email + ", licenseNumber=" + licenseNumber + "]";
    }
}
