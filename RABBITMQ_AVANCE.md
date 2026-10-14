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

Todo en `infra/`. `docker compose config` valida sin errores; **falta probarlo contra Docker**
(Docker Desktop estaba apagado al momento de escribirlo).

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
- **Tamaño del EC2**: `Despliegue_AWS_Pedidos360_EP2.md` dice `t3.micro` (913 MB, sin swap);
  la Fase A de este documento asumía `t3.medium`. Con 913 MB **no cabe** (5 JVM + 2 RabbitMQ + nginx).
  Hay que confirmar el tipo real de instancia y, si sigue en `t3.micro`, subirla a `t3.small`/`t3.medium`
  o agregar swap antes de la demo.
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
8. Desplegar la rama en el EC2 (git pull + rebuild de todas las imágenes) y comprobar que
   `PATCH /api/orders/{id}/status` funciona (el fix de `-parameters` aún no está desplegado).
   Ojo: el `docker-compose.yml` del EC2 tiene cambios locales con secretos (Graph, CORS, ruta del
   frontend) que chocan con el nuevo; conviene moverlos a un `.env` fuera de git.
9. Security Group del EC2: abrir 15672 (y 15673 para mostrar el 2.º nodo) en la demo, o túnel SSH.
10. Agregar a la guía de presentación (`Guion_Presentacion_EP2.md` / `.docx`) los bloques de RabbitMQ:
    clúster de 2 nodos, 3 colas + DLQ, exchanges direct/topic, docker-compose en la nube.

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
- EC2 (AWS Academy): sin Elastic IP, la IP cambia al parar/prender; el checklist de qué actualizar está en
  `Despliegue_AWS_Pedidos360_EP2.md`. Última IP usada: `54.163.21.47` (puede haber cambiado).
- Preferencia del usuario: commits **sin** línea de coautoría de IA.
- La terminal del usuario es PowerShell 5.1: no acepta `&&`.
