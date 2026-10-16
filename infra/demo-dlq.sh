#!/usr/bin/env bash
# Demo de la DLQ: publica los 4 casos de error directo a los exchanges y muestra
# como los trata el consumidor. Sirve para ensayar el bloque 4 de la presentacion
# (el indicador de ACK y manejo de errores, que vale 20%).
#
#   bash infra/demo-dlq.sh            -> publica los 4 casos y muestra el resultado
#   bash infra/demo-dlq.sh --purgar   -> vacia las 6 colas primero (contadores en cero)
#   bash infra/demo-dlq.sh --solo-purgar
#
# Requiere PEDIDOS360_SIMULATE_FAILURES=true en notify (ya viene asi en el compose).
# Se corre desde el host del EC2: el contenedor de rabbitmq no trae curl.
set -u

MGMT=${MGMT:-http://localhost:15672}
AUTH=${AUTH:-guest:guest}
COLAS="q.cmd.email q.cmd.kitchen q.cmd.invoice q.cmd.email.dlq q.cmd.kitchen.dlq q.cmd.invoice.dlq"

purgar() {
  echo "== Vaciando las 6 colas =="
  for q in $COLAS; do
    docker exec pedidos360-rabbit1 rabbitmqctl -q purge_queue "$q" > /dev/null 2>&1 \
      && echo "   $q vaciada" || echo "   $q (ya estaba vacia)"
  done
  echo
}

case "${1:-}" in
  --purgar)      purgar ;;
  --solo-purgar) purgar; exit 0 ;;
esac

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
cd "$WORK"

cat > transitorio.json <<'EOF'
{"type":"email.send","eventId":"demo-transitorio","timestamp":"2026-01-01T00:00:00Z","correlationId":"demo","payload":{"orderId":999,"customerId":"cliente-demo","status":"ACEPTADO","failMode":"transient"}}
EOF
cat > poison.json <<'EOF'
{"type":"email.send","eventId":"demo-poison","timestamp":"2026-01-01T00:00:00Z","correlationId":"demo","payload":{"orderId":999,"customerId":"cliente-demo","status":"ACEPTADO","failMode":"poison"}}
EOF
cat > sin-items.json <<'EOF'
{"type":"kitchen.ticket","eventId":"demo-sin-items","timestamp":"2026-01-01T00:00:00Z","correlationId":"demo","payload":{"orderId":999}}
EOF
printf 'esto no es json' > no-json.txt

publicar() { # $1 exchange  $2 routingKey  $3 archivo  $4 descripcion
  python3 - "$2" "$3" > body.json <<'PY'
import json, sys
print(json.dumps({"properties": {}, "routing_key": sys.argv[1],
                  "payload": open(sys.argv[2]).read().strip(),
                  "payload_encoding": "string"}))
PY
  printf '   %-42s ' "$4"
  curl -s -u "$AUTH" -H 'content-type: application/json' \
    -X POST "$MGMT/api/exchanges/%2F/$1/publish" --data @body.json
  echo
}

echo "== Publicando los 4 casos de error =="
publicar cmd.topic  cmd.email.aceptado transitorio.json "1) transitorio -> reintento y luego DLQ"
publicar cmd.topic  cmd.email.aceptado poison.json      "2) no recuperable -> DLQ directo"
publicar cmd.direct kitchen.ticket     sin-items.json   "3) payload sin 'items' -> DLQ"
publicar cmd.direct invoice.gen        no-json.txt      "4) cuerpo no-JSON -> mensaje invalido"

echo
echo "== Esperando al consumidor =="
sleep 6

echo
echo "== Como lo trato el consumidor =="
docker logs --since 1m pedidos360-notify 2>&1 \
  | grep -iE "dlq|reintent" | sed 's/.*CommandProcessor *: /   /'

echo
echo "== Estado de las colas =="
docker exec pedidos360-rabbit1 rabbitmqctl -q list_queues name messages 2>/dev/null | sed 's/^/   /'

echo
echo "== Replicas de las DLQ en el segundo nodo =="
curl -s -u "$AUTH" "$MGMT/api/queues/%2F?columns=name,node,synchronised_slave_nodes" \
  | python3 -c "import json,sys
for q in json.load(sys.stdin):
    if q['name'].endswith('.dlq'):
        print(f\"   {q['name']:<22} maestro={q['node']:<16} replica={q.get('synchronised_slave_nodes')}\")"
