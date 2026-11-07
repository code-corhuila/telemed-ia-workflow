package co.edu.corhuila.telemedia.workflow.app;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class WorkflowHttpTest {

    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Autowired
    private MockMvc mvc;

    @Test
    void healthAnswersUpWithoutAToken() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"status\":\"UP\"}", true));
    }

    @Test
    void returnsTheCorrelationIdItReceived() throws Exception {
        mvc.perform(get("/health").header("X-Correlation-Id", "req-42:abc"))
                .andExpect(header().string("X-Correlation-Id", "req-42:abc"));
    }

    @Test
    void replacesAnUnsafeCorrelationIdWithANewOne() throws Exception {
        mvc.perform(get("/health").header("X-Correlation-Id", "bad id\r\ninjected"))
                .andExpect(header().string("X-Correlation-Id", matchesPattern(UUID_PATTERN)));
    }

    @Test
    void anUnknownRouteAnswersTheSharedEnvelopeWithTheTraceId() throws Exception {
        mvc.perform(get("/api/v1/nothing-here").header("X-Correlation-Id", "trace-404"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("the requested resource does not exist"))
                .andExpect(jsonPath("$.traceId").value("trace-404"))
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    void aWrongMethodAnswersTheSharedEnvelope() throws Exception {
        mvc.perform(put("/health"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.traceId").value(matchesPattern(UUID_PATTERN)));
    }
}
