#!/usr/bin/env bash
# Construye las 5 imágenes del backend + el frontend y las empuja a Amazon ECR.
#
# ECS no construye imágenes: las descarga de un registro. Por eso este paso no existía
# con docker-compose, que las construía en la propia máquina.
#
#   bash infra/ecs/build-and-push.sh
#
# Requiere: AWS CLI con credenciales válidas, Docker, y haber compilado antes el
# frontend (su Dockerfile.prebuilt solo empaqueta el dist/ ya generado).
set -euo pipefail

REGION=${REGION:-us-east-1}
SERVICIOS="orders notify rabbit-admin audit bff"
FRONT_DIR=${FRONT_DIR:-$HOME/frontend-prebuilt}

ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
REGISTRO="$ACCOUNT.dkr.ecr.$REGION.amazonaws.com"
echo "Registro: $REGISTRO"
echo

echo "== 1. Repositorios en ECR =="
for s in $SERVICIOS frontend; do
  if aws ecr describe-repositories --repository-names "pedidos360-$s" --region "$REGION" > /dev/null 2>&1; then
    echo "   pedidos360-$s ya existe"
  else
    aws ecr create-repository --repository-name "pedidos360-$s" --region "$REGION" > /dev/null
    echo "   pedidos360-$s creado"
  fi
done
echo

echo "== 2. Autenticando Docker contra ECR =="
aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "$REGISTRO"
echo

echo "== 3. Backend: construir y empujar =="
cd "$(dirname "$0")/../.."          # raíz del repo
for s in $SERVICIOS; do
  echo "--- $s ---"
  docker build -f "ms-pedidos360-$s/Dockerfile" -t "pedidos360-$s:latest" .
  docker tag "pedidos360-$s:latest" "$REGISTRO/pedidos360-$s:latest"
  docker push "$REGISTRO/pedidos360-$s:latest"
done
echo

echo "== 4. Frontend: construir y empujar =="
if [ -d "$FRONT_DIR" ]; then
  docker build -f "$FRONT_DIR/Dockerfile.prebuilt" -t pedidos360-frontend:latest "$FRONT_DIR"
  docker tag pedidos360-frontend:latest "$REGISTRO/pedidos360-frontend:latest"
  docker push "$REGISTRO/pedidos360-frontend:latest"
else
  echo "   AVISO: no existe $FRONT_DIR — se omite el frontend."
  echo "   Compila el dist/ en tu equipo y súbelo antes de ejecutar esto."
fi
echo

echo "== Listo. URIs para la task definition =="
for s in $SERVICIOS frontend; do
  echo "   $REGISTRO/pedidos360-$s:latest"
done
