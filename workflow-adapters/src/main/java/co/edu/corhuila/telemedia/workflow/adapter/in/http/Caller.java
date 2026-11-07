package co.edu.corhuila.telemedia.workflow.adapter.in.http;

import java.util.Objects;
import java.util.Set;

/** Who calls a request, as the verified token says. */
public record Caller(String subject, Set<String> roles) {

    public Caller {
        Objects.requireNonNull(subject, "subject");
        roles = Set.copyOf(roles);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }
}
