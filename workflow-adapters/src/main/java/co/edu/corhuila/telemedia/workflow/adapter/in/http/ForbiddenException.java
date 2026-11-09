package co.edu.corhuila.telemedia.workflow.adapter.in.http;

/** The caller is authenticated but its role may not use the route: {@code 403}. */
class ForbiddenException extends RuntimeException {

    ForbiddenException() {
        super("forbidden");
    }
}
