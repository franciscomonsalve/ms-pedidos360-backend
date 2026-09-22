# Alta de usuarios en Entra ID (botón "Crear cuenta")

El botón del login lleva a `/register`. El formulario hace `POST /api/users/register`
al BFF, y el BFF crea al usuario en Entra ID vía Microsoft Graph con el flujo
*client credentials* (el secreto nunca llega al navegador).

## 1. App Registration para Graph

Puedes reutilizar la App Registration de la API (`08c770a5-…`) o crear una nueva
dedicada (recomendado). En Azure Portal → **Microsoft Entra ID → App registrations**:

1. **Certificates & secrets → New client secret** → copia el *Value*.
2. **API permissions → Add a permission → Microsoft Graph → Application permissions**:
   - `User.ReadWrite.All` — crear usuarios en el directorio.
   - `AppRoleAssignment.ReadWrite.All` — asignar el App Role (Admin/Operator/Customer).
3. Pulsa **Grant admin consent** (sin esto, Graph devuelve 403).

> Los permisos *delegated* no sirven aquí: el BFF actúa como aplicación, sin usuario.

## 2. Variables de entorno del BFF

| Variable | Qué es | Ejemplo |
|---|---|---|
| `AZURE_TENANT_ID` | Directory (tenant) ID | `f280f365-…` |
| `AZURE_GRAPH_CLIENT_ID` | appId de la App Registration con permisos de Graph | `1234abcd-…` |
| `AZURE_GRAPH_CLIENT_SECRET` | secreto generado en el paso 1 | `abc8Q~…` |
| `AZURE_USER_DOMAIN` | dominio verificado del tenant | `pedidos360.onmicrosoft.com` |
| `AZURE_API_CLIENT_ID` | appId de la API (donde viven los App Roles) | `08c770a5-…` |
| `AZURE_DEFAULT_ROLE` | rol por defecto | `Customer` |
| `AZURE_SELF_SERVICE_ROLES` | roles permitidos en el registro público | `Customer` |
| `CORS_ALLOWED_ORIGINS` | orígenes del SPA | `http://localhost:4200` |

PowerShell, para probar en local:

```powershell
$env:AZURE_GRAPH_CLIENT_ID     = "<appId>"
$env:AZURE_GRAPH_CLIENT_SECRET = "<secret>"
$env:AZURE_USER_DOMAIN         = "<tenant>.onmicrosoft.com"
mvn -pl ms-pedidos360-bff spring-boot:run
```

## 3. Frontend

En `src/environments/environment.ts` y `environment.prod.ts`, ajusta
`signupDomain` al mismo dominio de `AZURE_USER_DOMAIN`. Solo se usa para mostrar
`@tenant.onmicrosoft.com` junto al alias; el UPN real lo arma el BFF.

## 4. Seguridad del endpoint público

`POST /api/users/register` es anónimo a propósito (el usuario aún no tiene sesión).
Para que nadie se auto-asigne privilegios:

- El rol **no se lee del body** en el registro público: el BFF aplica
  `pedidos360.graph.self-service-roles` (por defecto solo `Customer`).
- Para crear Admin u Operator existe `POST /api/users`, que exige un JWT con rol
  `Admin` (`@PreAuthorize("hasRole('ADMIN')")`).

Si no quieres registro abierto a internet, pon `AZURE_SELF_SERVICE_ROLES=` vacío o
quita el `permitAll` de `/api/users/register` en `SecurityConfig`; el botón del
login seguirá funcionando solo para administradores autenticados.

## 5. Comportamiento del usuario creado

- `accountEnabled: true`
- `forceChangePasswordNextSignIn: false` → la clave que el usuario define en el
  registro queda como definitiva. Se probó con `true` (el estándar recomendado),
  pero en este tenant (External ID/CIAM) ese paso de "cambia tu clave" del
  primer login rompe el flujo de MSAL (`AADSTS900561: The endpoint only
  accepts POST requests. Received a GET request.`), así que se desactivó.
- El `otherMails` se rellena con el correo de recuperación si se indicó.
- Si falla la asignación del rol, el usuario **sí queda creado** y la respuesta lo
  avisa (`roleAssigned: false`) para que un admin lo asigne a mano.
