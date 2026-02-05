#!/bin/bash

# ========================================
# RANKED MINECRAFT - OPTIMIZED PERFORMANCE MODE
# Auto-configurable - NO requiere permisos manuales
# 4GB RAM - HIT REGISTRATION EXCELENTE
# Optimizado para mapas 72x72 - Partidas 5v5
# AJUSTADO PARA PLANES DE 6GB TOTAL
# ========================================

# Auto-configurar permisos (silencioso si falla)
chmod +x start.sh 2>/dev/null || true

# ========================================
# COLORES PARA OUTPUT
# ========================================
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# ========================================
# BANNER DE INICIO
# ========================================
clear
echo -e "${PURPLE}╔════════════════════════════════════════════╗${NC}"
echo -e "${PURPLE}║${GREEN}   RANKED MINECRAFT - OPTIMIZED MODE      ${PURPLE}║${NC}"
echo -e "${PURPLE}╠════════════════════════════════════════════╣${NC}"
echo -e "${PURPLE}║${CYAN} Hit Registration:${GREEN} EXCELENTE ⚡            ${PURPLE}║${NC}"
echo -e "${PURPLE}║${CYAN} RAM Asignada:    ${YELLOW} 4GB (Optimizado)       ${PURPLE}║${NC}"
echo -e "${PURPLE}║${CYAN} GC Pause Target: ${YELLOW} 150ms (Balanceado)     ${PURPLE}║${NC}"
echo -e "${PURPLE}║${CYAN} Stats Tracking:  ${RED} DESACTIVADO            ${PURPLE}║${NC}"
echo -e "${PURPLE}║${CYAN} CPU Mode:        ${GREEN} MEDIO (Eficiente)      ${PURPLE}║${NC}"
echo -e "${PURPLE}╚════════════════════════════════════════════╝${NC}"
echo ""

# ========================================
# VERIFICACIONES PRE-INICIO
# ========================================

echo -e "${BLUE}[CHECK]${NC} Verificando archivos del servidor..."

# Verificar que existe server.jar
if [ ! -f "server.jar" ]; then
    echo -e "${RED}[ERROR]${NC} No se encontró server.jar"
    echo -e "${YELLOW}[INFO]${NC} Asegúrate de que server.jar está en el directorio actual"
    echo -e "${YELLOW}[INFO]${NC} Directorio actual: $(pwd)"
    echo -e "${YELLOW}[INFO]${NC} Archivos disponibles:"
    ls -lah *.jar 2>/dev/null || echo "No hay archivos .jar"
    exit 1
fi

echo -e "${GREEN}[OK]${NC} server.jar encontrado"

# Verificar versión de Java
echo -e "${BLUE}[CHECK]${NC} Verificando versión de Java..."
JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2)
echo -e "${GREEN}[OK]${NC} Java versión: ${YELLOW}${JAVA_VERSION}${NC}"

# Verificar RAM disponible
TOTAL_RAM=$(free -m 2>/dev/null | awk '/^Mem:/{print $2}' || echo "6144")
echo -e "${GREEN}[OK]${NC} RAM total del sistema: ${YELLOW}${TOTAL_RAM}MB${NC}"
echo -e "${YELLOW}[INFO]${NC} RAM asignada al servidor: ${GREEN}4096MB${NC} (deja 2GB para el sistema)"

echo ""
echo -e "${PURPLE}════════════════════════════════════════════${NC}"
echo -e "${CYAN}[INICIO]${NC} Iniciando servidor con FLAGS optimizadas..."
echo -e "${PURPLE}════════════════════════════════════════════${NC}"
echo ""

# ========================================
# INFORMACIÓN DE CONFIGURACIÓN
# ========================================
echo -e "${BLUE}[CONFIG]${NC} Garbage Collector: ${GREEN}G1GC${NC} (Optimizado para PvP)"
echo -e "${BLUE}[CONFIG]${NC} Heap Memory: ${YELLOW}4096MB${NC} inicial y máxima"
echo -e "${BLUE}[CONFIG]${NC} GC Pause Target: ${YELLOW}150ms${NC} (Balanceado - buen rendimiento)"
echo -e "${BLUE}[CONFIG]${NC} New Generation: ${YELLOW}40%${NC} (Optimizado para eventos PvP)"
echo -e "${BLUE}[CONFIG]${NC} String Deduplication: ${GREEN}ACTIVADO${NC}"
echo -e "${BLUE}[CONFIG]${NC} Compressed OOPs: ${GREEN}ACTIVADO${NC}"
echo -e "${BLUE}[CONFIG]${NC} Performance Stats: ${RED}DESACTIVADO${NC} (0% CPU en hits)"
echo -e "${YELLOW}[NOTA]${NC} Configuración ajustada para uso eficiente de recursos"
echo ""
echo -e "${GREEN}[READY]${NC} Iniciando Minecraft Server..."
echo -e "${PURPLE}════════════════════════════════════════════${NC}"
echo ""

# ========================================
# FLAGS OPTIMIZADAS - BALANCE RENDIMIENTO/RECURSOS
# 4GB RAM - Perfecto para planes de 6GB
# ========================================

exec java -Xms4G -Xmx4G \
-XX:+UseG1GC \
-XX:+ParallelRefProcEnabled \
-XX:MaxGCPauseMillis=150 \
-XX:+UnlockExperimentalVMOptions \
-XX:+DisableExplicitGC \
-XX:+AlwaysPreTouch \
-XX:G1NewSizePercent=40 \
-XX:G1MaxNewSizePercent=50 \
-XX:G1HeapRegionSize=8M \
-XX:G1ReservePercent=20 \
-XX:G1HeapWastePercent=5 \
-XX:G1MixedGCCountTarget=4 \
-XX:InitiatingHeapOccupancyPercent=15 \
-XX:G1MixedGCLiveThresholdPercent=90 \
-XX:G1RSetUpdatingPauseTimePercent=5 \
-XX:SurvivorRatio=32 \
-XX:+PerfDisableSharedMem \
-XX:MaxTenuringThreshold=1 \
-XX:+UseStringDeduplication \
-XX:+UseCompressedOops \
-Dusing.aikars.flags=https://mcflags.emc.gs \
-Daikars.new.flags=true \
-Dterminal.jline=false \
-Dterminal.ansi=true \
-Dfile.encoding=UTF-8 \
-jar server.jar nogui

# ========================================
# MANEJO DE CIERRE
# ========================================

EXIT_CODE=$?

echo ""
echo -e "${PURPLE}════════════════════════════════════════════${NC}"
if [ $EXIT_CODE -eq 0 ]; then
    echo -e "${GREEN}[EXIT]${NC} Servidor detenido correctamente"
else
    echo -e "${RED}[ERROR]${NC} Servidor detenido con código de error: ${EXIT_CODE}"
fi
echo -e "${PURPLE}════════════════════════════════════════════${NC}"

exit $EXIT_CODE
