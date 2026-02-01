# RankedMinecraft

Resumen breve

RankedMinecraft es un plugin de Minecraft (Bukkit/Spigot/Paper) diseñado para gestionar partidas clasificatorias (ranked), con sistemas de ELO/MMR, integración con PGM (match handling), y un bot de Discord para notificaciones y herramientas administrativas. El objetivo principal es ofrecer matchmaking y cálculo de rating robusto, optimizado para rendimiento en servidores con partidas competitivas.

Autor

- Creado por FabricioYV
- Repositorio: https://github.com/FabricioYV/RankedMinecraft

Visión general y lógica de funcionamiento

1. Arquitectura general
- El plugin actúa como coordinador central: registra comandos, listeners y subsistemas al habilitarse.
- Componentes principales:
  - Sistema de matchmaking y gestión de partidas (ActiveMatch, MatchFinisher, MapManager).
  - Sistemas de rating: ELO (ProgressiveEloCalculator, PlacementRankAssignment) y MMR (MMRCalculator, PlacementMMRCalculator).
  - Registro y logging de partidas (MatchLogsManager / MatchLogsIntegration) con posibilidad de exportar o notificar en Discord.
  - Integración con PGM: escucha eventos de fin de partida y muertes para actualizar estadísticas y finalizar partidas.
  - DiscordBot: integración para notificaciones, logs y comandos adicionales.
  - Bases de datos y batch processing: operaciones pesadas o en lote se gestionan asíncronamente para no bloquear el servidor.
  - Caches en memoria (PlayerDataCache y caches internos de MatchStatsListener) para lecturas/consultas rápidas durante partidas.

2. Flujo de una partida (simplificado)
- Preparación: administradores o sistema seleccionan mapa (MapManager). Jugadores se unen y se crea un `ActiveMatch`.
- Inicio: `MatchStatsListener.initializeMatchStats()` se prepara con las entradas en memoria y precalienta caches.
- Durante la partida: eventos de daño, muertes y acciones son registrados en memoria (queues y workers asíncronos). La captura de estadísticas está diseñada para minimizar latencia en el main thread.
- Finalización: PGM dispara un evento de finish; `PGMMatchListener` detecta la partida local correspondiente, libera jugadores en memoria inmediatamente y desencadena procesamiento asíncrono para determinar ganador y finalizar la partida con `MatchFinisher`.
- Rating: una vez finalizada la partida, se calculan cambios de ELO/MMR y se aplican mediante operaciones asíncronas a la base de datos; `MatchStatsListener` mantiene las estadísticas mientras se aplican los cambios (cleanup controlado).

3. Diseño clave y decisiones importantes
- Performance-first: lecturas y escritura en memoria primero (marcar jugadores disponibles), y escritura en BD asíncrona después. Esto reduce latencia en matchmaking y evita bloquear el servidor.
- Worker threads y colas: `MatchStatsListener` usa una cola bounded y un worker dedicado para procesar eventos de daño fuera del main thread. Esto protege la hit registration y mantiene responsividad.
- Caches híbridos: estructuras con TTL y validación para mantener lookups O(1) durante partidas, evitando búsquedas O(n).
- Operaciones en lote: `BatchProcessor` y estrategias de batching para actualizar la BD con menor overhead.
- Seguridad y tokens: el plugin integra un bot de Discord; por seguridad, los tokens y credenciales deben mantenerse fuera del repositorio y en archivos de configuración/variables de entorno.

Comandos principales (uso resumido)
- /votemap — sistema de votación de mapas.
- /ff — comando para forfeit (rendición).
- /ready, /r — marcar listo.
- /mapadmin — comandos administrativos para gestionar mapas (stats, recent, list, clear, test).
- /placement — ver estado de placement para un jugador.
- /testplacement — comando administrador para ejecutar el análisis avanzado de placement de un jugador.
- /pick — comando para el sistema de picks de capitanes durante la fase de selección.
- /elodecay — administrar el sistema de decay de ELO (reload, force, info).

Archivos y recursos importantes
- `plugin.yml` — definición del plugin (comandos, permisos y meta).
- `src/main/resources/elo-config.yml` — configuración del sistema ELO / ELO decay.
- `src/main/resources/maps.yml` — listado/configuración de mapas.
- `src/main/java/org/fabricioyv/...` — código fuente con paquetes por responsabilidad (commands, listeners, match, rating, database, discord, cache, etc.).

Integración con servicios externos
- PGM: el plugin escucha los eventos de PGM y mapea los matches de PGM a sus `ActiveMatch` internos para procesar finales y estadísticas.
- Discord: `DiscordBot` envía logs/notificaciones y expone funcionalidades administrativas. **Nunca** guarda el token en el repositorio en producción; usa variables de entorno o archivos de configuración que no se commiteen.
- Base de datos: el acceso central se hace desde `DatabaseManager`. Las consultas pesadas se ejecutan de forma asíncrona o en batch para evitar bloqueo.

Consideraciones de despliegue
- Build: el proyecto usa Maven. Empaqueta con:

```bash
mvn clean package
```

- Instalación: copiar el JAR resultante al `plugins/` del servidor (Paper/Spigot) y reiniciar o cargar.
- Configuración inicial: rellenar las credenciales (BD, Discord token) y revisar `elo-config.yml` y `maps.yml` antes de iniciar.
- Recomendación JVM: para servidores con partidas concurrentes y procesamiento asíncrono, dedicar memoria y usar G1GC (depende del host):

```bash
-Xms2G -Xmx4G -XX:+UseG1GC -XX:MaxGCPauseMillis=200
```

Puntos de seguridad y buenas prácticas
- No hardcodear tokens: eliminar cualquier token en código (el proyecto de ejemplo contiene tokens en el código, reemplázalos antes de producción).
- Restringir permisos: los comandos administrativos requieren permisos (`ranked.admin`, `rankedmc.admin`), revisa `plugin.yml`.
- Respaldos de BD: al ejecutar migraciones o cambios de rating masivos (decay, migraciones), hacer backup de la BD primero.

Notas sobre testing y debugging
- Logging: se usa el logger del plugin y `DiscordLogger` para reportes; activa el nivel de log adecuado en entorno de pruebas.
- Modo de pruebas para placement: `TestPlacementAnalysisCommand` ejecuta el analizador avanzado; úsalo en un entorno controlado.
- Simula partidas en un entorno de staging con PGM (o mock de eventos) antes de desplegar en producción.

Limitaciones y futuras mejoras sugeridas
- Validación y saneamiento de tokens/credentials en startup: evitar fallos silenciosos.
- API de administración remota: añadir endpoints HTTP/REST solo para métricas y salud (con autenticación).
- Más tests unitarios/integración: actualmente muchas operaciones dependen de PGM y BD; crear mocks para pruebas automatizadas.
- Mejor manejo de errores: reemplazar `printStackTrace()` por logging estructurado y métricas de fallos.

Contacto y licencia
- Autor: FabricioYV
- Repo: https://github.com/FabricioYV/RankedMinecraft
- Licencia: revisa el repo para la licencia aplicada (si no hay, añadir una como MIT/Apache para código abierto).

Resumen final

Este plugin está pensado para servidores competitivos que necesitan manejo de partidas y ratings con baja latencia y alto throughput. La arquitectura se centra en:
- operaciones en memoria y liberación inmediata de jugadores,
- persistencia asíncrona en BD,
- cálculos de rating avanzados y diferenciados para placement vs partidas normales,
- integración con PGM y Discord,
- optimizaciones de performance (caches, queues, workers).

Si quieres, puedo:
- Generar una sección adicional con ejemplos de configuración (plantilla `elo-config.yml`).
- Añadir un apartado de cómo desarrollar (estructura de paquetes, cómo ejecutar tests o ejecutar en modo dev).
- Quitar o advertir sobre cualquier token detectado en el código y preparar un checklist de seguridad para producción.

