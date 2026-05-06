FROM eclipse-temurin:21-jdk AS builder
RUN apt-get update && apt-get install -y curl && \
    curl -fL https://github.com/sbt/sbt/releases/download/v1.11.5/sbt-1.11.5.tgz | tar xzf - -C /usr/local && \
    ln -s /usr/local/sbt/bin/sbt /usr/local/bin/sbt && \
    rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY project ./project
COPY build.sbt .
RUN sbt update
COPY src ./src
RUN sbt assembly

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /app/target/scala-3.4.2/dtmetrics-demo-assembly-0.1.0.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
