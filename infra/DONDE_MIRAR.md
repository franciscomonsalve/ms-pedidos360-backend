# Dónde mirar para comprobar que todo funciona

Mapa de clics: qué URL abrir, qué pestaña, qué columna, y **qué número o línea demuestra qué**.
Pensado para tenerlo al lado durante la presentación.

> Sustituye `<IP>` por la IP pública del EC2 de la sesión. Si cambió, primero el checklist de
> cambio de IP.

---

## 1. RabbitMQ — la UI de gestión

`http://<IP>:15672` (nodo 1) y `http://<IP>:15673` (nodo 2) · usuario `guest`, clave `guest`

### Overview → sección **Nodes**

Lo primero que hay que mirar. **Debe listar los DOS nodos**, `rabbit@rabbit1` y `rabbit@rabbit2`,
ambos en verde.

| Si ves | Significa |
|---|---|
| Dos nodos | El clúster está formado · **elemento 8 demostrado** |
| Un solo nodo | No se formó el clúster, o uno se cayó |
| La misma lista en `:15672` y en `:15673` | Son el mismo clúster, no dos brokers sueltos |

> Ese último punto es el que vale: abre las dos UI en pestañas distintas y muestra que **ambas
> listan los mismos dos nodos**.

### Queues → **las 6 colas**

| Columna | Qué significa | Qué esperar |
|---|---|---|
| **Ready** | Mensajes esperando a que alguien los consuma | 0 en reposo; **sube** si el consumidor está caído |
| **Unacked** | Entregados pero aún sin confirmar | 0 o muy bajo; si crece, el consumidor se atascó |
| **Total** | Ready + Unacked | |
| **Node** | En qué nodo está el maestro de esa cola | `rabbit1` o `rabbit2`, da igual cuál |
| **Policy** | Qué política le aplica | `retencion-principales` o `retencion-dlq` |

Las seis que deben aparecer:

```
q.cmd.email        q.cmd.email.dlq
q.cmd.kitchen      q.cmd.kitchen.dlq
q.cmd.invoice      q.cmd.invoice.dlq
```

**Elemento 9 demostrado** cuando se ven las 6 y los contadores se mueven al crear un pedido.

### Clic en una cola → **Features** y **Mirrors**

Al entrar a `q.cmd.email`:

- **Features** muestra sus argumentos: `D` (durable), y la política aplicada con
  `message-ttl`, `max-length`, `ha-mode`
- Más abajo, **"Mirrors"** o **"Synchronised mirrors"** nombra el **otro** nodo
  → eso prueba que la cola está **replicada**, no solo que el clúster existe

> Si "Mirrors" está vacío, la replicación se perdió. Se arregla volviendo a aplicar
> `bash infra/politicas-retencion.sh`, que incluye `ha-mode` en cada definición.

### Clic en una DLQ → **Get messages**

Para mostrar el contenido de la cola muerta sin vaciarla:

1. Entra a `q.cmd.email.dlq`
2. Baja hasta **Get messages**
3. **Ack Mode:** elige `Nack message requeue true` ← importante, así **no** los saca
4. Messages: 5 → **Get Message(s)**

Verás el JSON del mensaje que falló. El contador **no baja**.

### Exchanges → filtrar por `cmd`

Deben aparecer los tres, con su tipo en la columna **Type**:

| Exchange | Type |
|---|---|
| `cmd.direct` | `direct` |
| `cmd.topic` | `topic` |
| `cmd.dead.dlx` | `direct` |

**Elemento 10 demostrado.** Clic en `cmd.topic` → **Bindings** muestra los patrones
(`cmd.email.#`, `cmd.kitchen.#`, `cmd.invoice.#`).

### Admin → Policies

Deben estar las tres: `ha-all`, `retencion-dlq` y `retencion-principales`.

---

## 2. CloudWatch — los logs de los servicios

Consola AWS → **CloudWatch** → **Logs** → **Log groups** → **`/pedidos360/compose`**

### Log streams — 9 flujos, uno por contenedor

```
orders   notify   rabbit-admin   audit   bff
frontend   rabbit1   rabbit2   rabbit-init
```

**El que importa para la demo es `notify`**: ahí está el trabajo de los tres consumidores y
todos los envíos a la cola muerta.

### Qué línea prueba qué

| Lo que pasó | La línea que buscas |
|---|---|
| Procesado bien | `comando procesado OK eventId=... correlationId=...` |
| El consumidor hizo su trabajo | `Notificando al cliente ...` · `Ticket de cocina emitido ...` · `Factura generada ...` |
| Mensaje duplicado | `ya procesado, se descarta el duplicado (idempotencia)` |
| Error transitorio | `error transitorio ... Se reintenta una vez (requeue)` |
| Reintento agotado | `mensaje enviado a DLQ (...) motivo: reintentos agotados` |
| Error no recuperable | `mensaje enviado a DLQ (...) motivo: error no recuperable` |
| Mensaje malformado | `motivo: mensaje invalido: el cuerpo no es un envelope JSON valido` |

> **Señala siempre el `motivo:`.** Es la prueba de que el consumidor distingue el tipo de error
> en vez de tratarlos todos igual, que es el indicador que vale 20%.

### Logs Insights — buscar en los 9 servicios a la vez

Esto es lo que `docker logs` no puede hacer. CloudWatch → **Logs Insights** → seleccionar
`/pedidos360/compose` → pegar:

```
fields @timestamp, @logStream, @message
| filter @message like /DLQ/
| sort @timestamp desc
```

Otras consultas útiles:

```
# Todo lo que procesó bien, en la última hora
fields @timestamp, @logStream, @message
| filter @message like /procesado OK/
| sort @timestamp desc

# Solo errores, de cualquier servicio
fields @timestamp, @logStream, @message
| filter @message like /ERROR/
| sort @timestamp desc

# Seguir un pedido concreto por su correlationId
fields @timestamp, @logStream, @message
| filter @message like /correlationId=3/
| sort @timestamp asc
```

> Logs Insights tarda unos segundos en indexar. Para la demo en vivo usa `docker logs`, que es
> instantáneo, y deja CloudWatch para responder *"¿y cómo monitorean esto de verdad?"*.

---

## 3. Por terminal — lo más rápido

```bash
# Los 9 servicios
docker compose ps

# El clúster
docker exec pedidos360-rabbit1 rabbitmqctl -q cluster_status

# Las 6 colas con sus contadores
docker exec pedidos360-rabbit1 rabbitmqctl -q list_queues name messages policy

# Los 3 exchanges
docker exec pedidos360-rabbit1 rabbitmqctl -q list_exchanges name type | grep '^cmd\.'

# La replicación de cada cola (si 'replica' sale vacío, se perdió)
curl -s -u guest:guest "http://localhost:15672/api/queues/%2F?columns=name,policy,node,synchronised_slave_nodes"

# El consumidor, filtrado y legible
docker logs --since 5m pedidos360-notify 2>&1 | grep -iE "procesado OK|reintent|DLQ"
```

Y los dos scripts que hacen todo esto de una vez:

```bash
bash infra/verificar-cluster.sh    # clúster, colas, exchanges, bindings, réplicas
bash infra/politicas-retencion.sh --ver   # qué política aplica a cada cola
```

---

## 4. Orden de revisión antes de presentar

Diez minutos antes, en este orden. Si algo falla, falla acá y no delante del docente.

| # | Comprobación | Dónde | Está bien si |
|---|---|---|---|
| 1 | Los 9 contenedores | `docker compose ps` | 8 `Up`, `rabbit-init` `Exited (0)` |
| 2 | Los dos nodos | UI → Overview → Nodes | Los dos en verde, en ambas UI |
| 3 | Las 6 colas | UI → Queues | Las 6, en **cero** |
| 4 | La replicación | Clic en una cola → Mirrors | Nombra el otro nodo |
| 5 | Los 3 exchanges | UI → Exchanges | `direct`, `topic`, `direct` |
| 6 | CloudWatch | Log group `/pedidos360/compose` | 9 flujos, con actividad reciente |
| 7 | La aplicación | `https://<IP>/login` | Entra y lista pedidos |
| 8 | El flujo completo | Crear un pedido y aceptarlo | Las colas se mueven y vuelven a cero |

**Deja las colas en cero antes de empezar:**

```bash
bash infra/demo-dlq.sh --solo-purgar
```

Así los contadores se mueven desde cero delante del docente.

---

## 5. Si algo no cuadra

| Síntoma | Casi siempre es |
|---|---|
| La UI no abre (*timeout*) | Security Group: la regla de 15672/15673 atada a "Mi IP" |
| Un solo nodo en Overview | El otro se cayó, o no se formó el clúster |
| "Mirrors" vacío en las colas | Una política de mayor prioridad desplazó a `ha-all` |
| **Ready** sube y no baja | El consumidor está caído: `docker ps` y mira `notify` |
| **Unacked** sube y no baja | El consumidor recibe pero no confirma: mira sus logs |
| CloudWatch vacío | El stack se levantó sin la superposición, o falta el perfil de IAM |
| `docker logs` no devuelve nada | Raro: Docker guarda caché local. Si pasa, lee de CloudWatch |
