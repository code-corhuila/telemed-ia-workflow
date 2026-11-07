package co.edu.corhuila.telemedia.workflow.application.port.out;

import java.util.UUID;

@FunctionalInterface
public interface IdGenerator {

    UUID newId();
}
