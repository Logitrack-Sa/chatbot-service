# chatbot-service

Base stateless del chatbot de LogiTrack (#103): Java 21, Spring Boot 3.5.16,
Spring Cloud 2025.0.3, Spring AI 1.1.8, OpenFeign, Config Server, Eureka,
OAuth2 Resource Server y Actuator Prometheus. No incluye JDBC, JPA ni MySQL.
Las herramientas `@Tool`, el prompt logístico y el endpoint SSE de producción
corresponden a la issue #104.

## Configuración y ejecución

Config Server es obligatorio (`CONFIG_SERVER_URL`, por defecto
`http://localhost:8888`) y debe publicar `chatbot-service.properties` desde
`Logitrack-Sa/config-repo`. El puerto por defecto es 8084 (`SERVER_PORT`).
El registro en Eureka usa `CHATBOT-SERVICE`; configurar `EUREKA_SERVER_URL`
(por defecto `http://localhost:8065/eureka/`).

La clave pública RSA X.509 se lee de `JWT_PUBLIC_KEY_LOCATION`
(por defecto `file:./certs/public.pem`). Se valida firma RS256, expiración,
issuer (`JWT_ISSUER`, por defecto `https://security-service`), kid (`JWT_KEY_ID`,
por defecto `logitrack-main`), sujeto UUID y audiencia `chatbot-service` sin
consultar al emisor. No se monta la clave privada ni se emiten tokens.

Para Spring AI configurar `AI_API_KEY` y `AI_MODEL` en el entorno. No hay claves,
modelo gratuito ni credenciales administrativas incorporadas. Por defecto usa
el cliente OpenAI con `AI_BASE_URL=https://api.openai.com` y
`AI_COMPLETIONS_PATH=/v1/chat/completions`. Un proveedor compatible puede cambiar
ambos valores; la clave de IA es independiente del JWT y no se envía a los servicios
de dominio. El arranque no realiza una conversación ni consume tokens del modelo.

`./mvnw spring-boot:run` inicia el servicio. `GET /actuator/health` y
`GET /actuator/prometheus` son públicos para la red de monitorización; los demás
endpoints de Actuator están cerrados. `/api/v1/chat/**` exige un JWT válido y toda
ruta no declarada se deniega. La API de chat se implementará en #104.

## Propagación del usuario hacia OpenFeign

`ShipmentFeignClient` expone únicamente la consulta GET por tracking y
`CourierFeignClient` el listado GET paginado por estado. Los destinos se resuelven
por Eureka; `SHIPMENT_SERVICE_URL` y `COURIER_SERVICE_URL` permiten sobrescribirlos
para pruebas. Los contratos DTO corresponden a los JSON actuales de esos servicios.

`FeignClientSecurityConfig` se aplica solo a esos dos clientes. Sustituye cualquier
Authorization previo por el JWT autenticado vigente, rechaza llamadas sin identidad,
no sigue redirecciones y deshabilita el log de Feign para no registrar tokens.
No obtiene una credencial ADMIN ni concede permisos adicionales: los servicios de
destino siguen imponiendo sus roles, audiencias y reglas de pertenencia.

`SecurityContextPropagationFilter` captura el JWT después del filtro Bearer y lo
restaura en `finally`. Su contexto usa un ThreadLocal registrado con Micrometer;
Spring MVC captura este valor al devolver un `Flux`, y Reactor restaura y limpia
el valor en cada cambio de hilo. Un `TaskDecorator` captura la identidad al enviar
trabajo al executor compartido de `@Async` y MVC, con pool y cola acotados.

Se evita deliberadamente `RequestContextHolder.setRequestAttributes(attributes, true)`
y `MODE_INHERITABLETHREADLOCAL`: no propagan correctamente a workers preexistentes
y pueden retener peticiones o identidad entre usuarios. La misma intención de la
issue se implementa con snapshots por petición y limpieza, sin transportar el objeto
ServletRequest. El fallback solo acepta un `JwtAuthenticationToken` autenticado del
SecurityContext, nunca la cabecera cruda de una petición.

Para un flujo fuera del manejo automático de Spring MVC, capturar **antes** de
salir de la petición autenticada:

```java
return JwtRelayContext.propagate(chatClient.prompt().user(prompt).stream().content());
```

El token no se añade al prompt ni al `ToolContext` del modelo. Feign bloquea:
las herramientas futuras deben ejecutar esas consultas en `boundedElastic` o en
el executor configurado, no en el event loop de red. Executors creados manualmente
o `CompletableFuture` en el common pool no reciben contexto automáticamente.

Referencias del diseño: [Spring MVC 6.2 — Context Propagation](https://docs.spring.io/spring-framework/reference/6.2/web/webmvc/mvc-ann-async.html#webmvc-ann-async-context-propagation)
y [Reactor — Context Propagation](https://projectreactor.io/docs/core/release/reference/advanced-contextPropagation.html).

## Validación y despliegue

Para equipos limitados, compilar sin ejecutar tests ni iniciar servicios:

```bash
JAVA_TOOL_OPTIONS="-Xmx256m -XX:ActiveProcessorCount=1" ./mvnw -DskipTests test-compile
```

GitHub Actions ejecuta `./mvnw verify`. Las pruebas verifican tokens ausentes,
expirados, issuer/audiencia incorrectos, propagación en `Flux` y `@Async`, workers
reutilizados, dos usuarios concurrentes, limpieza tras errores, clientes Feign,
registro Eureka y Prometheus. Los endpoints probe existen **solo en tests**.
No se llama a OpenAI/Gemini ni se requiere MySQL o Docker para las pruebas Java.
El smoke posterior en CI ejecuta la imagen con 350 MB, Config Server y Eureka
simulados, PEM efímero, métricas, rechazo de peticiones sin JWT y UID 10001.

El `Dockerfile` requiere el JAR de `./mvnw package` y fija el JRE 21 por digest.
La integración del stack está en `Logitrack-Sa/deploy/docker/Dockerfile.chatbot`
y `docker-compose.yml`, con heap de 224 MB, límite de 350M y solo public.pem montado.
No ejecutar el stack completo en equipos con pocos recursos para validar este cambio.
