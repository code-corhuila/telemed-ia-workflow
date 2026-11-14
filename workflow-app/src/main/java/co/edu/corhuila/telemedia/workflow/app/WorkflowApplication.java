package co.edu.corhuila.telemedia.workflow.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Composition root of the workflow. The adapters live outside this package, so the scan starts one level up. */
@SpringBootApplication(scanBasePackages = "co.edu.corhuila.telemedia.workflow")
public class WorkflowApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkflowApplication.class, args);
    }
}
