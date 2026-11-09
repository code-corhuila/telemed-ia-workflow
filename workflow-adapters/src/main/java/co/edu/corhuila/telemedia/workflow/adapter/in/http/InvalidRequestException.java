package co.edu.corhuila.telemedia.workflow.adapter.in.http;

import java.util.List;

/** The request has invalid fields, headers or path variables: {@code 400} with their details. */
class InvalidRequestException extends RuntimeException {

    private final List<ApiError.FieldDetail> details;

    InvalidRequestException(List<ApiError.FieldDetail> details) {
        super("invalid request");
        this.details = List.copyOf(details);
    }

    List<ApiError.FieldDetail> details() {
        return details;
    }
}
