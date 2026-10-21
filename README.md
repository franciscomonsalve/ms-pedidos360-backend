# Pedidos360 — Backend

Sistema de gestión de pedidos con arquitectura de microservicios, desplegado en AWS. Las tareas
pesadas que dispara un cambio de estado —notificar al cliente, emitir el ticket de cocina, generar
la boleta— se procesan de forma asíncrona con **RabbitMQ**, de modo que no bloquean la operación.

Asignatura DSY1107 (Desarrollo Cloud Native I), Duoc UC.

| Documento | Contenido |
|---|---|
| [RABBITMQ_AVANCE.md](RABBITMQ_AVANCE.md) | Diseño de la mensajería, decisiones, hallazgos y estado |
| [PRUEBAS_REALIZADAS.md](PRUEBAS_REALIZADAS.md) | Evidencia de las pruebas ejecutadas |
| [ms-pedidos360-bff/REGISTRO_USUARIOS.md](ms-pedidos360-bff/REGISTRO_USUARIOS.md) | Alta de usuarios en Entra ID |
| [infra/TOPOLOGIA.md](infra/TOPOLOGIA.md) | La topología de mensajería en detalle |
| [infra/DONDE_MIRAR.md](infra/DONDE_MIRAR.md) | Qué pantalla mirar en RabbitMQ y en CloudWatch, y qué debería verse |

---

## Módulos

| Módulo | Puerto | Qué hace |
|---|---|---|
| `ms-pedidos360-bff` | 8080 | Puerta de entrada. Valida el JWT, aplica los roles y hace de proxy hacia el resto |
| `ms-pedidos360-orders` | 8081 | Pedidos y su ciclo de estados. **Publica** los comandos a RabbitMQ |
| `ms-pedidos360-catalog` | 8082 | Productos y stock (no se despliega hoy, ver más abajo) |
| `ms-pedidos360-notify` | 8083 | **Consume** los comandos: correo, cocina y boleta |
| `ms-pedidos360-audit` | 8084 | Registro de auditoría, solo lectura y solo Admin |
| `ms-pedidos360-rabbit-admin` | 8086 | API REST para administrar colas, exchanges y bindings |

`notify` y `rabbit-admin` **no publican puerto** hacia fuera: son internos a la red de Docker.

### Mensajería

```
cmd.direct (direct) --kitchen.ticket--> q.cmd.kitchen --(rechazo)--> cmd.dead.dlx --> q.cmd.kitchen.dlq
cmd.direct          --invoice.gen-----> q.cmd.invoice --(rechazo)--> cmd.dead.dlx --> q.cmd.invoice.dlq
cmd.topic  (topic)  --cmd.email.#-----> q.cmd.email   --(rechazo)--> cmd.dead.dlx --> q.cmd.email.dlq
```

Los nombres de colas, exchanges y routing keys **no están escritos en el código Java**: viven en el
bloque `pedidos360.messaging.rabbit` del `application.yml` y se leen con `@ConfigurationProperties`.

Qué publica cada transición de estado:

| Transición | Mensajes |
|---|---|
| Cualquier cambio de estado | email, por el exchange **topic**, clave `cmd.email.<estado>` |
| → `ACEPTADO` | + ticket de cocina, por el **direct** |
| → `ENTREGADO` | + boleta, por el **direct** |

---

## Requisitos

- **JDK 21**
- **Maven 3.9+**
- **Docker** y Docker Compose (para el clúster de RabbitMQ)

### Antes de compilar: `toolchains.xml`

El `pom.xml` raíz usa `maven-toolchains-plugin` y **exige un JDK 21 declarado**. Sin esto, la
compilación falla con `No toolchain found`. Crea `~/.m2/toolchains.xml` (en Windows,
`C:\Users\<tu-usuario>\.m2\toolchains.xml`):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<toolchains>
  <toolchain>
    <type>jdk</type>
    <provides>
      <version>21</version>
    </provides>
    <configuration>
      <jdkHome>RUTA/A/TU/JDK21</jdkHome>
    </configuration>
  </toolchain>
</toolchains>
```

Ajusta `jdkHome` a tu instalación (por ejemplo `C:\Program Files\Amazon Corretto\jdk21.0.10_7` o
`/usr/lib/jvm/java-21-openjdk`).

---

## Compilar y pasar las pruebas

```bash
mvn clean package
```

Debe terminar en `BUILD SUCCESS` con **43 pruebas** en verde. El desglose por clase está en
[PRUEBAS_REALIZADAS.md](PRUEBAS_REALIZADAS.md).

> Durante las pruebas de `notify` verás líneas `ERROR ... mensaje enviado a DLQ`. **Son esperadas**:
> es el test ejercitando los caminos de error a propósito.

---

## Levantar el clúster de RabbitMQ en local

Esto **no necesita ningún secreto** y cubre lo central del proyecto: los dos nodos en clúster, las
3 colas con sus DLQ, los exchanges direct y topic, y el manejo de errores.

```bash
cd infra
docker compose up -d rabbit1 rabbit2 rabbit-init notify
```

`rabbit2` espera a que `rabbit1` esté sano antes de unirse al clúster, así que tarda ~40 segundos.

Interfaces de gestión (usuario `guest`, contraseña `guest`):

- http://localhost:15672 → nodo 1
- http://localhost:15673 → nodo 2

**Las dos muestran los dos nodos.** Esa es la prueba de que están integrados en un clúster y no son
dos brokers independientes.

### Verificar

```bash
bash infra/verificar-cluster.sh    # cluster_status, política, colas+DLQ, exchanges, bindings, réplicas
bash infra/demo-dlq.sh             # publica los 4 casos de error y muestra cómo los trata el consumidor
bash infra/demo-dlq.sh --solo-purgar   # deja las 6 colas en cero
```

Lo que debe salir de `demo-dlq.sh`:

| Caso | Resultado |
|---|---|
| `failMode: transient` | **dos líneas**: un reintento y después la DLQ por `reintentos agotados` |
| `failMode: poison` | DLQ directo, `error no recuperable` |
| Payload sin `items` | DLQ, `el payload no trae 'items'` |
| Cuerpo que no es JSON | DLQ, `mensaje invalido` |

Para seguir los ACK mientras usas el sistema:

```bash
docker logs -f pedidos360-notify
```

---

## Políticas de retención: TTL y longitud máxima

```bash
bash infra/politicas-retencion.sh          # aplica: DLQ 24 h, principales 10 min, máx. 1000
bash infra/politicas-retencion.sh --ver    # qué política aplica a cada cola
bash infra/politicas-retencion.sh --quitar # vuelve atrás (no toca ha-all)
```

| Colas | TTL | max-length | Overflow |
|---|---|---|---|
| `q.cmd.email` · `kitchen` · `invoice` | 10 min | 1000 | `reject-publish-dlx` |
| las tres `.dlq` | 24 h | 1000 | — |

**Se aplican por política, no como argumentos de la cola.** Los argumentos de una cola son
inmutables: cambiar el TTL obliga a borrar y recrear la cola, o el arranque falla con
`PRECONDITION_FAILED`. Una política hace lo mismo sobre colas ya creadas, se cambia en caliente
y se quita con un comando.

> **RabbitMQ aplica una sola política por cola:** gana la de mayor prioridad y las definiciones
> **no se combinan**. Las políticas de retención están en prioridad 2 y `ha-all` en 1, así que
> desplazarían la replicación. Por eso cada definición de retención **repite** `ha-mode` y
> `ha-sync-mode`. Sin eso, las 6 colas quedan sin réplica aunque todo parezca funcionar.

Con esto quedan cubiertos los tres motivos por los que un mensaje llega a la DLQ:

| `reason` en `x-death` | Causa | Dónde se configura |
|---|---|---|
| `rejected` | el consumidor hace `basicReject` | código, `CommandProcessor` |
| `expired` | se venció el TTL | política |
| `maxlen` | la cola alcanzó su límite | política, con `overflow: reject-publish-dlx` |

Para ver la expiración en vivo: `--demo-ttl` baja el TTL de `q.cmd.email` a 15 s. **Hay que
quitarla después**, o rompe la demostración de la caída del consumidor.

---

## Monitoreo con CloudWatch

Opcional y desacoplado: un overlay de Compose que cambia el driver de logs de los 9 servicios.

```bash
docker compose -f docker-compose.yml -f docker-compose.cloudwatch.yml up -d
bash infra/cloudwatch-verificar.sh
```

Los registros van al grupo `/pedidos360/compose`, con un flujo por servicio. Requiere que la
instancia tenga un perfil de IAM con permiso sobre CloudWatch Logs (en AWS Academy,
`LabInstanceProfile`).

**Es un archivo aparte a propósito:** si el driver `awslogs` falla, el contenedor **no arranca**.
Manteniéndolo opcional, el stack normal nunca depende de que AWS responda.

Consulta útil en Logs Insights, para encontrar una falla sin saber en qué servicio ocurrió:

```
fields @timestamp, @logStream, @message
| filter @message like /DLQ/
| sort @timestamp desc
```

> `docker logs` sigue funcionando igual, porque Docker mantiene una caché local además de
> enviar. Para una demostración en vivo conviene `docker logs`, que es instantáneo; CloudWatch
> tarda unos segundos en indexar.

---

## Demostración guiada

```bash
bash infra/demo-presentacion.sh                      # 4 actos, con pausas
bash infra/demo-presentacion.sh --con-caida-de-nodo  # incluye tumbar un nodo del clúster
bash infra/demo-presentacion.sh --falla-ahora        # publica un solo mensaje envenenado
```

Los cuatro actos: camino feliz · se cae el consumidor · falla el mensaje · se cae un nodo.

`--falla-ahora` sella el `eventId` con la hora, porque si se repitiera, la idempotencia lo
descartaría en silencio y no se vería nada en los registros.

---

## Levantar el stack completo

Requiere configuración que **no está en el repositorio**.

```bash
cp infra/.env.example infra/.env
# editar infra/.env
cd infra
docker compose up -d
```

### Qué hay que rellenar en `infra/.env`

| Variable | Para qué | ¿Está en el repo? |
|---|---|---|
| `AZURE_GRAPH_CLIENT_SECRET` | El BFF crea usuarios en Entra ID | **No** — pídesela a quien administre el tenant |
| `DB_PASSWORD` | Solo si usas `ORDERS_PROFILE=cloud` | **No** |
| `CORS_ALLOWED_ORIGINS` | Debe coincidir con el origin del frontend | Default `http://localhost:4200` |
| `FRONTEND_CONTEXT` / `FRONTEND_DOCKERFILE` | Ruta del repo del frontend | Los defaults apuntan al layout del **servidor**; en local hay que cambiarlos |

> **Importante en local:** descomenta en el `.env` las dos líneas de la sección Frontend
> (`FRONTEND_CONTEXT=../../frontend-pedidos360` y `FRONTEND_DOCKERFILE=Dockerfile`), o el build del
> frontend fallará buscando una ruta que no existe en tu máquina.

Sin el secreto de Graph, todo funciona **menos** el registro de usuarios nuevos.

### Base de datos de `orders`

Se elige con `ORDERS_PROFILE` en `infra/.env`:

- **`dev`** → H2 en memoria. Arranca siempre; los pedidos se pierden al reiniciar. **Úsalo en local.**
- **`cloud`** → PostgreSQL. Los pedidos persisten, pero si la base no está disponible `orders` no levanta.

### Por qué `catalog` no está en el compose

`orders` llama a `catalog` para descontar stock al aceptar un pedido, pero lo hace con un
`RestTemplate` **sin token**, y ese endpoint exige rol. La llamada daría 401 y, como el error se
convierte en 409, **toda transición a `ACEPTADO` fallaría**. Por eso existe el flag
`pedidos360.catalog.enabled` (`CATALOG_ENABLED`), hoy en `false`.

Para volver a activarlo hay que implementar antes la autenticación entre servicios.

---

## Autenticación y roles

La identidad está en **Entra ID** (Azure AD). El frontend obtiene un JWT con
OAuth 2.0 / OpenID Connect usando Authorization Code + PKCE, y el API Manager lo valida antes de
llegar al backend.

Roles y lo que permiten:

| Ruta | Roles |
|---|---|
| `GET`/`POST` `/api/orders/**` | Admin, Operator, Customer |
| `PATCH /api/orders/{id}/status` | **Admin, Operator** |
| `/api/audit/**` | Admin |
| `/api/rabbit/**` | Admin |
| `POST /api/users/register` | Pública |

---

## API del administrador de RabbitMQ

Vía BFF, solo rol Admin. Base: `/api/rabbit`

```
POST   /queues      {"name":"q.demo","withDeadLetter":true,
                     "deadLetterExchange":"cmd.dead.dlx","deadLetterRoutingKey":"demo"}
GET    /queues   ·  GET /queues/{name}   ·  DELETE /queues/{name}
POST   /exchanges   {"name":"ex.demo","type":"TOPIC"}
GET    /exchanges   ·  DELETE /exchanges/{name}
POST   /bindings    {"queue":"q.demo","exchange":"ex.demo","routingKey":"demo.#"}
GET    /bindings    ·  DELETE /bindings?queue=&exchange=&routingKey=
POST   /messages    {"exchange":"ex.demo","routingKey":"demo.prueba",
                     "type":"demo.test","payload":{...}}
```

Las altas y bajas usan AMQP; los listados, la API HTTP de gestión de RabbitMQ.

La entrada está validada: nombre vacío o con caracteres no permitidos devuelve **400** con el
detalle por campo, el prefijo `amq.` está reservado, duplicado devuelve **409** e inexistente **404**.

---

## Estructura

```
infra/
  docker-compose.yml            Stack completo: 5 microservicios + nginx + 2 nodos RabbitMQ
  docker-compose.cloudwatch.yml Overlay opcional: envía los logs a CloudWatch
  .env.example                  Plantilla de configuración (copiar a .env, que no se versiona)
  rabbitmq/rabbitmq.conf        Config compartida por los dos nodos (peer discovery, límites)
  rabbitmq/ha-policy.json       Política de réplica de las colas
  verificar-cluster.sh          Chequeos del clúster
  demo-dlq.sh                   Demo de los caminos de error a DLQ
  demo-presentacion.sh          Demostración en 4 actos, con pausas
  politicas-retencion.sh        Aplica/quita TTL y longitud máxima por política
  cloudwatch-verificar.sh       Comprueba que los logs llegan a CloudWatch
  TOPOLOGIA.md                  La topología de mensajería en detalle
  DONDE_MIRAR.md                Qué pantalla mirar y qué debería verse
ms-pedidos360-*/                Un módulo Maven por microservicio
```

El frontend está en un repositorio aparte, con su propio
[README](../frontend-pedidos360/README.md).
