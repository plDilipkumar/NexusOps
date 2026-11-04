FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -B verify
FROM eclipse-temurin:21-jre
WORKDIR /app
# Run the API as an unprivileged fixed UID. The process only needs to read the JAR;
# application logs go to stdout/stderr and temporary files use the container's /tmp.
COPY --from=build --chown=10001:10001 /app/target/neurosql-api-0.1.0.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
