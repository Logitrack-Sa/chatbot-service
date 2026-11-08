# chatbot-service

Base stateless del chatbot de LogiTrack (#103): Java 21, Spring Boot 3.5.16,
Spring Cloud 2025.0.3, Spring AI 1.1.8, OpenFeign, Config Server, Eureka,
OAuth2 Resource Server y Actuator Prometheus. No incluye JDBC, JPA ni MySQL.
Incluye las herramientas `@Tool`, el prompt logístico y el endpoint SSE de la
issue #104, sin historial ni estado persistente.

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
ruta no declarada se deniega.

## Chat logístico

`GET /api/v1/chat/stream?prompt=...` devuelve `text/event-stream` con los
fragmentos producidos por el modelo. No guarda conversaciones ni comparte memoria
entre usuarios. Un prompt ausente o en blanco se rechaza con `400` antes de llamar
al modelo; un JWT ausente o inválido se rechaza con `401`. El JWT se captura antes
de abrir el `Flux` y se propaga a las consultas de dominio, nunca al proveedor de
IA.

```bash
export JWT="$(cat /ruta/al/token-del-usuario)"
curl -N -H "Authorization: Bearer $JWT" \
  "http://localhost:8084/api/v1/chat/stream?prompt=¿Dónde%20está%20el%20envío%20LT-123?"
```

El prompt logístico usa solo herramientas de lectura:

- `consultarEstadoEnvioPorTracking(trackingCode)`: `GET` al servicio de envíos.
- `obtenerRepartidoresDisponibles()`: `GET` al servicio de repartidores con la
  consulta fija `status=AVAILABLE`, página `0` y tamaño `10`.

Ambas traducen fallos de dominio a un resultado controlado (`NOT_FOUND`,
`ACCESS_DENIED`, `UNAVAILABLE` o `INVALID_INPUT`) sin exponer detalles internos.
No hay operaciones de escritura. `ACTIVO` no equivale a `AVAILABLE`: la herramienta
no traduce el estado, no sustituye la consulta y no aplica un fallback a
`ACTIVO`/`INACTIVO`. Si `courier-service` rechaza `AVAILABLE` con `400` por
incompatibilidad de contrato, la herramienta responde `UNAVAILABLE` con un mensaje
sanitizado y no reintenta con otro estado. No se debe modificar `courier-service`
desde este repositorio.

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

El token no se añade al prompt ni al `ToolContext` del modelo. Spring AI ejecuta
las herramientas en `boundedElastic`; sobre ese worker Reactor restaura el JWT y lo
limpia al completar, fallar o cancelar el stream. Feign rechaza las consultas sin
identidad. Executors creados manualmente o `CompletableFuture` en el common pool no
reciben contexto automáticamente.

Referencias del diseño: [Spring MVC 6.2 — Context Propagation](https://docs.spring.io/spring-framework/reference/6.2/web/webmvc/mvc-ann-async.html#webmvc-ann-async-context-propagation)
y [Reactor — Context Propagation](https://projectreactor.io/docs/core/release/reference/advanced-contextPropagation.html).

## Validación y despliegue

Para equipos limitados, compilar sin ejecutar tests ni iniciar servicios:

```bash
JAVA_TOOL_OPTIONS="-Xmx256m -XX:ActiveProcessorCount=1" ./mvnw -DskipTests test-compile
```

GitHub Actions ejecuta `./mvnw verify`. Las pruebas verifican tokens ausentes,
expirados, issuer/audiencia incorrectos, propagación en `Flux` y `@Async`, workers
reutilizados, dos usuarios concurrentes, limpieza tras error y cancelación,
clientes Feign, registro Eureka y Prometheus. La integración del chat usa un
proveedor OpenAI y servicios de dominio simulados localmente: comprueba SSE real,
JWT solo hacia dominio, ejecución de herramientas en `boundedElastic`, concurrencia,
consulta de repartidores con éxito y rechazo `400` de contrato, y proveedor no
disponible, sin llamar a OpenAI/Gemini ni requerir MySQL o Docker.
Los endpoints probe existen **solo en tests**.
El smoke posterior en CI ejecuta la imagen con 350 MB, Config Server y Eureka
simulados, PEM efímero, métricas, rechazo de peticiones sin JWT y UID 10001.

El `Dockerfile` requiere el JAR de `./mvnw package` y fija el JRE 21 por digest.
La integración del stack está en `Logitrack-Sa/deploy/docker/Dockerfile.chatbot`
y `docker-compose.yml`, con heap de 224 MB, límite de 350M y solo public.pem montado.
No ejecutar el stack completo en equipos con pocos recursos para validar este cambio.
