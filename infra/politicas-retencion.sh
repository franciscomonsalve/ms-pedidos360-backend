#!/usr/bin/env bash
# =============================================================================
#  Políticas de retención (TTL y longitud máxima) sobre las colas existentes.
#
#  POR QUE UNA POLITICA Y NO ARGUMENTOS DE LA COLA
#  La guía del docente declara el TTL como argumento de la cola:
#      QueueBuilder.durable(...).withArgument("x-message-ttl", 30000)
#  Eso funciona, pero los argumentos de una cola son INMUTABLES: cambiarlos
#  obliga a borrar y recrear la cola, o el arranque falla con PRECONDITION_FAILED.
#  Una politica hace lo mismo sobre las colas YA CREADAS, sin tocar el codigo ni
#  perder mensajes, y se quita con un solo comando.
#
#  CUIDADO CON EL TTL DE LAS COLAS PRINCIPALES
#  Un TTL corto (los 30 s de la guía) ROMPE la demo de "se cae el consumidor":
#  los mensajes acumulados expirarian hacia la DLQ en vez de ser consumidos al
#  levantar el consumidor. Por eso aqui el TTL de las principales es de 10 min.
#
#   bash infra/politicas-retencion.sh            # aplica retencion (DLQ 24 h, principales 10 min)
#   bash infra/politicas-retencion.sh --ver      # muestra las politicas vigentes
#   bash infra/politicas-retencion.sh --demo-ttl # TTL de 15 s SOLO para mostrar la expiracion
#   bash infra/politicas-retencion.sh --quitar   # vuelve todo atras
#
#  Se corre desde el host del EC2.
# =============================================================================
set -u

RABBIT1=${RABBIT1:-pedidos360-rabbit1}

# Prioridades: la politica de retencion (2) gana sobre ha-all (1) donde se
# solapan, y la de demo (3) gana sobre ambas mientras este puesta.
PRIO_RETENCION=2
PRIO_DEMO=3

titulo() { echo; echo "=== $* ==="; }
nota()   { echo "    $*"; }

rmqctl() { docker exec "$RABBIT1" rabbitmqctl "$@"; }

ver() {
  titulo "Políticas vigentes"
  rmqctl -q list_policies 2>/dev/null | sed 's/^/    /' || nota "(no se pudo consultar)"
  titulo "Qué política aplica a cada cola"
  rmqctl -q list_queues name policy messages 2>/dev/null | sed 's/^/    /'
}

case "${1:-}" in
  --ver)
    ver
    exit 0
    ;;

  --quitar)
    titulo "Quitando las políticas de retención"
    for p in retencion-principales retencion-dlq demo-ttl; do
      if rmqctl -q clear_policy "$p" > /dev/null 2>&1; then
        nota "$p eliminada"
      else
        nota "$p (no existía)"
      fi
    done
    nota ""
    nota "La política ha-all de replicación NO se toca: sigue vigente."
    ver
    exit 0
    ;;

  --demo-ttl)
    titulo "TTL de 15 segundos sobre q.cmd.email, solo para la demostración"
    nota "Con esto un mensaje que nadie consuma expira y el broker lo manda a"
    nota "la DLQ por 'expire', que es el segundo de los tres motivos de muerte."
    echo
    rmqctl set_policy demo-ttl '^q\.cmd\.email$' \
      '{"message-ttl":15000,"dead-letter-exchange":"cmd.dead.dlx","dead-letter-routing-key":"email.send"}' \
      --priority "$PRIO_DEMO" --apply-to queues
    echo
    nota "Ahora, para mostrarlo:"
    nota "  1. docker stop pedidos360-notify        (que nadie consuma)"
    nota "  2. publica un mensaje a q.cmd.email"
    nota "  3. espera 20 s y mira q.cmd.email.dlq: el mensaje llegó solo,"
    nota "     sin que ningún consumidor lo rechazara"
    nota "  4. docker start pedidos360-notify"
    echo
    nota "CUANDO TERMINES, QUITALA o romperá la demo de la caída del consumidor:"
    nota "  bash infra/politicas-retencion.sh --quitar"
    exit 0
    ;;

  "")
    ;;

  *)
    echo "Opción desconocida: $1"
    exit 1
    ;;
esac

# -----------------------------------------------------------------------------
titulo "1) Retención en las 3 colas muertas: 24 horas"

nota "Es el valor de la guía. Los mensajes fallidos se conservan un día para"
nota "análisis y después el broker los descarta solo, sin intervención."
echo
rmqctl set_policy retencion-dlq '^q\.cmd\..*\.dlq$' \
  '{"message-ttl":86400000,"max-length":1000}' \
  --priority "$PRIO_RETENCION" --apply-to queues

# -----------------------------------------------------------------------------
titulo "2) Retención en las 3 colas principales: 10 minutos y 1000 mensajes"

nota "La guía usa 30 s. Aquí son 10 min a propósito: con 30 s, los mensajes"
nota "acumulados durante la demo de 'se cae el consumidor' expirarían antes de"
nota "que lo levantes, y esa demo dejaría de funcionar."
echo
nota "max-length=1000 habilita el tercer motivo de muerte ('maxlen'): si la cola"
nota "se llena, el mensaje más antiguo se va a la DLQ en vez de perderse."
echo
rmqctl set_policy retencion-principales '^q\.cmd\.(email|kitchen|invoice)$' \
  '{"message-ttl":600000,"max-length":1000,"overflow":"reject-publish-dlx"}' \
  --priority "$PRIO_RETENCION" --apply-to queues

# -----------------------------------------------------------------------------
ver

titulo "Listo"
nota "Los tres motivos por los que un mensaje llega a la DLQ quedan cubiertos:"
nota "  reject  -> el consumidor lo rechaza          (ya estaba, en CommandProcessor)"
nota "  expire  -> se le vence el TTL                (nuevo, por política)"
nota "  maxlen  -> la cola alcanza su límite         (nuevo, por política)"
echo
nota "Para volver atrás: bash infra/politicas-retencion.sh --quitar"
echo
