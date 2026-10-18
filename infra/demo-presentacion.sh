#!/usr/bin/env bash
# =============================================================================
#  Demo guiada de EP4: flujo completo con una falla real en el medio.
#
#  Responde las cuatro preguntas del docente, en este orden:
#
#    ACTO 1  Camino feliz: el productor publica, se enruta, el consumidor procesa.
#    ACTO 2  Se cae el CONSUMIDOR. Que pasa con los mensajes? (no se pierden)
#    ACTO 3  Falla el MENSAJE. Reintento y, si insiste, cola muerta (DLQ).
#    ACTO 4  Se cae un NODO del broker. La cola sobrevive por la replica. (opcional)
#
#  USO
#    bash infra/demo-presentacion.sh                     # actos 1 a 3, con pausas
#    bash infra/demo-presentacion.sh --con-caida-de-nodo # agrega el acto 4
#    bash infra/demo-presentacion.sh --sin-pausas        # de corrido, para ensayar
#
#  Se corre DESDE EL HOST del EC2 (el contenedor de rabbitmq no trae curl).
#  Requiere PEDIDOS360_SIMULATE_FAILURES=true en notify (ya viene en el compose).
#
#  Conviene tener abierto en el navegador http://<IP>:15672 (cola q.cmd.email)
#  para que el docente vea los contadores moverse en vivo mientras corre esto.
# =============================================================================
set -u

MGMT=${MGMT:-http://localhost:15672}
MGMT2=${MGMT2:-http://localhost:15673}
AUTH=${AUTH:-guest:guest}
RABBIT1=${RABBIT1:-pedidos360-rabbit1}
RABBIT2=${RABBIT2:-pedidos360-rabbit2}
NOTIFY=${NOTIFY:-pedidos360-notify}

PAUSAS=1
NODO=0
for arg in "$@"; do
  case "$arg" in
    --sin-pausas)         PAUSAS=0 ;;
    --con-caida-de-nodo)  NODO=1 ;;
    *) echo "Opcion desconocida: $arg"; exit 1 ;;
  esac
done

PRINCIPALES="q.cmd.email q.cmd.kitchen q.cmd.invoice"
DLQS="q.cmd.email.dlq q.cmd.kitchen.dlq q.cmd.invoice.dlq"

# ------------------------------------------------------------------ utilidades

acto() {
  echo
  echo "############################################################"
  echo "#  $*"
  echo "############################################################"
  echo
}
paso()  { echo; echo ">>> $*"; }
nota()  { echo "    $*"; }
pausa() { [ "$PAUSAS" = "1" ] || return 0; echo; read -r -p "    [Enter para continuar] " _; }

# Tabla de las 6 colas. Columna "LISTOS" = mensajes esperando consumidor.
colas() {
  local nodo=${1:-$RABBIT1}
  local salida q n
  salida=$(docker exec "$nodo" rabbitmqctl -q list_queues name messages 2>/dev/null)
  printf '    %-24s %8s\n' "COLA" "LISTOS"
  for q in $PRINCIPALES $DLQS; do
    n=$(echo "$salida" | awk -v q="$q" '$1==q {print $2}')
    printf '    %-24s %8s\n' "$q" "${n:-0}"
  done
}

# Publica un cuerpo arbitrario en un exchange con una routing key.
# $1 exchange  $2 routingKey  $3 archivo  $4 descripcion  [$5 url de gestion]
publicar() {
  local mgmt=${5:-$MGMT}
  local body
  body=$(python3 "$WORK/armar_body.py" "$2" "$3")
  printf '    %-46s ' "$4"
  curl -s -u "$AUTH" -H 'content-type: application/json' \
    -X POST "$mgmt/api/exchanges/%2F/$1/publish" --data "$body"
  echo
}

# Logs del consumidor, sin el ruido de Spring.  $1 patron  $2 ventana
logs() {
  docker logs --since "${2:-1m}" "$NOTIFY" 2>&1 \
    | grep -iE "$1" \
    | sed -E 's/.*(CommandProcessor|Consumer)[[:space:]]*:[[:space:]]*/    /' \
    | tail -20
  echo
}

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# --------------------------------------------------- helpers python auxiliares

cat > "$WORK/armar_body.py" <<'PY'
import json, sys
routing_key, archivo = sys.argv[1], sys.argv[2]
with open(archivo) as fh:
    payload = fh.read().strip()
print(json.dumps({"properties": {}, "routing_key": routing_key,
                  "payload": payload, "payload_encoding": "string"}))
PY

cat > "$WORK/ver_dlq.py" <<'PY'
import json, sys
try:
    msgs = json.load(sys.stdin)
except Exception:
    print("    (no se pudo leer la cola)")
    raise SystemExit
if not msgs:
    print("    (vacia)")
for m in msgs:
    try:
        cuerpo = json.loads(m["payload"])
        modo = cuerpo.get("payload", {}).get("failMode", "-")
        print("    eventId=%-20s failMode=%s" % (cuerpo.get("eventId", "?"), modo))
    except Exception:
        print("    (mensaje no-JSON: %s)" % m["payload"][:40])
PY

cat > "$WORK/ver_replicas.py" <<'PY'
import json, sys
for q in json.load(sys.stdin):
    replicas = q.get("synchronised_slave_nodes") or ["(sin replica)"]
    print("    %-24s maestro=%-18s replica=%s"
          % (q["name"], q["node"], ",".join(replicas)))
PY

# ---------------------------------------------------------------- los mensajes

cd "$WORK"

# Validos, uno por cada caso de uso (los 3 consumidores).
cat > ok-email.json <<'EOF'
{"type":"email.send","eventId":"demo-ok-email","timestamp":"2026-01-01T00:00:00Z","correlationId":"demo-feliz","payload":{"orderId":1001,"customerId":"cliente-demo","status":"ACEPTADO"}}
EOF
cat > ok-kitchen.json <<'EOF'
{"type":"kitchen.ticket","eventId":"demo-ok-kitchen","timestamp":"2026-01-01T00:00:00Z","correlationId":"demo-feliz","payload":{"orderId":1001,"items":2}}
EOF
cat > ok-invoice.json <<'EOF'
{"type":"invoice.gen","eventId":"demo-ok-invoice","timestamp":"2026-01-01T00:00:00Z","correlationId":"demo-feliz","payload":{"orderId":1001,"totalAmount":15990}}
EOF

# Los mismos, con otro eventId: se usan mientras el consumidor esta caido.
# (el cache de idempotencia descartaria un eventId repetido)
for n in 1 2 3; do
  sed "s/demo-ok-email/demo-caida-$n/" ok-email.json > "caida-$n.json"
done
sed 's/demo-ok-email/demo-por-rabbit2/' ok-email.json > por-rabbit2.json

# Con falla: transitorio (reintenta y despues DLQ) y no recuperable (DLQ directo).
cat > falla-transitoria.json <<'EOF'
{"type":"email.send","eventId":"demo-transitorio","timestamp":"2026-01-01T00:00:00Z","correlationId":"demo-falla","payload":{"orderId":2002,"customerId":"cliente-demo","status":"ACEPTADO","failMode":"transient"}}
EOF
cat > falla-poison.json <<'EOF'
{"type":"email.send","eventId":"demo-poison","timestamp":"2026-01-01T00:00:00Z","correlationId":"demo-falla","payload":{"orderId":2003,"customerId":"cliente-demo","status":"ACEPTADO","failMode":"poison"}}
EOF

# =============================================================================
acto "ACTO 0  -  Punto de partida limpio"

paso "Vaciando las 6 colas para que los contadores partan en cero"
for q in $PRINCIPALES $DLQS; do
  docker exec "$RABBIT1" rabbitmqctl -q purge_queue "$q" > /dev/null 2>&1
done
colas
nota ""
nota "3 colas de comandos + 3 colas muertas (DLQ) = las 6 colas del sistema."
pausa

# =============================================================================
acto "ACTO 1  -  Camino feliz"

nota "El productor (ms-pedidos360-orders) publica un comando por cada caso de"
nota "uso. El exchange lo enruta a su cola. El consumidor lo procesa y confirma"
nota "con ACK. Las colas quedan vacias porque nada se acumula."
echo

paso "Publicando los 3 comandos validos"
publicar cmd.topic  cmd.email.aceptado ok-email.json   "email.send     -> q.cmd.email"
publicar cmd.direct kitchen.ticket     ok-kitchen.json "kitchen.ticket -> q.cmd.kitchen"
publicar cmd.direct invoice.gen        ok-invoice.json "invoice.gen    -> q.cmd.invoice"

sleep 4
paso "Lo que hizo cada consumidor (las 3 funciones del sistema)"
logs "procesado OK|Notificando|Ticket de cocina|Factura generada" 1m

paso "Estado de las colas"
colas
nota ""
nota "Todo en cero: cada mensaje fue consumido y confirmado con ACK."
pausa

# =============================================================================
acto "ACTO 2  -  Se cae el CONSUMIDOR"

nota "PREGUNTA: si el consumidor se cae, se pierden los pedidos?"
nota "RESPUESTA: no. La cola NO se cae con el consumidor: vive en el broker y"
nota "retiene los mensajes. Eso es precisamente para lo que sirve una cola."
echo

paso "Bajando el consumidor:  docker stop $NOTIFY"
if docker stop "$NOTIFY" > /dev/null 2>&1; then
  nota "consumidor detenido"
else
  nota "AVISO: no se pudo detener $NOTIFY (revisa el nombre del contenedor)"
fi

paso "El productor sigue trabajando: publica 3 comandos sin consumidor vivo"
publicar cmd.topic cmd.email.aceptado caida-1.json "pedido 1 (sin consumidor)"
publicar cmd.topic cmd.email.aceptado caida-2.json "pedido 2 (sin consumidor)"
publicar cmd.topic cmd.email.aceptado caida-3.json "pedido 3 (sin consumidor)"

sleep 2
paso "Estado de las colas: mirar q.cmd.email"
colas
nota ""
nota "q.cmd.email tiene 3 mensajes LISTOS, esperando. No se perdio ninguno."
nota "El productor nunca supo que el consumidor estaba caido: esta desacoplado."
nota "En la UI de gestion esto es la columna 'Ready' subiendo."
pausa

paso "Levantando el consumidor:  docker start $NOTIFY"
docker start "$NOTIFY" > /dev/null 2>&1
nota "consumidor iniciado, esperando que arranque la JVM (25 s)..."
sleep 25

paso "Lo que proceso al reconectarse"
logs "procesado OK|Notificando" 40s

paso "Estado de las colas"
colas
nota ""
nota "q.cmd.email volvio a cero: al reconectarse consumio los 3 pendientes."
nota "Resultado: cero perdida de datos ante la caida de un consumidor."
pausa

# =============================================================================
acto "ACTO 3  -  Falla el MENSAJE: reenvio y cola muerta"

nota "Caso distinto: el consumidor esta vivo, pero un mensaje NO se puede"
nota "procesar. El consumidor usa ACK manual y decide explicitamente su destino:"
echo
nota "  Error transitorio    -> basicNack(requeue=true)    -> SE REENVIA a la cola"
nota "                          si vuelve a fallar         -> cola muerta (DLQ)"
nota "  Error no recuperable -> basicReject(requeue=false) -> cola muerta directo"
echo

paso "Publicando un mensaje con falla transitoria (se reintenta una vez)"
publicar cmd.topic cmd.email.aceptado falla-transitoria.json "failMode=transient"

paso "Publicando un mensaje con falla no recuperable (DLQ directo)"
publicar cmd.topic cmd.email.aceptado falla-poison.json      "failMode=poison"

sleep 8
paso "Decisiones del consumidor, con el motivo de cada una"
logs "reintent|dlq" 1m

paso "Estado de las colas"
colas
nota ""
nota "q.cmd.email.dlq tiene 2 mensajes: esa es LA COLA MUERTA."
nota "El reenvio del transitorio ocurrio, volvio a fallar, y termino ahi."
echo
nota "Quien los mando ahi fue el broker, no el codigo: la cola se declaro con"
nota "x-dead-letter-exchange=cmd.dead.dlx, y al rechazar con requeue=false el"
nota "broker re-enruta el mensaje a ese exchange."
pausa

paso "Contenido real de la cola muerta (se lee sin consumirlo)"
curl -s -u "$AUTH" -H 'content-type: application/json' \
  -X POST "$MGMT/api/queues/%2F/q.cmd.email.dlq/get" \
  --data '{"count":5,"ackmode":"ack_requeue_true","encoding":"auto"}' \
  | python3 "$WORK/ver_dlq.py"
nota ""
nota "ackmode=ack_requeue_true: se leen sin sacarlos, la cola queda intacta."
pausa

# =============================================================================
if [ "$NODO" = "1" ]; then
  acto "ACTO 4  -  Se cae un NODO del broker"

  nota "PREGUNTA: y si se cae la cola, es decir el nodo donde vive?"
  nota "RESPUESTA: por eso el cluster tiene 2 nodos y la politica ha-all replica"
  nota "las 6 colas en ambos. Si cae el nodo maestro, la replica es promovida."
  echo
  nota "AVISO: este acto detiene rabbit1. Es el mas vistoso y el mas riesgoso."
  nota "Ensayalo antes; si el tiempo apremia, explicalo con la tabla de replicas."
  pausa

  paso "Donde vive cada cola ahora (maestro y replica)"
  curl -s -u "$AUTH" "$MGMT/api/queues/%2F?columns=name,node,synchronised_slave_nodes" \
    | python3 "$WORK/ver_replicas.py"
  pausa

  paso "Bajando el primer nodo:  docker stop $RABBIT1"
  docker stop "$RABBIT1" > /dev/null 2>&1 && nota "rabbit1 detenido"
  sleep 15

  paso "El cluster visto desde el nodo que sigue en pie"
  docker exec "$RABBIT2" rabbitmqctl -q cluster_status 2>/dev/null \
    | grep -A4 -iE "running nodes" | sed 's/^/    /'

  paso "Las colas siguen existiendo, ahora con maestro en rabbit2"
  colas "$RABBIT2"

  paso "Y el sistema sigue aceptando mensajes por el segundo nodo"
  publicar cmd.topic cmd.email.aceptado por-rabbit2.json "email.send por rabbit2" "$MGMT2"
  sleep 5
  logs "procesado OK|Notificando" 30s
  nota "Los microservicios reconectaron solos: SPRING_RABBITMQ_ADDRESSES lista"
  nota "los dos nodos, asi que el cliente AMQP hace failover sin intervencion."
  pausa

  paso "Restaurando el nodo:  docker start $RABBIT1"
  docker start "$RABBIT1" > /dev/null 2>&1
  sleep 30
  docker exec "$RABBIT1" rabbitmqctl -q cluster_status 2>/dev/null \
    | grep -A4 -iE "running nodes" | sed 's/^/    /'
  nota "rabbit1 se reincorporo al cluster y re-sincronizo las replicas."
fi

# =============================================================================
acto "FIN  -  Resumen de lo demostrado"
nota "1. Camino feliz: productor -> exchange -> cola -> consumidor -> ACK"
nota "2. Cae el consumidor: la cola retiene, al volver consume, cero perdidas"
nota "3. Falla el mensaje: reenvio del transitorio y cola muerta con el motivo"
if [ "$NODO" = "1" ]; then
  nota "4. Cae un nodo: la replica asume y el cliente AMQP hace failover"
fi
echo
nota "Las 6 colas, los 3 consumidores y los 3 exchanges quedan visibles en la"
nota "UI de gestion; el detalle de cada decision, en los logs de $NOTIFY."
echo
