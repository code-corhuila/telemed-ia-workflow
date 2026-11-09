FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /app

COPY pom.xml .
COPY workflow-core ./workflow-core
COPY workflow-adapters ./workflow-adapters
COPY workflow-app ./workflow-app

# JdbcSagaStoreTest needs Docker (Testcontainers) and the db/ changelog; `mvn verify` runs it
# locally and in CI, the image build only compiles and runs the other tests.
RUN mvn -B clean package -Dtest='!JdbcSagaStoreTest' -Dsurefire.failIfNoSpecifiedTests=false

FROM eclipse-temurin:21-jre

WORKDIR /app

RUN groupadd --system telemed \
    && useradd --system \
        --gid telemed \
        --home-dir /app \
        --shell /usr/sbin/nologin \
        telemed

COPY --from=build \
    --chown=telemed:telemed \
    /app/workflow-app/target/workflow-app-*.jar \
    app.jar

USER telemed

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
