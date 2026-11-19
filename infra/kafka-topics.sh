#!/usr/bin/env bash
# =============================================================================
#  Creacion y administracion de los topicos de Pedidos360.
#
#  Los topicos se crean AQUI y no automaticamente: en el compose esta puesto
#  KAFKA_AUTO_CREATE_TOPICS_ENABLE=false a proposito. Si se crearan solos,
#  naceriian con las particiones y replicas por defecto sin que nadie se entere,
#  y el factor de replicacion es justo lo que hay que demostrar.
#
#    bash infra/kafka-topics.sh              # crea los dos topicos
#    bash infra/kafka-topics.sh --ver        # describe topicos y consumer groups
#    bash infra/kafka-topics.sh --lag        # solo el LAG de cada grupo
#    bash infra/kafka-topics.sh --demo-ttl   # baja la retencion a 60s (demo)
#    bash infra/kafka-topics.sh --borrar     # elimina los topicos
#
#  POR QUE 3 PARTICIONES Y 3 REPLICAS
#    3 particiones -> tres consumidores del mismo grupo pueden trabajar en
#                     paralelo, uno por particion. Con 1 particion, un segundo
#                     consumidor del mismo grupo se quedaria sin nada que hacer.
#    3 replicas    -> con min.insync.replicas=2 y acks=all, el cluster tolera
#                     la caida de UN broker sin perder escrituras.
#
#  POR QUE DOS POLITICAS DE LIMPIEZA DISTINTAS
#    orders.events  -> cleanup.policy=delete, 7 dias. Es un flujo de HECHOS:
#                      cada cambio de estado es un evento inmutable que caduca
#                      porque pasado ese plazo el dato vive en la base.
#    audit.timeline -> cleanup.policy=compact, 30 dias. Es un flujo de ESTADO:
#                      interesa el ultimo estado conocido de cada pedido, asi
#                      que Kafka conserva el ultimo valor de cada clave
#                      (orderId) y descarta los intermedios.
# =============================================================================
set -u

BROKER=${BROKER:-pedidos360-kafka1}
BOOTSTRAP=${BOOTSTRAP:-localhost:29092}

T_EVENTS=orders.events
T_TIMELINE=audit.timeline
# El DeadLetterPublishingRecoverer de Spring Kafka publica en "<topico>.DLT",
# asi que hace falta una por cada topico consumido. Como auto.create esta
# desactivado, si no existieran el envio a la DLT fallaria en silencio.
T_DLT=orders.events.DLT
T_DLT_TIMELINE=audit.timeline.DLT

PARTICIONES=3
REPLICAS=3

RET_EVENTS=604800000      # 7 dias
RET_TIMELINE=2592000000   # 30 dias
RET_DLT=1209600000        # 14 dias: un mensaje muerto se conserva para analisis

titulo() { echo; echo "=== $* ==="; }
nota()   { echo "    $*"; }

kt()  { docker exec "$BROKER" kafka-topics --bootstrap-server "$BOOTSTRAP" "$@"; }
kcg() { docker exec "$BROKER" kafka-consumer-groups --bootstrap-server "$BOOTSTRAP" "$@"; }
kcf() { docker exec "$BROKER" kafka-configs --bootstrap-server "$BOOTSTRAP" "$@"; }

ver() {
  titulo "Topicos"
  kt --list | sed 's/^/    /'

  for t in "$T_EVENTS" "$T_TIMELINE" "$T_DLT" "$T_DLT_TIMELINE"; do
    titulo "Detalle de $t"
    kt --describe --topic "$t" 2>/dev/null | sed 's/^/    /' || nota "(no existe)"
    nota ""
    nota "Configuracion no heredada:"
    kcf --describe --topic "$t" 2>/dev/null | sed 's/^/    /' || true
  done

  titulo "Consumer groups"
  kcg --list | sed 's/^/    /'
}

lag() {
  titulo "LAG por grupo"
  nota "CURRENT-OFFSET = por donde va el grupo | LOG-END-OFFSET = lo ultimo que hay"
  nota "LAG = lo que le falta por consumir. Si crece, el consumidor no da abasto."
  echo
  for g in $(kcg --list 2>/dev/null); do
    echo "    --- $g"
    kcg --describe --group "$g" 2>/dev/null | sed 's/^/    /'
    echo
  done
}

case "${1:-}" in
  --ver)  ver; exit 0 ;;
  --lag)  lag; exit 0 ;;

  --borrar)
    titulo "Eliminando los topicos"
    for t in "$T_EVENTS" "$T_TIMELINE" "$T_DLT" "$T_DLT_TIMELINE"; do
      kt --delete --topic "$t" 2>/dev/null && nota "$t eliminado" || nota "$t (no existia)"
    done
    exit 0
    ;;

  --demo-ttl)
    titulo "Retencion de $T_EVENTS a 60 segundos, SOLO para la demostracion"
    nota "Sirve para mostrar en vivo que la politica de retencion se aplica:"
    nota "publica eventos, espera, y los segmentos antiguos desaparecen."
    echo
    kcf --alter --topic "$T_EVENTS" --add-config retention.ms=60000,segment.ms=30000
    echo
    nota "CUANDO TERMINES, devuelvelo a 7 dias:"
    nota "  bash infra/kafka-topics.sh"
    exit 0
    ;;

  "") ;;
  *)  echo "Opcion desconocida: $1"; exit 1 ;;
esac

# -----------------------------------------------------------------------------
titulo "1) $T_EVENTS - flujo de hechos del negocio"
nota "cleanup.policy=delete, 7 dias. Cada cambio de estado de un pedido es un"
nota "evento inmutable. La clave del mensaje es el orderId, asi que todos los"
nota "eventos de un mismo pedido caen en la misma particion y se procesan EN"
nota "ORDEN; pedidos distintos van a particiones distintas y se paralelizan."
echo
kt --create --if-not-exists --topic "$T_EVENTS" \
   --partitions "$PARTICIONES" --replication-factor "$REPLICAS" \
   --config cleanup.policy=delete \
   --config retention.ms=$RET_EVENTS \
   --config min.insync.replicas=2

# -----------------------------------------------------------------------------
titulo "2) $T_TIMELINE - flujo de estado"
nota "cleanup.policy=compact. Interesa el ULTIMO estado conocido de cada pedido,"
nota "no el historico completo: Kafka conserva el ultimo valor de cada clave y"
nota "descarta los intermedios. Un consumidor que arranca de cero reconstruye"
nota "el estado actual leyendo el topico entero."
echo
kt --create --if-not-exists --topic "$T_TIMELINE" \
   --partitions "$PARTICIONES" --replication-factor "$REPLICAS" \
   --config cleanup.policy=compact \
   --config retention.ms=$RET_TIMELINE \
   --config min.cleanable.dirty.ratio=0.1 \
   --config segment.ms=60000 \
   --config min.insync.replicas=2

# -----------------------------------------------------------------------------
titulo "3) $T_DLT y $T_DLT_TIMELINE - mensajes que agotaron reintentos"
nota "Equivalente Kafka de la DLQ de RabbitMQ. Lo alimenta el"
nota "DeadLetterPublishingRecoverer de Spring Kafka cuando un evento falla"
nota "despues de los reintentos. 14 dias para poder analizarlo con calma."
echo
for dlt in "$T_DLT" "$T_DLT_TIMELINE"; do
  kt --create --if-not-exists --topic "$dlt" \
     --partitions "$PARTICIONES" --replication-factor "$REPLICAS" \
     --config cleanup.policy=delete \
     --config retention.ms=$RET_DLT \
     --config min.insync.replicas=2
done

# -----------------------------------------------------------------------------
ver

titulo "Listo"
nota "Kafka UI: http://localhost:8090"
nota ""
nota "Para la demostracion de LAG (vale 15% en EP6):"
nota "  1. docker stop pedidos360-notify     (cae un grupo consumidor)"
nota "  2. cambia estados de pedidos desde la app"
nota "  3. bash infra/kafka-topics.sh --lag  (el LAG de notify crece)"
nota "  4. docker start pedidos360-notify"
nota "  5. bash infra/kafka-topics.sh --lag  (vuelve a 0)"
echo
