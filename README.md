# LegendarySpawner - Instrucciones de compilación

## ⚠️ Nota importante
Cobblemon no está en Maven público. Para compilar este mod necesitas
que Gradle ya tenga Cobblemon en su caché local (de haber compilado
el PokeBuilder u otro mod de Cobblemon antes).

## Opción A - Compilar en la misma sesión (recomendado)
Simplemente compila este mod **en la misma máquina y sesión** donde
ya compilaste el PokeBuilder. Gradle reutilizará el caché automáticamente.

## Opción B - Forzar el caché manualmente
1. Abre la carpeta `C:\Users\<tu usuario>\.gradle\caches`
2. Verifica que existe: `modules-*/files-*/com.cobblemon/fabric/1.7.3+1.21.1/`
3. Si existe, el build funcionará directamente con `.\gradlew.bat build`

## Comandos
- `/legendaryspawner spawn` - Fuerza spawn aleatorio
- `/legendaryspawner spawn <especie>` - Fuerza spawn específico  
- `/legendaryspawner remove` - Elimina legendarios activos
- `/legendaryspawner status` - Estado actual
- `/legendaryspawner reload` - Recarga config
- Alias corto: `/ls`
