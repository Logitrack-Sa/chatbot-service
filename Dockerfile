FROM eclipse-temurin:21-jre@sha256:0b99b3376fbb50dbf9c0af8535fdd5b8eb0d3fdfc8b1e4967f383b6919f6901a
WORKDIR /app
RUN groupadd --gid 10001 spring && useradd --uid 10001 --gid spring --no-create-home spring && mkdir /secrets
COPY target/chatbot-service-*.jar /app/app.jar
ENV SERVER_PORT=8084 JAVA_TOOL_OPTIONS="-XX:+UseSerialGC -Xms32m -Xmx224m -XX:MaxMetaspaceSize=96m -XX:ReservedCodeCacheSize=32m -Xss256k"
USER 10001:10001
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
