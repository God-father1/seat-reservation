FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn
COPY mvnw pom.xml ./
# RUN ./mvnw dependency:go-offline
COPY src ./src
RUN ./mvnw package -DskipTests
RUN java -Djarmode=tools -jar target/seat-reserve-service-0.0.1-SNAPSHOT.jar extract --layers --destination application

FROM eclipse-temurin:25-jre AS aot
WORKDIR /app
COPY --from=build /app/application/dependencies/ ./
COPY --from=build /app/application/spring-boot-loader/ ./
COPY --from=build /app/application/snapshot-dependencies/ ./
COPY --from=build /app/application/application/ ./
RUN java -XX:AOTMode=record -XX:AOTConfiguration=app.aotconf -Dspring.context.exit=onRefresh -Dspring.flyway.enabled=false -jar seat-reserve-service-0.0.1-SNAPSHOT.jar || echo "AOT config failed"
RUN java -XX:AOTMode=create -XX:AOTConfiguration=app.aotconf -XX:AOTCache=app.aot -jar seat-reserve-service-0.0.1-SNAPSHOT.jar || touch app.aot

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
RUN useradd -u 10001 -m appuser
COPY --from=build /app/application/dependencies/ ./
COPY --from=build /app/application/spring-boot-loader/ ./
COPY --from=build /app/application/snapshot-dependencies/ ./
COPY --from=build /app/application/application/ ./
COPY --from=aot /app/app.aot ./app.aot
COPY docker-entrypoint.sh ./
RUN chmod +x docker-entrypoint.sh && chown -R appuser:appuser /app
USER 10001
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["./docker-entrypoint.sh"]
