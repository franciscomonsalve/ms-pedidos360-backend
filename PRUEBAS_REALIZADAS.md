# Pruebas realizadas — Pedidos360 (EP3 / EP4, DSY1107)

_Evidencia de las pruebas ejecutadas sobre la integración de RabbitMQ: automatizadas, de
integración local y verificaciones contra el entorno desplegado en AWS. Última actualización:
2026-10-17._

**Resultado global:** 43 pruebas automatizadas en verde (`BUILD SUCCESS`) y los 11 elementos
exigidos por EP4 verificados en la nube. No queda código sin ejecutar contra un broker real.

---

## 1. Pruebas automatizadas

Se ejecutan con `mvn clean package` desde la raíz del backend.

| Clase | Módulo | Pruebas | Qué cubre |
|---|---|---|---|
| `CommandProcessorTest` | notify | 9 | ACK, reintento, DLQ e idempotencia del consumidor |
| `RabbitAdminServiceTest` | rabbit-admin | 13 | Alta y baja de colas, exchanges y bindings |
| `RabbitAdminControllerTest` | rabbit-admin | 11 | Contratos REST y códigos de respuesta |
| `OrderServiceCatalogFlagTest` | orders | 6 | Flag del catálogo y comandos publicados por transición |
| `SecurityConfigTest` | orders | 4 | Autorización por método HTTP |
| **Total** | | **43** | |

### 1.1 `CommandProcessorTest` — el consumidor (indicador de ACK y errores, 20%)

Cubre las cuatro rutas de decisión del consumidor:

- Mensaje válido → se procesa y se confirma con ACK explícito.
- Mensaje duplicado → se confirma **sin reprocesar** (idempotencia por `eventId`).
- Error transitorio → **un reintento**; si vuelve a fallar, va a la DLQ con el motivo
  `reintentos agotados`.
- Error no recuperable → DLQ directo, sin reintentar.
- Cuerpo que no es un envelope JSON válido → DLQ con el motivo `mensaje invalido`.
- Envelope sin campos obligatorios (`eventId`, `type`) → DLQ.

### 1.2 `SecurityConfigTest` — regresión de un bug real

Esta clase existe porque se detectó un **fallo de autorización** durante el despliegue. La
configuración usaba `requestMatchers("PATCH", "/api/orders/*/status")` sin importar `HttpMethod`.
Esa sobrecarga recibe **patrones de ruta, no verbos**, así que la regla anterior
(`"/api/orders/**"`) capturaba todos los métodos y la de PATCH quedaba como código muerto:
**un usuario con rol CUSTOMER podía cambiar el estado de cualquier pedido.**

Las pruebas fijan el comportamiento correcto:

| Caso | Esperado |
|---|---|
| `PATCH /api/orders/1/status` con rol CUSTOMER | **403** |
| Mismo endpoint con ADMIN y con OPERATOR | Pasa la capa de autorización |
| Mismo endpoint sin token | **401** |
| `GET /api/orders` con los tres roles | **200** |

**Se comprobó que detectan la regresión:** reintroduciendo el bug a propósito, el `PATCH` de un
CUSTOMER devuelve **404 en vez de 403** — es decir, pasa la autorización y llega al controlador.

### 1.3 `OrderServiceCatalogFlagTest` — flag del catálogo y mapeo de comandos

| Caso | Esperado |
|---|---|
| Catálogo apagado → transición a `ACEPTADO` | Funciona y no se llama al catálogo |
| Catálogo encendido pero caído → `ACEPTADO` | **409** (reproduce el fallo original) |
| → `ACEPTADO` | Publica email (topic) y ticket de cocina; **no** boleta |
| → `ENTREGADO` | Publica email y boleta; **no** ticket de cocina |
| Transición intermedia | Publica **solo** el email |
| Transición inválida | **409** y no publica nada |

---

## 2. Pruebas de integración en local (2026-10-08)

Clúster real levantado con Docker Desktop:
`docker compose -f infra/docker-compose.yml up -d rabbit1 rabbit2 rabbit-init notify`

- **Clúster**: `rabbitmqctl cluster_status` lista `rabbit@rabbit1` y `rabbit@rabbit2` como Disk
  Nodes y Running Nodes, sin alarmas ni particiones de red.
- **Topología declarada desde el código**: 3 colas, 3 DLQ, los 3 exchanges y sus bindings, con la
  política `ha-all` aplicada y réplica sincronizada en el segundo nodo.
- **Flujo de mensajes** (6 casos): válidos por direct y por topic, cuerpo no-JSON, payload sin
  `items`, `failMode: transient` y `failMode: poison`. Todos con el resultado esperado.
- **Tolerancia a fallos**: con `docker stop pedidos360-rabbit1`, las 6 colas pasaron a `rabbit2`
  con sus mensajes intactos, los 3 consumidores se re-engancharon y un mensaje nuevo publicado por
  el segundo nodo se procesó con normalidad. Al reiniciar el primer nodo, el clúster volvió a dos.

---

## 3. Pruebas en la nube (AWS)

Entorno: EC2 `t3.medium` en us-east-1, 8 contenedores (5 microservicios, nginx y 2 nodos RabbitMQ),
API Gateway como API Manager, Entra ID como IDaaS y PostgreSQL en RDS.

### 3.1 Clúster y topología

- `cluster_status` con los dos nodos como Running Nodes, sin alarmas ni particiones.
- Las dos interfaces de gestión (puertos 15672 y 15673) muestran **ambos nodos**, cada una
  indicando por cuál se entró. Confirma integración en clúster, no dos brokers independientes.
- `rabbit-init` aplica la política `ha-all` y termina con código 0.
- Las 6 colas con política `ha-all` y los 3 exchanges con su tipo correcto:
  `cmd.direct` (direct), `cmd.topic` (**topic**) y `cmd.dead.dlx` (direct).

### 3.2 Flujo completo de mensajes

Dos pedidos recorridos de `CREADO` a `ENTREGADO` desde el frontend, con token de Admin y pasando
por el API Manager. **12 comandos publicados, 12 procesados**, sin pérdidas.

| Transición | Exchange | Routing key | Cola |
|---|---|---|---|
| → ACEPTADO | `cmd.topic` | `cmd.email.aceptado` | email |
| → ACEPTADO | `cmd.direct` | `kitchen.ticket` | kitchen |
| → EN_PREPARACION | `cmd.topic` | `cmd.email.en_preparacion` | email |
| → DESPACHADO | `cmd.topic` | `cmd.email.despachado` | email |
| → ENTREGADO | `cmd.topic` | `cmd.email.entregado` | email |
| → ENTREGADO | `cmd.direct` | `invoice.gen` | invoice |

Los `eventId` **coinciden entre publicador y consumidor**, con unos 177 ms entre publicar y
procesar. Los tres consumidores trabajan en hilos independientes.

### 3.3 DLQ: los cuatro caminos de error

Ejecutado con `bash infra/demo-dlq.sh`, que publica los casos directo a los exchanges:

| Caso | Resultado en el log del consumidor |
|---|---|
| `failMode: transient` | `error transitorio ... Se reintenta una vez (requeue)` y después `DLQ ... motivo: reintentos agotados` |
| `failMode: poison` | `DLQ ... motivo: error no recuperable` |
| Payload sin `items` | `DLQ ... motivo: error no recuperable: el payload no trae 'items'` |
| Cuerpo que no es JSON | `DLQ ... motivo: mensaje invalido: el cuerpo no es un envelope JSON valido` |

Estado final: `email.dlq`=2, `kitchen.dlq`=1, `invoice.dlq`=1, colas principales en 0. Las tres
DLQ con **réplica sincronizada en `rabbit@rabbit2`**: la política cubre también las colas de
mensajes muertos.

### 3.4 Microservicio administrador (`/api/rabbit/**`)

Ejecutado vía API Gateway → BFF → `rabbit-admin` con token de Admin.

**Endpoints REST:**

| Operación | Código | Observación |
|---|---|---|
| `POST /queues` | 201 | Crea `q.demo` **y** su `q.demo.dlq` |
| `POST /exchanges` | 201 | `ex.demo` tipo TOPIC |
| `POST /bindings` | 201 | `q.demo` ← `ex.demo` con `demo.#` |
| `POST /messages` | 202 | Devuelve el `eventId` |
| `GET /queues/q.demo` | 200 | `messageCount: 1` tras publicar → **el binding enruta de verdad** |
| `GET /exchanges`, `GET /bindings` | 200 | Listados vía API de gestión |
| `DELETE` de binding, cola y exchange | 204 | Y **404** al consultar después |

**Validación de entrada:**

| Entrada | Respuesta |
|---|---|
| Nombre vacío | 400 `el nombre de la cola es obligatorio` |
| `"mi cola!"` | 400 con el patrón permitido explicado |
| `amq.fanout` | 400 `El prefijo 'amq.' esta reservado por RabbitMQ` |
| Tipo de exchange faltante | 400 `el tipo es obligatorio (DIRECT, TOPIC, FANOUT o HEADERS)` |
| Cola duplicada | 409 `La cola 'q.demo' ya existe` |
| Cola inexistente | 404 |
| `DELETE amq.topic` | 400 (reservado) |

Las respuestas 400 incluyen un array `details` con el campo y el mensaje de cada violación.

### 3.5 Autorización y persistencia

- **403 verificado en producción**: un usuario sin rol Admin ni Operator recibe
  `No tienes permisos para cambiar el estado (requiere Admin u Operator)` al intentar cambiar el
  estado de un pedido. Con un usuario Admin, la misma operación funciona. Es la confirmación en
  vivo del arreglo descrito en 1.2.
- **Persistencia**: tras `docker compose restart orders`, el pedido seguía en PostgreSQL. Y tras un
  **apagado completo de la instancia** (el entorno de laboratorio la detiene al cerrar la sesión),
  al volver con otra IP pública el pedido seguía ahí.

### 3.6 Configuración del API Manager

| Comprobación | Resultado |
|---|---|
| Preflight `OPTIONS /api/orders` | 200, con `access-control-allow-methods: GET,PATCH,POST,PUT` |
| Preflight `OPTIONS /api/orders/{id}/status` con PATCH | 200 |
| `GET /api/orders` sin token | **401** del JWT Authorizer |
| `GET /api/audit` con token de rol insuficiente | **403** |
| `GET /api/orders` con token válido | **200** |

---

## 4. Cobertura: qué no está cubierto

Por transparencia, lo que **no** tiene pruebas:

- `bff`, `catalog` y `audit` no tienen pruebas automatizadas. El módulo `orders` pasó de 0 a 10
  durante este trabajo, a raíz del fallo de autorización descrito en 1.2.
- La idempotencia de `notify` se mantiene en memoria: se pierde al reiniciar el servicio. Es
  suficiente para el alcance del proyecto y está documentado como deuda consciente.
- `orders` llama a `catalog` con un `RestTemplate` sin token. Por eso la reserva de stock está
  desactivada mediante el flag `pedidos360.catalog.enabled`: con la llamada activa, toda transición
  a `ACEPTADO` fallaba con 409. Habilitarla requiere implementar autenticación entre servicios.

---

## 5. Cómo reproducir estas pruebas

```bash
# Automatizadas (43)
mvn clean package

# Clúster, topología, réplicas y bindings
bash infra/verificar-cluster.sh

# Los 4 caminos de error a DLQ
bash infra/demo-dlq.sh

# Dejar las colas en cero
bash infra/demo-dlq.sh --solo-purgar
```

El detalle técnico de cada hallazgo y su corrección está en
[RABBITMQ_AVANCE.md](RABBITMQ_AVANCE.md).
