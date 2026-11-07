package co.edu.corhuila.telemedia.workflow.adapter.in.http;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error answers the shared envelope with the request's {@code traceId}. Spring MVC errors
 * (unknown route, wrong method, unreadable body...) keep their status with a neutral message; any
 * other exception is a {@code 500} whose detail goes to the log only.
 */
@RestControllerAdvice
public class ErrorHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ErrorHandler.class);

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception failure, HttpServletRequest request) {
        LOG.error("unexpected error on {} {}", request.getMethod(), request.getRequestURI(), failure);
        return envelope(500, ApiError.forStatus(500, CorrelationFilter.current(request)));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception failure, Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        String traceId = CorrelationFilter.current(
                ((NativeWebRequest) request).getNativeRequest(HttpServletRequest.class));
        HttpHeaders copy = new HttpHeaders();
        copy.putAll(headers);
        copy.setContentType(MediaType.APPLICATION_JSON);
        return ResponseEntity.status(status).headers(copy).body(ApiError.forStatus(status.value(), traceId));
    }

    static ResponseEntity<ApiError> envelope(int status, ApiError error) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(error);
    }
}
