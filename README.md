# OpenLimit

App Android minimalista para limitar **cuántas veces por hora** puedes abrir cada app.

Ejemplo: con un límite de 3 en WhatsApp, puedes abrirla 3 veces entre las 14:00 y las 14:59.
La 4.ª vez te manda al inicio y muestra una pantalla de bloqueo hasta las 15:00, cuando el contador se reinicia.

## Funciones

- Límite de aperturas por hora para cada app (0 = bloqueada siempre, "—" = sin límite).
- Contadores que se reinician en cada hora en punto.
- Límites y contadores guardados en archivos: se conservan aunque reinicies el teléfono.
- **Anti-trampa:** cuando una app agota sus aperturas, no se puede subir ni quitar su límite hasta la siguiente hora. Solo se puede hacer más estricto.
- **Pausa mínima:** salir de una app y volver antes de N segundos (45 por defecto) no gasta una apertura.
- Pantalla de bloqueo a pantalla completa con la hora en que la app vuelve a estar disponible.
- Interfaz oscura y minimalista con buscador; las apps con límite aparecen primero.

## Cómo funciona

| Pieza | Archivo | Qué hace |
|---|---|---|
| Servicio de accesibilidad | [`LimitAccessibilityService.kt`](app/src/main/java/net/jakstech/openlimit/LimitAccessibilityService.kt) | Filtra los eventos de Android (ignora teclado, barra de notificaciones, menú de compartir, diálogos y paneles) y actúa según lo que decide `OpenTracker`. |
| Lógica de conteo | [`OpenTracker.kt`](app/src/main/java/net/jakstech/openlimit/OpenTracker.kt) | Decide si es apertura, misma sesión o bloqueo (ver abajo). Es Kotlin puro, sin APIs de Android, para poder probarlo. |
| Pantalla de bloqueo | [`BlockOverlay.kt`](app/src/main/java/net/jakstech/openlimit/BlockOverlay.kt) | Se dibuja como capa de accesibilidad (`TYPE_ACCESSIBILITY_OVERLAY`), así que no necesita permisos extra. |
| Almacenamiento | [`LimitStore.kt`](app/src/main/java/net/jakstech/openlimit/LimitStore.kt) | `limits.properties` y `settings.properties` (los escribe la interfaz) y `counts.properties` (lo escribe el servicio, junto con la hora a la que pertenecen los contadores). |
| Interfaz | [`MainActivity.kt`](app/src/main/java/net/jakstech/openlimit/MainActivity.kt) | Lista de apps con el selector − / +. |

### Cómo se cuenta una apertura

Los eventos de ventana (`TYPE_WINDOW_STATE_CHANGED`) generan mucho ruido. Animaciones, gestos, diálogos, paneles y eventos tardíos de una app que se está cerrando también los disparan. Por eso:

1. Solo cuentan los eventos cuya clase es una **Activity real** del paquete. Diálogos, `FrameLayout` y paneles se ignoran.
2. La app al frente se decide cuando lleva **400 ms estable**. Ese tiempo se mide con la hora de cada evento (`eventTime`), no con la de llegada. Si el sistema pausa el proceso, los eventos llegan juntos y se procesan en orden. Si la app ya no está en pantalla, la apertura se cuenta pero no se muestra el bloqueo.
3. `notificationTimeout="0"`: con un valor mayor, Android fusiona los eventos y se pierde el de "pantalla mostrada".
4. La primera app al frente después de que el servicio arranca no cuenta.
5. **Pausa mínima** (por defecto 45 s, ajustable de 0 a 5 min en pasos de 15 s en la pantalla principal): si sales de una app y vuelves antes de ese tiempo, sigue siendo la misma sesión y no cuenta. Si vuelves después, cuenta como apertura nueva. El tiempo incluye el que pasa con la pantalla apagada. No se puede subir mientras alguna app tenga sus aperturas agotadas. Un bloqueo termina la sesión anterior.
   Android no le dice a una app normal si otra app se **cerró** o solo se **minimizó**: en los dos casos solo se ve que salió de la pantalla. Por eso la pausa se aplica igual en ambos casos.

Las decisiones sobre las apps **con límite** quedan en el registro con la etiqueta `OpenLimit`. Las demás apps no se registran:

```bash
adb logcat -s OpenLimit
```

El servicio corre en su propio proceso (`:watcher`). Además, es un servicio en primer plano con una notificación silenciosa. Así, cerrar OpenLimit o "limpiar todo" en Recientes no lo mata; en MIUI eso dejaba el servicio caído.
Por eso los datos se guardan en archivos que se releen en cada acceso y no en `SharedPreferences`, que guarda una copia en memoria por proceso y quedaría desactualizada.

No se lee el contenido de la pantalla (`canRetrieveWindowContent=false`). Solo se usa el nombre del paquete de la app en primer plano.

## Requisitos

- Android 8.0+ (minSdk 26, targetSdk 36)
- JDK 17+, Android SDK con la plataforma 36
- Gradle 9.2.1 (vía wrapper), AGP 9.0.1, Kotlin + Jetpack Compose

## Compilar e instalar

Con el teléfono conectado por USB y la depuración USB activada:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Tests

**Unitarios (JVM, sin teléfono, ~1 s):**

```bash
./gradlew testDebugUnitTest
```

- [`LimitStoreTest`](app/src/test/java/net/jakstech/openlimit/LimitStoreTest.kt) (21 tests): límites, anti-trampa (no subir ni quitar un límite agotado), reinicio en la hora en punto y a medianoche, persistencia entre instancias (reinicio o el otro proceso), archivos corruptos, pausa (por defecto, límites, bloqueo mientras haya apps agotadas).
- [`OpenTrackerTest`](app/src/test/java/net/jakstech/openlimit/OpenTrackerTest.kt) (26 tests): conteo 1-2-3 y bloqueo, límite 0, apps sin límite y la propia app, eventos ruidosos (parpadeo al abrir, evento tardío al cerrar, duplicados), **eventos retrasados en tanda**, pausa mínima (dentro, fuera, exacta, con pantalla apagada, por app, sin reanudar tras un bloqueo) y reinicio por hora.

Usan un reloj falso ([`FakeTime`](app/src/test/java/net/jakstech/openlimit/FakeTime.kt)) para simular horas, pantalla apagada y retrasos.

**En el teléfono (adb, ~1 min):**

```bash
scripts/device-test.sh com.brave.browser
```

Comprueba que el servicio está activo y en primer plano, el conteo con bloqueo, la pausa mínima y los eventos retrasados (pausa el proceso con `kill -STOP`). Guarda tus límites y contadores antes de empezar y los restaura al terminar.

## Configuración en el teléfono

1. Abre OpenLimit y toca **"Activa el servicio"**, o ve a **Ajustes → Accesibilidad → Descargadas → OpenLimit → Activar**.
2. En Xiaomi / MIUI / HyperOS (muy recomendado):
   - Información de la app → **Inicio automático**: activado.
   - **Ahorro de batería** → **Sin restricciones**.
3. Si Android muestra "ajuste restringido": Información de la app → ⋮ → **Permitir ajustes restringidos**.

## Batería

El servicio solo se ejecuta cuando Android le avisa que cambió la app en primer plano. No hay temporizadores, wakelocks, red ni GPS.
En cada evento lee un archivo pequeño. La interfaz refresca los contadores cada segundo, pero solo mientras está abierta.

## Limitaciones conocidas

- Cambiar la hora del teléfono reinicia los contadores.
- Desactivar el servicio de accesibilidad, forzar la detención o desinstalar la app quita el bloqueo.
- Abrir una app en pantalla dividida o en ventana flotante puede no contarse de forma exacta.

## Ideas futuras

- Límite por **tiempo de uso** (minutos por hora o por día).
- **"1 minuto extra"**: una sola vez por hora y por app.
- Protección contra el cambio de hora (usar un reloj que no dependa de la hora del sistema).
- Modo concentración o descanso por horario.
- Panel de uso diario.
- Protección contra desinstalación (administrador del dispositivo).
