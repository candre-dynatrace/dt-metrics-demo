FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app
COPY project ./project
COPY build.sbt .
RUN sbt update
COPY src ./src
RUN sbt assembly

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /app/target/scala-3.4.2/dtmetrics-demo-assembly-1.0.jar app.jar

EXPOSE 9496
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
