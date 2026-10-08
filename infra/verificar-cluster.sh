#!/usr/bin/env bash
# Verificacion del cluster RabbitMQ de Pedidos360 (para la demo de EP4).
# Uso:  bash infra/verificar-cluster.sh
# Corre desde el host del EC2 (o local con Docker Desktop encendido).
set -u

RABBIT1=${RABBIT1:-pedidos360-rabbit1}
MGMT=${MGMT:-http://localhost:15672}
AUTH=${AUTH:-guest:guest}

titulo() { echo; echo "=============== $* ==============="; }

titulo "1) Estado del cluster (debe listar rabbit@rabbit1 y rabbit@rabbit2)"
docker exec "$RABBIT1" rabbitmqctl cluster_status

titulo "2) Nodos via API de gestion"
curl -sS -u "$AUTH" "$MGMT/api/nodes?columns=name,running,mem_used"
echo

titulo "3) Politica de replica"
curl -sS -u "$AUTH" "$MGMT/api/policies"
echo

titulo "4) Las 3 colas + sus 3 DLQ (columna 'slave_nodes' = replicas)"
docker exec "$RABBIT1" rabbitmqctl list_queues name messages policy

titulo "5) Exchanges (deben estar cmd.direct=direct, cmd.topic=topic, cmd.dead.dlx=direct)"
docker exec "$RABBIT1" rabbitmqctl list_exchanges name type | grep -E '^cmd\.' || echo "  (aun no declarados: arranca orders o notify)"

titulo "6) Bindings de las colas de comandos"
docker exec "$RABBIT1" rabbitmqctl list_bindings source_name destination_name routing_key | grep -E '^cmd\.' || true

titulo "7) Replicacion por cola (ha-mode: all => 1 replica en el otro nodo)"
curl -sS -u "$AUTH" "$MGMT/api/queues/%2F?columns=name,node,slave_nodes,synchronised_slave_nodes"
echo
