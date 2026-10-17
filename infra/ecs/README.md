# Despliegue en Amazon ECS

Migración del despliegue de `docker-compose` a **Amazon ECS con tipo de lanzamiento EC2**,
reutilizando la misma instancia.

> **El código de la aplicación no cambia.** La topología de mensajería, los consumidores, el
> microservicio administrador, los roles y Entra ID quedan exactamente igual. Esto es un cambio de
> empaquetado y despliegue.

---

## La decisión de diseño que importa

Un clúster de RabbitMQ necesita que los dos nodos **se resuelvan mutuamente por nombre, de forma
estable**. Con `docker-compose` eso venía gratis: su red interna resolvía `rabbit1` y `rabbit2`.

ECS no ofrece un equivalente directo:

| Modo de red | Por qué no sirve aquí |
|---|---|
| `bridge` | Los `links` son unidireccionales y no admiten ciclos; los dos nodos necesitan verse |
| `awsvpc` | No permite fijar el `hostname` del contenedor, y cada tarea consume una ENI (una `t3.medium` admite 3) |

La solución es el método que documenta RabbitMQ para **varios nodos en una misma máquina**:
`networkMode: host`, y los nodos se distinguen por **`RABBITMQ_NODENAME` y sus puertos**, no por el
hostname.

| | Nodo 1 | Nodo 2 |
|---|---|---|
| Nombre de nodo | `rabbit1@localhost` | `rabbit2@localhost` |
| AMQP | 5672 | 5673 |
| Gestión | 15672 | 15673 |
| Distribución Erlang | 25672 | 25673 |

Ambos resuelven `localhost`, que siempre existe, y comparten el `epmd` del host en el 4369.

**Efecto secundario favorable:** como la red es la del host, el BFF sigue escuchando en el **8080**
de la instancia. **No hay que tocar API Gateway ni el Security Group.**

---

## Lo que cambia respecto del compose

| | docker-compose | ECS |
|---|---|---|
| Imágenes | Se construyen en el EC2 | Se publican en **ECR** |
| Secretos | `infra/.env` | **SSM Parameter Store** |
| Resolución entre servicios | Por nombre de contenedor (`http://orders:8081`) | `http://localhost:8081` (red del host) |
| Dirección de RabbitMQ | `rabbit1:5672,rabbit2:5672` | `localhost:5672,localhost:5673` |
| Logs | `docker logs` | **CloudWatch Logs**, grupo `/ecs/pedidos360` |
| Arranque | `docker compose up -d` | ECS coloca la tarea en la instancia |

---

## Pasos

### 1. Preparar la instancia como *container instance*

La AMI actual (Amazon Linux 2023) **no trae el agente de ECS**, y la instancia **no tiene rol de
IAM**, que ECS exige para registrarse en el clúster.

**a) Asignar el perfil de instancia.** En la consola: EC2 → la instancia → Acciones → Seguridad →
**Modificar rol de IAM** → elegir `LabInstanceProfile` (el que ofrece AWS Academy). No requiere
reiniciar.

**b) Instalar y configurar el agente**, por SSH:

```bash
sudo dnf install -y ecs-init
echo "ECS_CLUSTER=pedidos360" | sudo tee /etc/ecs/ecs.config
sudo systemctl enable --now ecs
# Comprobar que se registró (debe responder con el ARN del clúster):
curl -s http://localhost:51678/v1/metadata
```

> Si `dnf install ecs-init` no encuentra el paquete, la alternativa es lanzar una instancia nueva
> con una **AMI optimizada para ECS**, que lo trae preinstalado. Implica rehacer el despliegue.

### 2. Crear el clúster

Consola → ECS → **Clústeres** → Crear → nombre `pedidos360` → infraestructura **EC2 existente**
(no crear instancias nuevas). La instancia del paso 1 debería aparecer registrada en un par de
minutos.

### 3. Dejar la configuración de RabbitMQ en la instancia

Los dos `.conf` y la política se montan desde el host:

```bash
sudo mkdir -p /opt/pedidos360/conf /opt/pedidos360/rabbit1 /opt/pedidos360/rabbit2
sudo cp ~/ms-pedidos360-backend/infra/ecs/rabbit1.conf /opt/pedidos360/conf/
sudo cp ~/ms-pedidos360-backend/infra/ecs/rabbit2.conf /opt/pedidos360/conf/
sudo cp ~/ms-pedidos360-backend/infra/rabbitmq/ha-policy.json /opt/pedidos360/conf/
sudo chmod -R a+r /opt/pedidos360/conf
```

### 4. Guardar los secretos en SSM

```bash
aws ssm put-parameter --name /pedidos360/DB_PASSWORD \
  --value 'LA_CLAVE' --type SecureString --overwrite --region us-east-1
aws ssm put-parameter --name /pedidos360/AZURE_GRAPH_CLIENT_SECRET \
  --value 'EL_SECRETO' --type SecureString --overwrite --region us-east-1
```

> Sácalos de `infra/.env`, que ya los tiene. No los escribas en el historial: usa
> `--value "$(grep '^DB_PASSWORD=' infra/.env | cut -d= -f2-)"`.

### 5. Publicar las imágenes en ECR

```bash
bash infra/ecs/build-and-push.sh
```

Anota las URIs que imprime al final.

### 6. Completar y registrar la definición de tarea

En `task-definition.json` hay cuatro marcadores por reemplazar:

| Marcador | Con qué |
|---|---|
| `REEMPLAZAR_ARN_LabRole` (×2) | El ARN de `LabRole` (IAM → Roles → LabRole) |
| `REEMPLAZAR_ECR/...` (×6) | Las URIs del paso 5 |
| `REEMPLAZAR_https://IP_DEL_EC2` | `https://<IP pública actual>` |

```bash
aws ecs register-task-definition \
  --cli-input-json file://infra/ecs/task-definition.json \
  --region us-east-1
```

### 7. Levantar

```bash
aws ecs create-service --cluster pedidos360 --service-name pedidos360 \
  --task-definition pedidos360 --desired-count 1 \
  --launch-type EC2 --scheduling-strategy REPLICA --region us-east-1
```

### 8. Verificar

```bash
aws ecs describe-services --cluster pedidos360 --services pedidos360 \
  --query 'services[0].{estado:status,corriendo:runningCount}' --region us-east-1

# El clúster de RabbitMQ, desde la instancia:
docker exec $(docker ps -qf name=rabbit1) rabbitmqctl -q cluster_status
```

Debe listar `rabbit1@localhost` y `rabbit2@localhost` como Running Nodes.

Y las pruebas de siempre: `http://<IP>:15672` y `:15673`, la aplicación en `https://<IP>`, y el
flujo completo de un pedido.

---

## Riesgos conocidos

- **`ecs-init` en Amazon Linux 2023**: si el paquete no está disponible, hace falta una AMI
  optimizada para ECS, lo que implica una instancia nueva.
- **Permisos del Learner Lab**: `LabRole` suele permitir ECS, ECR y SSM, pero conviene verificarlo
  antes de invertir tiempo. Si falla la creación del clúster, es por ahí.
- **Memoria**: la tarea reserva 2876 MiB de los ~3.7 GB de la instancia. El agente de ECS consume
  algo más que `dockerd` solo; si la tarea no se puede colocar, bajar los `memoryReservation`.
- **Los scripts de demo** (`verificar-cluster.sh`, `demo-dlq.sh`) usan nombres de contenedor fijos
  (`pedidos360-rabbit1`). Con ECS los nombres los genera el agente: hay que adaptarlos a
  `docker ps -qf name=rabbit1`.

## Volver atrás

El `docker-compose.yml` sigue en el repositorio y funcionando. Para revertir: detener el servicio
de ECS (`--desired-count 0`) y levantar el compose como siempre.
