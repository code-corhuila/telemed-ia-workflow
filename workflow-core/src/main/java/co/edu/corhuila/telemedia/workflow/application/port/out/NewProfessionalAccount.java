package co.edu.corhuila.telemedia.workflow.application.port.out;

/** The identity account of a new professional. */
public record NewProfessionalAccount(String fullName, String email, String identityDocument, String password) {

    @Override
    public String toString() {
        return "NewProfessionalAccount[email=" + email + "]";
    }
}
