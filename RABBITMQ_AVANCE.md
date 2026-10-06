# RabbitMQ en Pedidos360 — avance EP3 / EP4

_Rama de trabajo: `feature/rabbitmq` (repo `ms-pedidos360-backend`). Estado al 2026-10-06._

## 1. Qué piden las evaluaciones

**EP3 — Encargo (código, 12%, en parejas, entrega por GitHub).** Incorporar RabbitMQ con colas,
exchanges y DLQs para tareas asíncronas, sin afectar la lógica existente. Rúbrica (8 indicadores):

| Indicador | Peso |
|---|---|
| Nombres de colas/exchanges/bindings centralizados (application.yml o una clase `@Configuration`) | 12% |
| Beans `Queue`/`Exchange`/`Binding` por caso de uso | 13% |
| No mezclar lógica de negocio con configuración de mensajería | 10% |
| Consumidores con `@RabbitListener` agrupados por dominio | 15% |
| ACK y manejo explícito de errores (reintento / NACK / DLQ) | 20% |
| Microservicio administrador con endpoints REST (`POST /queues`, `DELETE /queues/{name}`...) | 13% |
| Lógica encapsulada en un servicio (`RabbitAdminService`) | 10% |
| Validación de entrada (no crear colas con nombre vacío o config inválida) | 7% |

Requisitos generales del encargo: backend compila, sigue buenas prácticas y responde a pruebas
básicas; filtros JWT en el backend; `.gitignore` correcto; mensajes no entregados van a DLQ y se
registran en logs; entrega = enlaces de GitHub en AVA + copia por correo al docente.

**EP4 — Presentación (18%, 5 a 10 min).** Todo lo de EP2 (API Manager, CORS, JWT, IDaaS, OIDC+PKCE,
despliegue) **más**:

- Dos nodos RabbitMQ configurados e integrados en un **clúster**.
- **Tres colas** con sus respectivas **DLQ** funcionando.
- Exchanges de tipo **direct y topic** correctamente configurados.
- Mostrar en la nube **docker-compose levantando RabbitMQ**.

## 2. Qué ya existía antes de esta rama

Topología en `orders` (3 colas + 3 DLQ, exchanges `cmd.direct`, `cmd.topic`, DLX `cmd.dead.dlx`),
productor `CommandPublisher`, un consumidor en `notify` con ACK manual. Faltaba el admin, el broker
en docker-compose, el clúster y varias mejoras de rúbrica.

## 3. Hecho en la rama `feature/rabbitmq` (Fase A, EP3)

Commits: `f38ab0c` (topología, consumidores, errores) y `f5532b4` (admin + BFF). Todo el reactor
Maven compila y pasa **33 pruebas unitarias** (`mvn clean package`).

| Tema | Qué se hizo | Dónde |
|---|---|---|
| Nombres centralizados | Bloque `pedidos360.messaging.rabbit` (exchanges, colas, routing keys, prefijo topic) en el yml de `orders` y `notify`; `@ConfigurationProperties`; los `@RabbitListener` usan `${...}` | `*/config/RabbitMessagingProperties.java`, `application.yml` |
| Topología | Beans por caso de uso (email, kitchen, invoice) con su DLQ y bindings direct + topic; se declara en `orders` y `notify` (idempotente) | `*/config/RabbitTopologyConfig.java` |
| Topic en uso real | El email se publica por topic con clave `cmd.email.<estado>`; kitchen/invoice por direct | `orders/.../CommandPublisher.java` |
| Desacople | El publicador captura errores del broker (log) para no romper el cambio de estado del pedido | `CommandPublisher` |
| Consumidores por dominio | `EmailCommandConsumer`, `KitchenCommandConsumer`, `InvoiceCommandConsumer` | `notify/.../messaging/{email,kitchen,invoice}` |
| ACK y errores | `CommandProcessor`: inválido → DLQ; duplicado → ACK; OK → ACK; transitorio → 1 reintento y luego DLQ; no recuperable → DLQ; todo con log | `notify/.../messaging/support` |
| Admin de RabbitMQ | Módulo nuevo `ms-pedidos360-rabbit-admin` (puerto 8086, solo Admin, JWT) | `ms-pedidos360-rabbit-admin/` |
| Validación | Bean Validation en DTOs, patrón de nombres, `amq.*` reservado, 404/409, errores 400 con detalle | `dto/AdminDtos.java`, `ApiExceptionHandler` |
| Integración BFF | Proxy `/api/rabbit/**` (solo Admin) y propagación del status/cuerpo de errores 4xx del downstream | `ms-pedidos360-bff` |
| Gancho de demo | Con `PEDIDOS360_SIMULATE_FAILURES=true`, un payload con `failMode: "transient"` o `"poison"` fuerza el error para mostrar la DLQ | `CommandProcessor` |

### Errores encontrados y corregidos
- **Faltaba `-parameters` al compilar** (el `pom.xml` raíz no usa el parent de Spring Boot). Spring 6.1 no
  resolvía `@PathVariable`/`@RequestParam` sin nombre; afectaba a `PATCH /api/orders/{id}/status`, el
  endpoint que dispara los mensajes. Corregido en `pom.xml`. **Hay que desplegarlo** (rebuild de las imágenes).
- El consumidor viejo marcaba el `eventId` como procesado antes de procesar → el reintento se descartaba.
- `notify` generaba un jar no ejecutable (faltaba el goal `repackage`, igual que pasó con `audit`).
- Los Dockerfiles copian el `pom.xml` de **todos** los módulos: al agregar un módulo hay que añadir la
  línea `COPY` en cada Dockerfile (ya hecho para `ms-pedidos360-rabbit-admin`).

### API del administrador (vía BFF y API Gateway, con token de Admin)
```
POST   /api/rabbit/queues          {"name":"q.demo","withDeadLetter":true,"deadLetterExchange":"cmd.dead.dlx"}
GET    /api/rabbit/queues          GET /api/rabbit/queues/{name}      DELETE /api/rabbit/queues/{name}
POST   /api/rabbit/exchanges       {"name":"ex.demo","type":"TOPIC"}  GET ...  DELETE /api/rabbit/exchanges/{name}
POST   /api/rabbit/bindings        {"queue":"q.demo","exchange":"ex.demo","routingKey":"demo.#"}
DELETE /api/rabbit/bindings?queue=&exchange=&routingKey=              GET /api/rabbit/bindings
POST   /api/rabbit/messages        {"exchange":"cmd.direct","routingKey":"email.send","payload":{...}}
```
Los listados usan la API HTTP de gestión de RabbitMQ (puerto 15672); las altas/bajas usan AMQP.

## 4. Pendiente

### Fase B — EP4 (nube) — **no empezada**
1. `infra/docker-compose.yml`: agregar **2 nodos RabbitMQ** (`rabbitmq:3.12-management`, mismo
   `RABBITMQ_ERLANG_COOKIE`, hostnames `rabbit1`/`rabbit2`, el 2.º se une al 1.º con
   `rabbitmqctl join_cluster`, o peer discovery `classic_config`), con política de réplica (`ha-all`).
2. Agregar al compose los servicios `notify` (8083) y `rabbit-admin` (8086) y las variables:
   - `SPRING_RABBITMQ_ADDRESSES=rabbit1:5672,rabbit2:5672` en orders, notify y rabbit-admin
   - `RABBITMQ_MANAGEMENT_URL=http://rabbit1:15672` en rabbit-admin
   - `RABBIT_ADMIN_SERVICE_URL=http://rabbit-admin:8086` en el BFF
   - `PEDIDOS360_SIMULATE_FAILURES=true` en notify (solo para la demo)
3. Memoria del EC2 (`t3.medium`, ~3.7 GB): ahora serían 5 JVM + 2 RabbitMQ + nginx. Probablemente
   cabe; si no, limitar con `JAVA_TOOL_OPTIONS=-Xmx256m`.
4. Security Group del EC2: abrir 15672 (UI de gestión) para la demo, o usar túnel SSH.
5. **Probar contra un broker real** (hasta ahora solo hay pruebas unitarias): flujo completo
   cambiar estado de un pedido → mensajes en las 3 colas → consumo; mensaje inválido → DLQ;
   `rabbitmqctl cluster_status`; revisar `exchangeDeclarePassive` y los listados del admin.
6. Desplegar la rama en el EC2 (git pull + rebuild) y verificar que `PATCH /api/orders/{id}/status` ya funciona.
7. Agregar a la guía de presentación (`Guion_Presentacion_EP2.md` / `.docx`) los bloques de RabbitMQ:
   clúster de 2 nodos, 3 colas + DLQ, exchanges direct/topic, docker-compose en la nube.

### Cierre de EP3
- Merge de `feature/rabbitmq` a `main` cuando esté probada; el compañero (EP3 es en parejas) debería revisarla.
- Entrega: enlaces de los repos de GitHub en AVA + copia al correo del docente (backend y frontend).
- Frontend: EP3 pide que esté completo y sin errores de compilación; no hay cambios de RabbitMQ en el
  front (opcional: pantalla de administración de colas para Admin).

### Deuda / buenas prácticas
- `orders/application.yml` (perfil `cloud`) trae una contraseña de base de datos por defecto escrita en
  el repo; conviene dejarla solo como variable de entorno.
- Idempotencia de `notify` en memoria (se pierde al reiniciar); suficiente para el MVP.

## 5. Notas para retomar
- Repos: `https://github.com/franciscomonsalve/ms-pedidos360-backend` y `.../frontend-pedidos360-`.
  Copias locales: `C:\Users\Gene\Desktop\PEDIDOS360\pedidos360-backend` y `...\frontend-pedidos360`.
- Compilar y probar: `mvn clean package` (desde la raíz del backend).
- EC2 (AWS Academy): sin Elastic IP, la IP cambia al parar/prender; el checklist de qué actualizar está en
  `Despliegue_AWS_Pedidos360_EP2.md`. Última IP usada: `54.163.21.47` (puede haber cambiado).
- Preferencia del usuario: commits **sin** línea de coautoría de IA.
- La terminal del usuario es PowerShell 5.1: no acepta `&&`.
