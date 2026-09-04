# Guía de instalación — RankedMinecraft + RankedDiscord

Este proyecto son **dos plugins independientes que trabajan juntos**:

- **[RankedMinecraft](https://github.com/FabricioYV/RankedMinecraft)** (este repo): corre en el servidor de Minecraft (Paper/Spigot + PGM). Matchmaking, picks, vetos, cálculo de ELO/MMR, y un bot de Discord para notificaciones de partidas.
- **[RankedDiscord](https://github.com/FabricioYV/RankedDiscord)**: también un plugin de Bukkit, expone `/verify` en el juego para vincular la cuenta de Minecraft con Discord, comandos de admin vía Discord y mensajes de bienvenida.

**Ambos leen y escriben la misma tabla `ranked_players`** en la misma base de datos MySQL — RankedMinecraft la crea y actualiza los stats de partidas; RankedDiscord la usa para verificación y comandos de admin. Si no apuntan a la misma base de datos, cada uno va a ver un mundo de jugadores distinto.

## Requisitos

- Servidor Paper/Spigot 1.12.x con [PGM](https://pgm.dev/) instalado (para RankedMinecraft).
- Java 16+ para compilar (el plugin apunta a bytecode 1.12/Java 16, revisá compatibilidad con tu core).
- MySQL 5.7+ u 8.x accesible desde el servidor.
- Maven.
- Dos aplicaciones de bot en el [Discord Developer Portal](https://discord.com/developers/applications) (una por plugin — pueden vivir en el mismo servidor de Discord).
- Modo desarrollador activado en tu cliente de Discord (para copiar IDs de canales/roles/usuarios: Configuración → Avanzado → Modo desarrollador).

## 1. Base de datos

Creá una base de datos y un usuario con permisos sobre ella. Ambos plugins van a apuntar acá:

```sql
CREATE DATABASE ranked_db;
CREATE USER 'ranked_user'@'%' IDENTIFIED BY 'una-contraseña-fuerte';
GRANT ALL PRIVILEGES ON ranked_db.* TO 'ranked_user'@'%';
FLUSH PRIVILEGES;
```

RankedMinecraft además usa una **segunda base de datos** (`databases.match_logs` en su `config.yml`) para logs detallados de partidas — es independiente, podés usar la misma instancia de MySQL con otro nombre de base.

## 2. Bots de Discord

Por cada uno (RankedMinecraft y RankedDiscord):

1. Andá al [Developer Portal](https://discord.com/developers/applications) → **New Application**.
2. Pestaña **Bot** → **Reset Token** → copiá el token (lo vas a pegar en `config.yml`, nunca lo subas a git).
3. Activá los **Privileged Gateway Intents** que necesite el bot: como mínimo `SERVER MEMBERS INTENT` y `MESSAGE CONTENT INTENT`.
4. Pestaña **OAuth2 → URL Generator**: marcá `bot` y los permisos que necesite (mover miembros de voz, gestionar roles, enviar mensajes, adjuntar archivos), generá el link e invitalo a tu servidor.
5. En tu servidor de Discord, copiá el **ID del servidor** (clic derecho sobre el ícono del servidor → Copiar ID) para `discord.guild-id`.

## 3. RankedMinecraft

```bash
git clone https://github.com/FabricioYV/RankedMinecraft.git
cd RankedMinecraft
mvn clean package
```

El jar queda en `target/`. Copialo a `plugins/` de tu servidor, iniciá una vez para que genere `plugins/RankedMinecraft/config.yml`, apagalo y completá ahí:

- `discord.token`, `discord.guild-id`
- `discord.channels.*` — IDs de los canales de voz/texto que usa el bot (sala de espera, categoría de equipos, resultados, logs, categoría de picks, canales de voz por modo)
- `discord.roles.queue` — rol `@Queue`
- `discord.roles.captain-eligible` — lista de IDs de rol habilitados para ser capitanes
- `discord.roles.ranks.*` — un rol de Discord por cada rango de ELO
- `databases.ranked.*` — host/puerto/nombre/usuario/contraseña de la base de datos del paso 1
- `databases.match_logs.*` — igual, para la base de datos de logs (puede ser otro nombre en la misma instancia)

Revisá también `elo-config.yml` y `maps.yml` antes de arrancar en producción.

## 4. RankedDiscord

```bash
git clone https://github.com/FabricioYV/RankedDiscord.git
cd RankedDiscord
mvn clean package
```

Mismo proceso: copiá el jar a `plugins/`, arrancá una vez para generar el `config.yml`, apagalo y completá:

- `database.host/port/database/username/password` — **la misma base de datos del paso 1**, no una nueva.
- `discord.token` — el token del segundo bot.
- `discord.queue_role_name` — nombre del rol `@Queue` (por defecto `"Queue"`).
- `discord.super-admin-user-id` — tu ID de usuario de Discord, para comandos administrativos destructivos como `/resetallstats`.

Si dejás algún campo con su valor `PUT_..._HERE` de fábrica, el plugin te lo va a decir en consola y se va a deshabilitar solo en vez de fallar con errores confusos.

## 5. Verificación

1. Arrancá RankedMinecraft primero (crea las tablas si no existen).
2. Arrancá RankedDiscord.
3. En el juego, probá `/verify` — debería generar un código y RankedDiscord debería poder validarlo contra la misma fila en `ranked_players` que ve RankedMinecraft.
4. En Discord, probá un comando de cada bot para confirmar que ambos tokens funcionan.

## Notas de seguridad

- Nunca subas `config.yml` con valores reales a git — quedate con los placeholders `PUT_..._HERE` en el repo, y editá solo la copia que corre en tu servidor.
- Si vas a exponer alguno de los dos repos como fork público, revisá que tu `config.yml` local (con credenciales reales) esté en `.gitignore` de tu propio despliegue si lo versionás aparte.
