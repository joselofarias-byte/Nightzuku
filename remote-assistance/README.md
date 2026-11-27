# JolufaSoft Remote

Cliente Android de asistencia con pantalla en directo, controles táctiles y navegación. Usa el cliente scrcpy para Android fijado en el script de compilación. La sesión abre video H.264 a 1280 px como máximo, 30 fps y 4 Mbps. Se inicia sin audio ni sincronización automática del portapapeles. La interfaz de asistencia prescinde de grabación, capturas y acceso a la cámara.

## Compilación

Ejecuta `bash remote-assistance/build-android.sh` con JDK 17, Android SDK 36 y Build Tools 36.0.0. El script aplica el parche al commit fijado, ejecuta las pruebas unitarias de ambas bibliotecas y verifica la firma del APK de prueba. La variante local usa la clave de depuración del equipo. Para conservar la misma firma entre compilaciones de distribución, usa el flujo de firma permanente de GitHub Actions con los secretos `JREMOTE_KEYSTORE_BASE64`, `JREMOTE_KEYSTORE_PASSWORD`, `JREMOTE_KEY_ALIAS`, `JREMOTE_KEY_PASSWORD` y las variables `JREMOTE_PUBLISHER_EMAIL` y `JREMOTE_PRIVACY_POLICY_URL`.

## Preparación

- Instala el APK en el teléfono del operador. Ejecuta `bash remote-assistance/install.sh` en Termux en ambos teléfonos desde una copia de este directorio.
- Configura cuentas SSH separadas y claves propias para cada rol según `sshd-match.conf.example`. Registra la huella del servidor verificada en el `known_hosts` de ambos teléfonos. Mantén los puertos de reenvío en bucle local.
- Configura `REMOTE_SSH_HOST`, `REMOTE_SSH_USER` y `REMOTE_SSH_KEY` en ambos teléfonos. Los valores de usuario y clave corresponden al rol de cada teléfono. Usa `REMOTE_SSH_PORT` si SSH usa un puerto distinto de 22.
- En el Android asistido, confirma que el ADB local autorizado de Nightzuku está disponible. Si usa otro puerto, configura `LOCAL_ADB_PORT`. El valor predeterminado es 5555.

## Sesión

1. En el teléfono asistido, ejecuta `"$HOME/.local/bin/jremote-agent"` en Termux. La persona escribe `SI` para autorizar. La ventana dura 30 minutos de forma predeterminada y se puede ajustar con `SESSION_MINUTES` entre 1 y 60.
2. En el teléfono del operador, ejecuta `"$HOME/.local/bin/jremote-controller"` en Termux. Abre **JolufaSoft Remote**, conserva el puerto **15556** y pulsa **Conectar**. Si configuraste `CONTROLLER_PORT`, usa ese puerto.
3. Si Android muestra la solicitud ADB, la persona asistida acepta la clave del cliente. La autorización ADB del APK es propia y se gestiona en el teléfono asistido.
4. El operador termina desde **Finalizar sesión**. La persona asistida corta el acceso con **Ctrl+C** en Termux. El túnel también se cierra automáticamente al vencer el tiempo.

Para varios dispositivos, asigna un puerto de servidor diferente por teléfono y usa el mismo `REMOTE_BIND_PORT` en el agente, el operador y las dos restricciones de sshd. Para revocar la autorización de ADB, usa las opciones de desarrollador del Android asistido. Para revocar el acceso SSH, retira la clave autorizada correspondiente. `jremote-check` comprueba disponibilidad local y TCP. La aceptación ADB y la transmisión se verifican al iniciar la sesión.

El cliente y el transporte mantienen identificadores y firma propios para poder instalarse junto a Nightzuku y al cliente scrcpy original. La licencia y avisos del cliente upstream se conservan al compilar.
