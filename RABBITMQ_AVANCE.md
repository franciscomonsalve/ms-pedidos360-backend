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

## 4. Hecho en la rama `feature/rabbitmq` (Fase B, EP4 — infraestructura, probada en local)

Todo en `infra/`. Levantado y verificado con Docker Desktop en local el 2026-10-08
(resultados en la sección 5.1). Falta el tramo que exige JWT real (`orders` y `rabbit-admin`)
y el despliegue en el EC2.

| Archivo | Qué hace |
|---|---|
| `infra/rabbitmq/rabbitmq.conf` | Config compartida por los 2 nodos: peer discovery `classic_config` con `rabbit@rabbit1` y `rabbit@rabbit2`, `cluster_partition_handling = autoheal`, `loopback_users.guest = false` (los servicios conectan desde otros contenedores) y tope `vm_memory_high_watermark.absolute = 256MB` |
| `infra/rabbitmq/ha-policy.json` | Política `ha-all`: `pattern ^q\.cmd\.`, `ha-mode: all`, `ha-sync-mode: automatic`, `apply-to: queues` → replica las 3 colas y sus 3 DLQ en ambos nodos |
| `infra/docker-compose.yml` | Servicios nuevos `rabbit1`, `rabbit2`, `rabbit-init`, `notify`, `rabbit-admin`; variables de clúster en `orders`/`notify`/`rabbit-admin`; `RABBIT_ADMIN_SERVICE_URL` en el BFF; `-Xmx` por servicio |
| `infra/verificar-cluster.sh` | Chequeo para la demo: `cluster_status`, nodos, política, colas + DLQ, exchanges direct/topic, bindings y réplicas sincronizadas |

Detalles del compose:

- **Nombres de nodo**: se fijan con `hostname: rabbit1` / `rabbit2` (RabbitMQ toma el hostname),
  más el mismo `RABBITMQ_ERLANG_COOKIE: pedidos360-cluster-cookie` en los dos.
- **Orden de arranque**: `rabbit2` espera a que `rabbit1` esté `service_healthy`
  (`rabbitmq-diagnostics check_running`). `classic_config` no tiene lock distribuido, así que
  arrancar los dos en simultáneo puede dejar dos clústers de un nodo.
- **Puertos**: `rabbit1` → 5672 / 15672; `rabbit2` → 5673 / 15673 (así se ven los dos nodos en la demo).
- **Volúmenes** `rabbit1-data` / `rabbit2-data` para que el clúster sobreviva a `docker compose down`.
- **`rabbit-init`**: contenedor de un solo uso (`curlimages/curl`) que aplica la política por la API
  HTTP de gestión y luego imprime los nodos del clúster. Se usa HTTP en vez de `rabbitmqctl` para no
  depender de la cookie de Erlang desde un contenedor ajeno al clúster.
- **Clientes**: `SPRING_RABBITMQ_ADDRESSES=rabbit1:5672,rabbit2:5672` en `orders`, `notify` y
  `rabbit-admin` (en Spring Boot, `addresses` tiene prioridad sobre `host`/`port`), de modo que si un
  nodo cae el cliente reconecta al otro. `RABBITMQ_MANAGEMENT_URL=http://rabbit1:15672` en el admin.
- **Memoria**: cada JVM con `JAVA_TOOL_OPTIONS=-Xmx256m/-Xmx192m -XX:MaxMetaspaceSize=128m` y cada
  nodo RabbitMQ con `vm_memory_high_watermark.absolute = 256MB`. A propósito **no** se usa `mem_limit`:
  un OOM-kill del kernel tumbaría el contenedor justo en la demo. Footprint esperado ≈ 2 GB.

### Ojo con esto
- **Tamaño del EC2**: la instancia se subió a `t3.medium` (2 vCPU / ~3.7 GB), suficiente para el
  footprint de ~2,3 GB del stack completo. `Despliegue_AWS_Pedidos360_EP2.md` sigue diciendo `t3.micro`
  (913 MB) en su sección 1.7: **ese dato está obsoleto**, hay que actualizarlo.
  Pendiente de confirmar en la instancia con `free -h`.
- **Disco del EC2**: con 5 imágenes Maven multi-stage los 8 GB por defecto del volumen raíz se llenan
  (`maven:3.9-eclipse-temurin-21` ~900 MB + `eclipse-temurin:21-jre` ~450 MB + `rabbitmq:3.12-management`
  ~250 MB + capas de build). Ampliar a **30 GB gp3** (lo cubre el free tier) y extender el filesystem
  con `growpart` + `xfs_growfs`.
- **El build es el pico de memoria, no el runtime**: `docker compose build` compila en paralelo y cada
  build de Maven es otra JVM de ~700 MB-1 GB. Serializar con `export COMPOSE_PARALLEL_LIMIT=1` antes
  de construir, o ir servicio por servicio.
- `infra/docker-compose.yml` ya traía el contexto de build del frontend en `../../../frontend-pedidos360`,
  que desde `infra/` apunta a `Desktop/`, no a `Desktop/PEDIDOS360/`. En el EC2 funciona por el layout
  de carpetas de allá; **en local hay que levantar solo los servicios que se necesiten**
  (`docker compose up -d rabbit1 rabbit2 rabbit-init orders notify rabbit-admin`).
- `rabbitmq:3.12` marca las colas espejadas clásicas (`ha-mode`) como **deprecadas** (en 4.x se
  eliminan a favor de quorum queues). Para la demo sirve y no requiere tocar el código Java; migrar a
  quorum queues implicaría declarar `x-queue-type: quorum` en `RabbitTopologyConfig`.

## 5. Pendiente

### Fase B — EP4 (nube) — probada en local con Docker; falta desplegar en el EC2
1. ~~2 nodos RabbitMQ en clúster con política de réplica~~ → hecho y **verificado** (sección 5.1).
2. ~~Servicios `notify` y `rabbit-admin` en el compose + variables de entorno~~ → hecho.
3. ~~Probar el clúster con Docker en local~~ → **hecho** (sección 5.1).
4. ~~Probar el flujo de mensajes contra un broker real (3 colas, DLQ, reintento)~~ → **hecho** con
   `notify` + clúster (sección 5.1). **Falta** el tramo `orders` → `notify` (`PATCH /api/orders/{id}/status`),
   que exige un JWT real: se prueba en el EC2 con un token de Admin.
5. ~~Tolerancia a fallos: apagar `rabbit1`~~ → **hecho** (sección 5.1).
6. **Probar `rabbit-admin`** (crear/eliminar colas, exchanges y bindings, `GET` de listados,
   `exchangeDeclarePassive`) contra el broker real; también exige JWT de Admin → hacerlo en el EC2.
7. Confirmar el tipo de instancia del EC2: en la sesión anterior se subió a **`t3.medium` (3.7 GB)**,
   suficiente para el stack completo (~2 GB); verificar con `free -h`. La IP pública cambió desde la
   última vez (la última conocida, `54.163.21.47`, ya no es confiable).
8. Desplegar la rama en el EC2 (ver 5.3) y comprobar que `PATCH /api/orders/{id}/status`
   funciona (el fix de `-parameters` aún no está desplegado).
   ~~El `docker-compose.yml` del EC2 tiene secretos editados a mano que choca con el nuevo~~ →
   resuelto: ahora todo sale de `infra/.env` (ver 5.3).
9. Security Group del EC2: abrir 15672 (y 15673 para mostrar el 2.º nodo) en la demo, o túnel SSH.
10. ~~Guía de presentación con los bloques de RabbitMQ~~ → hecho: **`Guion_Presentacion_EP4.md`**
    (archivo nuevo, no se tocó el de EP2). Cubre los 11 elementos obligatorios en 9:00 y mapea cada
    bloque al indicador de la pauta que alimenta. **Ojo con el PDF de EP4**: las "instrucciones
    específicas" listan 11 cosas a mostrar, pero la pauta con porcentajes evalúa los **mismos 8
    indicadores de código de EP3** — o sea, la nota se juega mostrando y explicando el código, no
    solo la demo. Falta: ensayar con cronómetro y, si se quiere, exportarlo a `.docx`.

#### 5.1 Resultados de la prueba local (2026-10-08, Docker Desktop 4.71)

`docker compose -f infra/docker-compose.yml up -d rabbit1 rabbit2 rabbit-init notify`

- **Clúster:** `rabbitmqctl cluster_status` lista `rabbit@rabbit1` y `rabbit@rabbit2` como Disk Nodes y Running Nodes,
  sin alarmas ni particiones. `rabbit-init` termina en 0 y aplica la política `ha-all`.
- **Topología declarada por `notify`:** 3 colas (`q.cmd.email|kitchen|invoice`) + 3 DLQ, todas con política `ha-all`,
  maestro en `rabbit1` y espejo sincronizado en `rabbit2`. Exchanges: `cmd.direct` (direct), `cmd.topic` (topic),
  `cmd.dead.dlx` (direct). Bindings: direct por routing key, topic por `cmd.<dominio>.#`, DLX hacia cada DLQ.
  Tres consumidores conectados (uno por cola principal).
- **Flujo de mensajes (publicados con `node infra/publicar-mensajes-prueba.js`, vía la API de gestión):**

  | Mensaje | Resultado |
  |---|---|
  | email válido por topic `cmd.email.aceptado` | procesado OK |
  | kitchen e invoice válidos por direct | procesados OK |
  | cuerpo que no es JSON | `[email] mensaje enviado a DLQ ... motivo: mensaje invalido` |
  | kitchen sin `items` | DLQ, `error no recuperable: el payload no trae items` |
  | `failMode: "transient"` | 1 reintento (WARN) y luego DLQ, `reintentos agotados` |
  | `failMode: "poison"` | DLQ directo, `error no recuperable` |

  Estado final de las DLQ: `email.dlq`=2, `kitchen.dlq`=1, `invoice.dlq`=1, sincronizadas en ambos nodos.
- **Tolerancia a fallos:** con `docker stop pedidos360-rabbit1` las 6 colas pasaron a `rabbit@rabbit2` con sus mensajes
  intactos, los 3 consumidores se re-engancharon, `notify` reconectó solo (`Attempting to connect to: [rabbit1:5672,
  rabbit2:5672]`) y un mensaje nuevo publicado por `rabbit2` se procesó. Al hacer `docker start pedidos360-rabbit1`
  el clúster volvió a 2 nodos en ejecución.

#### 5.4 Verificado EN LA NUBE (2026-10-15)

EC2 `i-0345ad9405c67365a`, `t3.medium`, IP `98.88.19.87`, rama `feature/rabbitmq`, config por `infra/.env`.

- **Clúster**: `rabbit1` y `rabbit2` healthy; `cluster_status` lista ambos como Disk Nodes y Running
  Nodes, **sin alarmas ni particiones**; 4 CPU cores agregados. `rabbit-init` terminó en 0 y aplicó
  `ha-all`. Las dos UIs (15672 y 15673) muestran los dos nodos; la esquina superior derecha indica
  por cuál nodo se entró (`Cluster rabbit@rabbit1` / `rabbit@rabbit2`) → **elemento 8**.
- **Topología**: `notify` arrancó en 5.9 s con
  `Attempting to connect to: [rabbit1:5672, rabbit2:5672]` (usa el clúster, no un nodo suelto).
  `list_queues` muestra las 6 colas con política `ha-all`; `list_exchanges` muestra `cmd.direct`
  (direct), `cmd.topic` (**topic**) y `cmd.dead.dlx` (direct) → **elementos 9 y 10**.
- **Security Group**: se abrieron 15672 y 15673 en `sg-00fdffe9c8269bf28 (seg-wrt)`, el mismo que
  tiene el 8080. Son **solo para la demo**: los servicios hablan por la red interna de Docker.
  **Cerrarlos al terminar** (la UI va con guest/guest).
- **Disco**: `docker builder prune -af` + `image prune -af` liberaron 4.6 GB (de 9.4 a 15 GB libres).
  No hizo falta ampliar el volumen (es de 20 GB, no de 8 como decía el doc de EP2).

- **Stack completo desplegado**: los 8 contenedores arriba (`orders`, `notify`, `rabbit-admin`,
  `audit`, `bff`, `frontend`, `rabbit1`, `rabbit2`). Salud: bff y audit `{"status":"UP"}`;
  `orders` responde **401** en `/actuator/health` porque su `SecurityConfig` protege el actuator con
  JWT (los otros módulos lo dejan abierto — inconsistencia menor, no un fallo); `notify` y
  `rabbit-admin` responden UP por dentro de la red. Frontend en `https://localhost` → **200**.
  **3 consumidores** conectados (uno por cola de trabajo) y 2 conexiones AMQP.
- **Huella real**: 1.6 GB de RAM usados de 3.7 GB, y 13 GB de disco libres de 20 GB. El `t3.medium`
  queda holgado. Tiempos de arranque: `notify` 5.9 s, `orders` 41 s (JPA + Hibernate + H2) — el
  chequeo de salud hay que hacerlo después de ~45 s, no antes.
- **Frontend**: `dist` recompilado con la IP nueva y subido por `scp` a
  `~/frontend-prebuilt/dist/frontend-pedidos360/browser` (el anterior quedó como `browser.bak-*`),
  construido con `Dockerfile.prebuilt`.
- **CORS en API Gateway**: `access-control-allow-methods: GET,PATCH,POST,PUT` ya desplegado.
  `PATCH` era imprescindible: es el método de `orders.service.ts:48` y sin él el navegador bloquea
  el preflight del cambio de estado. `DELETE` sigue fuera (solo haría falta si el frontend llamara
  al administrador de colas).

#### 5.6 Flujo completo verificado EN LA NUBE (2026-10-16) — EP4 COMPLETO

Dos pedidos recorridos de `CREADO` a `ENTREGADO` desde el frontend, con token de Admin, pasando por
API Gateway. **12 comandos publicados, 12 procesados**, cero pérdidas.

| Transición | Exchange | Routing key | Cola |
|---|---|---|---|
| → ACEPTADO | `cmd.topic` | `cmd.email.aceptado` | email |
| → ACEPTADO | `cmd.direct` | `kitchen.ticket` | kitchen |
| → EN_PREPARACION | `cmd.topic` | `cmd.email.en_preparacion` | email |
| → DESPACHADO | `cmd.topic` | `cmd.email.despachado` | email |
| → ENTREGADO | `cmd.topic` | `cmd.email.entregado` | email |
| → ENTREGADO | `cmd.direct` | `invoice.gen` | invoice |

Los `eventId` **coinciden entre publicador y consumidor** (ese es el hilo a señalar en la demo), con
~177 ms entre publicar y procesar — por eso las colas vuelven a cero antes de que alcances a
refrescar la UI. Los tres consumidores trabajaron en sus propios hilos (`ntContainer#0/1/2`).

**DLQ: los 4 caminos de error verificados** (publicando al exchange por la API de gestión desde el
host del EC2 — el contenedor de rabbitmq no trae `curl`):

| Caso | Resultado en el log |
|---|---|
| `failMode: transient` | `error transitorio ... Se reintenta una vez (requeue)` → luego `DLQ ... motivo: reintentos agotados` |
| `failMode: poison` | `DLQ ... motivo: error no recuperable: falla no recuperable simulada` |
| kitchen sin `items` | `DLQ ... motivo: error no recuperable: el payload no trae 'items'` |
| cuerpo que no es JSON | `DLQ ... motivo: mensaje invalido: el cuerpo no es un envelope JSON valido` |

Estado final: `email.dlq`=2, `kitchen.dlq`=1, `invoice.dlq`=1, colas principales en 0. Las tres DLQ
con **réplica sincronizada en `rabbit@rabbit2`** (`ha-all` funcionando sobre las DLQ, no solo sobre
las colas de trabajo).

> **Antes de presentar**: purgar las colas para que los contadores partan de cero.
> `docker exec pedidos360-rabbit1 rabbitmqctl purge_queue q.cmd.email.dlq` (y las otras cinco).

**Hallazgos de esta sesión** (ver 5.5 y commits `ae8a4bf`, `545ac69`):
- La transición a `ACEPTADO` estaba rota en el EC2: `catalog` no está desplegado y la reserva de
  stock lanzaba 409. Resuelto con el flag `pedidos360.catalog.enabled`.
- El frontend **nunca tuvo** cómo cambiar el estado de un pedido: `changeStatus()` existía en el
  servicio pero ningún componente la llamaba. Se agregó la columna con selector y botón.
- Deuda pendiente: `orders` llama a `catalog` con un `RestTemplate` sin token. Si algún día se
  despliega `catalog`, hay que implementar autenticación entre servicios (client credentials) antes
  de volver a poner `CATALOG_ENABLED=true`.

#### 5.7 Persistencia: orders en PostgreSQL (RDS), conmutable a H2

Motivo: el Learner Lab apaga la instancia al cerrar la sesión y con H2 en memoria los pedidos se
pierden. Pasó el 2026-10-15 en plena sesión de trabajo.

- `SPRING_PROFILES_ACTIVE` de orders sale de **`ORDERS_PROFILE`** en `infra/.env`:
  `dev` = H2 en memoria · `cloud` = PostgreSQL en RDS. Hoy está en `cloud`.
- **Plan de rescate para el día de la presentación.** RDS también la apaga el Learner Lab, y si no
  está disponible `orders` **no levanta**. Volver a H2 son dos comandos (~40 s):
  ```bash
  sed -i 's/^ORDERS_PROFILE=.*/ORDERS_PROFILE=dev/' infra/.env
  docker compose up -d --force-recreate orders
  ```
  Conviene llevarlo anotado. Creas un pedido nuevo y sigues: crear un pedido en vivo es, de hecho,
  el elemento 3 de la rúbrica.
- **Contraseña fuera del repo**: `application.yml` traía `${DB_PASSWORD:Pedidos360_2026}`, con la
  credencial real como default y publicada en GitHub. Ahora es `${DB_PASSWORD:}` sin default; el
  valor vive solo en `infra/.env`. **Sigue estando en el historial de git**: lo correcto sería
  rotarla en RDS.
- **Verificado el 2026-10-16**: ciclo completo `CREADO → ENTREGADO` desde el frontend (6 comandos:
  4 email por topic, 1 kitchen y 1 invoice por direct), luego `docker compose restart orders`, y el
  pedido **seguía en la base** (`id=1, ENTREGADO, 50000.00`). orders arranca en ~12 s con `cloud`.
- `audit` y `catalog` siguen en H2: el perfil `cloud` de `audit` apunta a **Oracle** con credenciales
  `changeme`, sin configurar.

#### 5.5 Bug de autorización encontrado al desplegar (corregido, commit `f3ab53f`)

`SecurityConfig` de `orders`, `audit` y `catalog` usaba `requestMatchers("PATCH", ruta)` **sin
importar `HttpMethod`**. Esa sobrecarga recibe **patrones de ruta, no verbos**, así que `"POST"`,
`"PATCH"` y `"GET"` se interpretaban como rutas. En `orders` era un agujero real:

```java
.requestMatchers("POST",  "/api/orders/**")       // -> captura TODOS los metodos
.requestMatchers("PATCH", "/api/orders/*/status") // -> codigo muerto, nunca se evalua
```

La primera regla matchea `/api/orders/**` para cualquier método y se evalúa primero → **un CUSTOMER
podía cambiar el estado de cualquier pedido**. El BFF tampoco lo frenaba (su regla de
`/api/orders/**` permite los tres roles sin distinguir método). En `audit` y `catalog` el efecto era
el inverso (más restrictivo de lo previsto), sin impacto de seguridad.

Corregido con `HttpMethod.POST/PATCH/GET/PUT`; en `audit` la regla pasó a ser por ruta porque solo
expone GETs. Se agregaron **4 pruebas** en `orders` (`SecurityConfigTest`) que fijan las reglas por
método; el total del backend subió de 33 a **37**. **Verificado que detectan la regresión**: con el
bug reintroducido a propósito, el `PATCH` de un CUSTOMER devuelve **404 en vez de 403** — o sea pasa
la autorización y llega al controlador. Desplegado en el EC2.

> **Ojo para la demo**: ahora un CUSTOMER **ya no puede** cambiar estados. El usuario con el que se
> haga el bloque 4 de la presentación tiene que ser **Admin u Operator**.

**Pendiente en el EC2**: probar el flujo completo con JWT (`PATCH /api/orders/{id}/status` → 3 colas,
DLQ con `failMode`, y el CRUD de `/api/rabbit/**`). Todo lo demás está verificado.

**La clave SSH no era la del Learner Lab**: la instancia se lanzó con un par propio,
`clave-pedidos360.pem` (ni `labsuser.pem` viejo ni el recién descargado funcionan). Está instalada
como `labsuser.pem` en la carpeta del proyecto, con permisos restringidos con `icacls`; el original
de septiembre quedó como `labsuser.pem.bak-20260914`.

**Gotchas del EC2 anotados sobre la marcha**:
- El Learner Lab regenera el par de claves al reiniciar el entorno: el `labsuser.pem` viejo da
  `Permission denied (publickey)`. Hay que bajarlo de nuevo (AWS Details → Download PEM).
- EC2 Instance Connect (terminal del navegador) **se cae por inactividad** y mata lo que esté
  corriendo. Lanzar los builds con `nohup ... > ~/build.log 2>&1 &` y seguirlos con `tail -f`.
- El `Dockerfile` usa `mvn -q`: el paso de compilación no imprime nada durante minutos y parece
  colgado. No lo está.

#### 5.3 Despliegue en el EC2 — configuración por `.env`

El compose ya no tiene secretos ni rutas del host escritos adentro: salen de `infra/.env`
(ignorado por git, con plantilla en `infra/.env.example`). Así el `git pull` del EC2 no pisa nada.
Todas las variables tienen `${VAR:-default}`, de modo que sin `.env` el stack igual levanta.

**Primera vez en el EC2** (hay que hacerlo ANTES del `git pull`, para no perder los valores que hoy
están editados a mano en el `docker-compose.yml` del servidor):

```bash
cd ~/ms-pedidos360-backend
cp infra/docker-compose.yml /tmp/compose-con-secretos.bak   # rescatar los valores actuales
git stash                                                   # soltar la edicion local
git fetch origin && git checkout feature/rabbitmq && git pull
cp infra/.env.example infra/.env
nano infra/.env   # pegar AZURE_GRAPH_CLIENT_SECRET, AZURE_USER_DOMAIN y CORS_ALLOWED_ORIGINS=https://<IP>
```

**Rebuild y arranque** (el build es el pico de memoria: serializado, no en paralelo):

```bash
cd infra
export COMPOSE_PARALLEL_LIMIT=1
docker compose build
docker compose up -d
bash ../infra/verificar-cluster.sh
```

Notas:
- Se corre `cd infra` a propósito: docker compose lee el `.env` del directorio del compose.
  Desde la raíz hay que pasar `--env-file infra/.env`.
- Defaults del `.env.example` pensados para el EC2: `FRONTEND_DOCKERFILE=Dockerfile.prebuilt`
  (el `npm ci`/`ng build` adentro del contenedor tumba la instancia) y
  `FRONTEND_CONTEXT=../../../frontend-pedidos360`. Para levantar en local hay que descomentar
  las dos líneas de la sección Frontend del `.env`.
- `notify` (8083) y `rabbit-admin` (8086) ya **no publican puerto**: son internos a la red de
  Docker. `notify` no tiene spring-security y solo expone el actuator, así que publicarlo lo dejaba
  abierto; al `rabbit-admin` solo lo llama el BFF por `/api/rabbit/**`. Para depurarlos:
  `docker exec pedidos360-notify curl -s localhost:8083/actuator/health`.

#### 5.2 Problema de Docker Desktop en este equipo (por si reaparece)
Docker Desktop 4.71 no arrancaba: `initializing Inference manager: ... remove ...\AppData\Local\Docker\run\dockerInference:
El sistema no tiene acceso al archivo` (un socket viejo que Windows no deja borrar, error 1920). Se resolvió
reiniciando Docker Desktop/Windows; no usar "Reset to factory defaults" (borra imágenes y volúmenes). Durante el
diagnóstico se puso `"EnableDockerAI": false` en `%APPDATA%\Docker\settings-store.json` (no fue la solución; hay respaldo
`settings-store.json.bak-pedidos360` por si se quiere revertir).

### Cierre de EP3
- Merge de `feature/rabbitmq` a `main` cuando esté probada; el compañero (EP3 es en parejas) debería revisarla.
- Entrega: enlaces de los repos de GitHub en AVA + copia al correo del docente (backend y frontend).
- Frontend: EP3 pide que esté completo y sin errores de compilación; no hay cambios de RabbitMQ en el
  front (opcional: pantalla de administración de colas para Admin).

### Deuda / buenas prácticas
- `orders/application.yml` (perfil `cloud`) trae una contraseña de base de datos por defecto escrita en
  el repo; conviene dejarla solo como variable de entorno.
- Idempotencia de `notify` en memoria (se pierde al reiniciar); suficiente para el MVP.

## 6. Notas para retomar
- Repos: `https://github.com/franciscomonsalve/ms-pedidos360-backend` y `.../frontend-pedidos360-`.
  Copias locales: `C:\Users\Gene\Desktop\PEDIDOS360\pedidos360-backend` y `...\frontend-pedidos360`.
- Compilar y probar: `mvn clean package` (desde la raíz del backend).
- EC2 (AWS Academy): instancia `i-0345ad9405c67365a` (`pedidos360-ec2`), **`t3.medium`**, us-east-1,
  **sin Elastic IP** → la IP cambia al parar/prender; el checklist de qué actualizar está en
  `Despliegue_AWS_Pedidos360_EP2.md` sección 2. IP al 2026-10-16: **`100.48.99.104`**
  (anteriores: `98.88.19.87`, `54.163.21.47` — cambia en cada arranque del Learner Lab). Privada: `172.31.31.31`.
- Preferencia del usuario: commits **sin** línea de coautoría de IA.
- La terminal del usuario es PowerShell 5.1: no acepta `&&`.
