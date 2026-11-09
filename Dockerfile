FROM eclipse-temurin:25-jre@sha256:dbb43d81099eec91e0800226ed2296d03146ee7857165b95112372ad0d67e87f
WORKDIR /app
RUN groupadd --gid 10001 spring && useradd --uid 10001 --gid spring --no-create-home spring && mkdir /secrets
COPY target/chatbot-service-*.jar /app/app.jar
ENV SERVER_PORT=8084 JAVA_TOOL_OPTIONS="-XX:+UseSerialGC -Xms32m -Xmx224m -XX:MaxMetaspaceSize=96m -XX:ReservedCodeCacheSize=32m -Xss256k"
USER 10001:10001
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
