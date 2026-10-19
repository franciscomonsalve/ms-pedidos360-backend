---
name: cambio-de-ip
description: Checklist para cuando cambia la IP pública del EC2 de Pedidos360 (el laboratorio de AWS Academy se apaga solo y la IP cambia en cada sesión). Úsalo cuando el usuario diga "nueva IP", "cambió la IP", pase una dirección IP suelta, o reporte que el login falla, que el frontend no carga datos, o que algo dejó de funcionar después de reiniciar el laboratorio.
---

# Cambio de IP del EC2 de Pedidos360

El laboratorio de AWS Academy detiene la instancia al cerrar la sesión, y al volver a
encenderla **la IP pública cambia**. Hay **cuatro** lugares donde quedó escrita. Si falta uno,
el síntoma no siempre apunta al culpable.

> **Lo primero:** pídele al usuario la IP nueva si no la dio. Todo lo de abajo la necesita.

## Conexión

```bash
cd /c/Users/Gene/Desktop/PEDIDOS360
ssh -i labsuser.pem -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
  -o LogLevel=ERROR ec2-user@<IP_NUEVA>
```

La clave es `labsuser.pem`, en la carpeta del proyecto. El usuario es `ec2-user`.
**Nunca pidas ni aceptes que peguen la clave privada en el chat**; léela del disco.

---

## Los cuatro lugares

### 1. Security Group — lo hace el usuario, en la consola

Puertos **443** (frontend), **8080** (BFF), **15672** y **15673** (UI de los dos nodos de
RabbitMQ), más el **22** para SSH.

> Las reglas suelen quedar atadas a **"Mi IP"**, y la IP del usuario también cambia. Si algo da
> *timeout*, es esto. Que las ponga en `0.0.0.0/0` para la demo.

Comprobación desde fuera:

```bash
for p in 22 443 8080 15672 15673; do
  (timeout 8 bash -c "exec 3<>/dev/tcp/<IP_NUEVA>/$p" 2>/dev/null \
    && echo "   puerto $p ABIERTO" || echo "   puerto $p cerrado")
done
```

### 2. API Gateway — lo hace el usuario, en la consola

Las **dos integraciones** deben apuntar a la IP nueva **con el `:8080`**. Olvidar el puerto es
el error clásico: el preflight devuelve **`301` con `Server: nginx`** en vez de llegar al BFF.

Comprobación (desde el EC2, que tiene credenciales de `LabRole`):

```bash
aws apigatewayv2 get-integrations --api-id 33kszs7hhl --region us-east-1 \
  --query 'Items[*].[IntegrationId,IntegrationUri]' --output text
```

Debe listar, con la IP nueva y el `:8080`:

```
http://<IP>:8080/api/users/register
http://<IP>:8080/api/{proxy}
```

Y el **CORS del propio gateway** (`AllowOrigins`) también lleva la IP:

```bash
aws apigatewayv2 get-api --api-id 33kszs7hhl --region us-east-1 \
  --query 'CorsConfiguration.AllowOrigins' --output text
```

Las tres rutas son `OPTIONS /api/{proxy+}` (sin autorización), `ANY /api/{proxy+}` (JWT) y
`POST /api/users/register` (sin autorización).

### 3. CORS del BFF — lo puedes hacer tú por SSH

```bash
cd ~/ms-pedidos360-backend/infra
cp .env .env.bak-$(date +%Y%m%d-%H%M)
sed -i 's|^CORS_ALLOWED_ORIGINS=.*|CORS_ALLOWED_ORIGINS=https://<IP_NUEVA>|' .env
grep '^CORS_ALLOWED_ORIGINS' .env
```

Hay que **recrear el BFF** para que lo tome:

```bash
docker compose -f docker-compose.yml -f docker-compose.cloudwatch.yml \
  up -d --force-recreate --no-deps bff
```

### 4. El frontend y Entra ID — el que más se olvida

El frontend es **Angular ya compilado**: la IP está **escrita dentro del archivo JavaScript**,
no se lee de ninguna configuración. Por eso sigue mandando la IP vieja a Microsoft aunque todo
lo demás esté bien.

**Síntoma:** al iniciar sesión, Microsoft responde

```
AADSTS50011: The redirect URI 'https://<IP_VIEJA>/auth/callback' specified in the request
does not match the redirect URIs configured for the application
```

**a) Corregir el bundle** (por SSH). No hardcodees el nombre del archivo, búscalo:

```bash
D=~/frontend-prebuilt/dist/frontend-pedidos360/browser
F=$(grep -rl 'redirectUri:"https://' "$D" | head -1)
echo "archivo: $F"
cp "$F" "$F.bak-$(date +%Y%m%d-%H%M)"
sed -i 's|https://<IP_VIEJA>|https://<IP_NUEVA>|g' "$F"
grep -o 'redirectUri:"[^"]*"' "$F"
grep -o 'postLogoutRedirectUri:"[^"]*"' "$F"
```

Y reconstruir la imagen:

```bash
cd ~/ms-pedidos360-backend/infra
docker compose -f docker-compose.yml -f docker-compose.cloudwatch.yml \
  up -d --build --force-recreate --no-deps frontend
```

> En `dist/` hay una carpeta `browser.bak-*` con IPs de sesiones anteriores. **No la toques**:
> nginx no la sirve y cambiarla no hace nada.

**b) Registrar la URI en Entra ID** — esto solo lo puede hacer el usuario:

> Portal de Azure → **Entra ID** → **App registrations** → la app del **frontend**
> (`faf7a202-e7db-49d8-89f1-0b32d5f1b4d2`, distinta de la de la API) → **Authentication** →
> **Redirect URIs** → agregar `https://<IP_NUEVA>/auth/callback`

Que **no borre las anteriores**: Entra ID admite varias y tener las viejas no estorba.

---

## Levantar el stack

Si las imágenes de Pedidos360 se perdieron (pasa cuando se recrea la instancia), hay que
reconstruir, y tarda 10-15 minutos:

```bash
cd ~/ms-pedidos360-backend/infra
nohup setsid docker compose up -d --build > /tmp/build.log 2>&1 < /dev/null &
```

### Trampa: los dos nodos de RabbitMQ se bloquean mutuamente

Al recrear **ambos** nodos a la vez, `rabbit1` arranca, ve en sus datos que está en clúster con
`rabbit2` y lo espera para sincronizar Mnesia (`Waiting for Mnesia tables...`). Pero `rabbit2`
tiene `depends_on: rabbit1 condition: service_healthy`. Se bloquean y **todo el stack queda en
`Created`**.

Salida:

```bash
docker compose -f docker-compose.yml -f docker-compose.cloudwatch.yml up -d --no-deps rabbit2
sleep 60
docker compose -f docker-compose.yml -f docker-compose.cloudwatch.yml up -d
```

### Si el repo del EC2 está atrasado

```bash
cd ~/ms-pedidos360-backend && git pull --ff-only origin main
```

---

## Verificación final, en orden

```bash
# 1. Los 8 contenedores arriba y los dos nodos healthy
docker ps -a --filter name=pedidos360 --format "{{.Names}}\t{{.Status}}" | sort

# 2. El clúster formado
docker exec pedidos360-rabbit1 rabbitmqctl -q cluster_status | grep -A4 -i "running nodes"

# 3. Las 6 colas CON réplica (si 'replica' sale vacío, algo rompió ha-all)
curl -s -u guest:guest "http://localhost:15672/api/queues/%2F?columns=name,policy,node,synchronised_slave_nodes"

# 4. La app responde
curl -sk -o /dev/null -w "frontend %{http_code}\n" https://localhost/
curl -s -o /dev/null -w "bff %{http_code}\n" http://localhost:8080/actuator/health
```

Y desde fuera, la cadena completa del gateway:

```bash
API="https://33kszs7hhl.execute-api.us-east-1.amazonaws.com"
# Preflight: 200 CON access-control-allow-origin (si da 301 de nginx, falta el :8080)
curl -s -i -X OPTIONS "$API/api/orders" -H "Origin: https://<IP_NUEVA>" \
  -H "Access-Control-Request-Method: GET" | grep -i "^HTTP\|access-control-allow-origin"
# Sin token: 401 del autorizador JWT
curl -s -o /dev/null -w "%{http_code}\n" "$API/api/orders"
```

> El gateway en frío puede devolver el primer preflight sin la cabecera CORS. **Repite antes de
> dar por roto nada**: a la segunda o tercera sale bien.

**Lo último lo prueba el usuario**: entrar a `https://<IP_NUEVA>/login`, iniciar sesión y crear
un pedido. Es lo único que necesita la cuenta de Entra ID.

---

## Diagnóstico por síntoma

| Síntoma | Casi siempre es |
|---|---|
| *Timeout* al abrir la app o las UIs de RabbitMQ | Security Group (regla atada a "Mi IP") |
| Preflight da **301** con `Server: nginx` | A una integración de API Gateway le falta el **`:8080`** |
| `AADSTS50011` al iniciar sesión | El `.js` del frontend y/o el redirect URI en Entra ID |
| El frontend carga pero no trae datos, y el preflight no trae `access-control-allow-origin` | `CORS_ALLOWED_ORIGINS` del BFF, o `AllowOrigins` del gateway |
| El stack entero en `Created` y `rabbit1` *unhealthy* | El bloqueo mutuo de los dos nodos |
| Los contenedores no arrancan tras activar CloudWatch | Falta el perfil de IAM `LabInstanceProfile` |

## Lo que NO cambia con la IP

El ID del API Gateway (`33kszs7hhl`), el endpoint `https://33kszs7hhl.execute-api.us-east-1.amazonaws.com`
—que va compilado en el frontend y está bien así—, el tenant de Entra ID, los client IDs, y el
endpoint de la base RDS.
