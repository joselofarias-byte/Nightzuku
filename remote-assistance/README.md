# JolufaSoft Remote

Instala el mismo APK en los dos teléfonos. En **Recibir asistencia**, la persona habilita la disponibilidad permanente una vez. Un servicio visible mantiene el túnel abierto, reintenta tras cortes y lo restaura después del reinicio y primer desbloqueo. **Desactivar asistencia** está en la app y en la notificación. En **Dar asistencia**, toca **Conectar** para ver la pantalla y usar los controles. La sesión empieza sin audio y sin sincronizar el portapapeles.

## Configuración una sola vez con Tailscale

1. Conecta los dos teléfonos y el Oracle al mismo tailnet. En la política de acceso, permite que solo esos teléfonos lleguen al puerto TCP 2222 del Oracle. El puerto 5555 del Android no se necesita en las reglas de acceso del tailnet.
2. En cada teléfono, elige su rol y pulsa **Ver clave pública SSH**. Guarda las claves públicas en el Oracle como `operador.pub` y `asistido.pub`.
3. En el Oracle Ubuntu con OpenSSH y Tailscale instalados, ejecuta:

   ```bash
   sudo bash remote-assistance/setup-tailnet-relay.sh operador.pub asistido.pub
   ```

   Se instala una instancia separada de OpenSSH que escucha en la IP de Tailscale, en el puerto 2222. El puerto ADB se transporta por SSH y ambos extremos del reenvío quedan en bucle local. El script muestra IP, puertos y huella ECDSA SHA256. También se puede usar un OpenSSH existente con las restricciones de `sshd-match.conf.example` y su clave ECDSA P-256.

4. En las dos apps, configura la IP privada que imprime el script o el nombre completo MagicDNS, el puerto SSH 2222, la huella y el puerto del dispositivo 15555. Los usuarios son `remote-operator-01` y `remote-device-01`. En el teléfono asistido, el puerto ADB de Nightzuku es 5555 por defecto.
5. En el teléfono asistido, toca **Activar asistencia permanente** y autoriza las notificaciones. En el otro, toca **Conectar**. Si Android pide autorizar la clave ADB del operador, acéptala en el teléfono asistido.

El teléfono asistido necesita que Android permita a Tailscale, Nightzuku y JolufaSoft Remote seguir ejecutándose. Si Nightzuku pierde el puerto ADB después de reiniciar, la app queda esperando a que regrese. La notificación muestra el estado. Tras forzar el cierre de la app desde Ajustes, ábrela y vuelve a activar la disponibilidad.

## Compilación

`bash remote-assistance/build-android.sh` aplica el parche al commit fijado, ejecuta las pruebas de ambas bibliotecas y verifica la firma del APK. Requiere JDK 17, Android SDK 36 y Build Tools 36.0.0. El APK local usa la clave de depuración del entorno. Para distribución, el script admite `release` y usa la configuración de firma y los datos de editor de scrcpy. El workflow `JolufaSoft Remote Android` publica el APK de prueba y su SHA256 cuando se actualiza la rama.

El código histórico en esta carpeta conserva el transporte de la 1.0.0. La 1.1.0 maneja los dos túneles desde la app.
