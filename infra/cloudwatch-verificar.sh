#!/usr/bin/env bash
# =============================================================================
#  Verifica que el EC2 puede enviar logs a CloudWatch, ANTES de recrear el stack.
#
#  POR QUE ESTE PASO EXISTE
#  El driver de logging de Docker se resuelve al CREAR el contenedor. Si no puede
#  autenticarse contra CloudWatch, el contenedor NO ARRANCA: no es que pierda los
#  logs, es que falla. Recrear los 9 servicios a ciegas puede dejar el stack
#  caido. Este script lo prueba con UN contenedor desechable primero.
#
#   bash infra/cloudwatch-verificar.sh
#
#  Si las 4 comprobaciones pasan, recien entonces:
#   docker compose -f docker-compose.yml -f docker-compose.cloudwatch.yml up -d
# =============================================================================
set -u

REGION=${REGION:-us-east-1}
GRUPO=${GRUPO:-/pedidos360/compose}
FLUJO="prueba-$(date +%s)"

ok()    { echo "   [OK]    $*"; }
falla() { echo "   [FALLA] $*"; }
info()  { echo "           $*"; }
titulo(){ echo; echo "=== $* ==="; }

PROBLEMAS=0

# -----------------------------------------------------------------------------
titulo "1) La instancia tiene un rol de IAM?"

TOKEN=$(curl -s -m 3 -X PUT "http://169.254.169.254/latest/api/token" \
          -H "X-aws-ec2-metadata-token-ttl-seconds: 60" 2>/dev/null)
if [ -z "$TOKEN" ]; then
  falla "no se pudo hablar con el servicio de metadatos de la instancia."
  info  "Esto solo corre DENTRO del EC2, no en tu equipo."
  exit 1
fi

ROL=$(curl -s -m 3 -H "X-aws-ec2-metadata-token: $TOKEN" \
        "http://169.254.169.254/latest/meta-data/iam/security-credentials/" 2>/dev/null)

if [ -z "$ROL" ] || echo "$ROL" | grep -qi "not found\|<?xml"; then
  falla "la instancia NO tiene perfil de IAM asignado."
  info  "Asignalo en la consola, sin reiniciar la instancia:"
  info  "  EC2 -> la instancia -> Acciones -> Seguridad -> Modificar rol de IAM"
  info  "  -> elegir 'LabInstanceProfile' -> Guardar"
  info  "Despues espera ~1 minuto y vuelve a correr este script."
  exit 1
fi
ok "rol asignado: $ROL"

# -----------------------------------------------------------------------------
titulo "2) El rol puede escribir en CloudWatch Logs?"

if ! command -v aws > /dev/null 2>&1; then
  info "AWS CLI no esta instalado en la instancia; se omite esta comprobacion."
  info "No es bloqueante: el paso 3 prueba lo mismo por la via que importa."
else
  if aws logs describe-log-groups --log-group-name-prefix "$GRUPO" \
       --region "$REGION" > /dev/null 2>&1; then
    ok "el rol puede consultar grupos de registro"
  else
    falla "el rol no puede consultar CloudWatch Logs (permisos de LabRole)"
    PROBLEMAS=$((PROBLEMAS + 1))
  fi

  # Pre-crear el grupo evita depender de logs:CreateLogGroup en cada contenedor.
  if aws logs create-log-group --log-group-name "$GRUPO" --region "$REGION" > /dev/null 2>&1; then
    ok "grupo de registro $GRUPO creado"
  else
    info "el grupo $GRUPO ya existia, o no hay permiso para crearlo (sigue al paso 3)"
  fi
fi

# -----------------------------------------------------------------------------
titulo "3) Docker puede usar el driver awslogs? (la prueba que de verdad importa)"

info "Lanzando un contenedor desechable con el mismo driver del compose..."
SALIDA=$(docker run --rm \
  --log-driver=awslogs \
  --log-opt "awslogs-region=$REGION" \
  --log-opt "awslogs-group=$GRUPO" \
  --log-opt "awslogs-create-group=true" \
  --log-opt "awslogs-stream=$FLUJO" \
  public.ecr.aws/docker/library/alpine:3.20 \
  echo "prueba de cloudwatch desde pedidos360" 2>&1)
CODIGO=$?

if [ $CODIGO -eq 0 ]; then
  ok "el contenedor arranco con el driver awslogs"
  info "flujo de registro: $FLUJO"
else
  falla "el contenedor NO arranco con el driver awslogs. Esto es bloqueante."
  echo "$SALIDA" | sed 's/^/           /'
  echo
  info "NO levantes el stack con la superposicion de CloudWatch: los 9"
  info "contenedores fallarian igual. Usa el compose normal:"
  info "  docker compose up -d"
  exit 1
fi

# -----------------------------------------------------------------------------
titulo "4) El mensaje llego a CloudWatch?"

if command -v aws > /dev/null 2>&1; then
  sleep 6
  MSG=$(aws logs get-log-events \
          --log-group-name "$GRUPO" \
          --log-stream-name "$FLUJO" \
          --region "$REGION" \
          --query 'events[0].message' --output text 2>/dev/null)
  if [ -n "$MSG" ] && [ "$MSG" != "None" ]; then
    ok "mensaje recibido: $MSG"
  else
    falla "el contenedor arranco pero el mensaje no aparece todavia."
    info "Puede ser latencia. Revisa a mano en la consola:"
    info "  CloudWatch -> Grupos de registro -> $GRUPO -> $FLUJO"
    PROBLEMAS=$((PROBLEMAS + 1))
  fi
else
  info "sin AWS CLI no se puede leer de vuelta; revisa en la consola:"
  info "  CloudWatch -> Grupos de registro -> $GRUPO -> $FLUJO"
fi

# -----------------------------------------------------------------------------
titulo "Resultado"

if [ "$PROBLEMAS" = "0" ]; then
  echo "   Todo en orden. Ahora el despliegue por etapas, NO de golpe:"
  echo
  echo "   # 1. Un solo servicio primero (audit es el menos critico de la demo)"
  echo "   docker compose -f docker-compose.yml -f docker-compose.cloudwatch.yml \\"
  echo "     up -d --force-recreate audit"
  echo
  echo "   # 2. Comprobar que quedo arriba antes de seguir"
  echo "   docker compose ps audit"
  echo
  echo "   # 3. Si esta arriba, el resto"
  echo "   docker compose -f docker-compose.yml -f docker-compose.cloudwatch.yml up -d"
  echo
  echo "   Si algo sale mal, volver atras son 20 segundos:"
  echo "   docker compose up -d --force-recreate"
else
  echo "   Hubo $PROBLEMAS advertencia(s). El driver funciona, pero revisa arriba"
  echo "   antes de recrear los 9 contenedores."
fi
echo
