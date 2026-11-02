# Configuración presencial

Se hace con la persona al lado, el OPPO desbloqueado por ella y el HONOR en la mano. No hace falta entrar por SSH al servidor Oracle ni pedir contraseñas de cuentas.

Cuando termine la visita, la notificación **Asistencia JOLUFA activa** tiene que seguir visible. Si no está, el agente no está corriendo.

## 1. Tailscale en los dos teléfonos

Instalá Tailscale desde la tienda, en los dos, con la misma red. Aprobá los dos nodos. Anotá la IP `100.x` del OPPO (en la app de Tailscale).

En el panel de Tailscale, una ACL de ejemplo para que solo el HONOR llegue al OPPO:

```json
{
  "acls": [
    {
      "action": "accept",
      "src": ["tag:cuidador"],
      "dst": ["tag:oppo:*"]
    }
  ]
}
```

Eso no cierra el puerto de ADB en la Wi-Fi de la casa. La ACL solo filtra la red de Tailscale. Por eso, para las sesiones, conviene datos móviles y no un Wi-Fi compartido. Es una mitigación, no una garantía medida en este OPPO.

## 2. Termux en el HONOR

En Termux, copiá y ejecutá:

```bash
pkg update -y && pkg install -y android-tools python git
git clone https://github.com/joselofarias-byte/Nightzuku.git "$HOME/Nightzuku"
ln -sfn "$HOME/Nightzuku/oppo-assist/termux/bin/jolufa" "$PREFIX/bin/jolufa"
mkdir -p "$HOME/.config/jolufa"
chmod 700 "$HOME/.config/jolufa"
```

Hasta que este código esté en `main`, copiá la carpeta `oppo-assist` de esta rama al mismo lugar del clon.

## 3. Pantalla de scrcpy (todavía no verificada en el HONOR 200)

Termux no dibuja una ventana de Linux solo. Hace falta la app Termux:X11 (el APK nightly del proyecto Termux) y el paquete compañero:

```bash
pkg install -y x11-repo
pkg install -y termux-x11-nightly proot-distro
proot-distro install debian
proot-distro login debian --shared-tmp -- bash -lc 'apt-get update && apt-get install -y scrcpy'
```

Para una sesión gráfica, en Termux:

```bash
termux-x11 :1 &
export DISPLAY=:1
proot-distro login debian --shared-tmp
```

Dentro de Debian, el `adb` que ya emparejaste es el de Termux. Esta parte no se corrió en el HONOR: si `scrcpy` no ve el servidor ADB, no inventes un puerto público. Usá mientras `jolufa captura` y `jolufa tocar`, que no necesitan X11.

## 4. El APK en el OPPO

El APK de depuración queda en `oppo-assist/agent/build/outputs/apk/debug/` después de `./gradlew :agent:assembleDebug` (desde `oppo-assist/`).

Con el OPPO en depuración USB, desde la computadora de la visita:

```bash
adb -d install -r oppo-assist-debug.apk
```

Abrí **OPPO Assist**. Dale permisos de SMS, contactos y notificaciones. Tocá **Permitir en segundo plano** y aceptá. En ColorOS, además, buscá el inicio automático de la app en los ajustes de batería. El rótulo del menú cambia con el modelo; no hay un camino único verificado.

Tocá **Iniciar asistencia** y **Guardar lista de apps**.

Con Tailscale conectado, el estado tiene que decir que escucha en una IP `100.` o `fd7a:115c:a1e0:`. Si Tailscale no está, el agente no abre el puerto. Eso es a propósito.

## 5. Clave

En el OPPO, **Mostrar clave de esta sesión**. En el HONOR:

```bash
read -r TOKEN
printf '%s' "$TOKEN" > "$HOME/.config/jolufa/token"
chmod 600 "$HOME/.config/jolufa/token"
cat > "$HOME/.config/jolufa/config" <<'EOF'
host=100.x.x.x
adb_port=PUERTO_DE_CONEXION
agent_port=8765
token_file=/data/data/com.termux/files/home/.config/jolufa/token
EOF
chmod 600 "$HOME/.config/jolufa/config"
```

Reemplazá `100.x.x.x` y `PUERTO_DE_CONEXION`. No mandes la clave por SMS.

## 6. Emparejar la depuración inalámbrica

En el OPPO: Ajustes, Acerca del teléfono, tocá el número de compilación hasta habilitar las opciones de desarrollador. Entrá a Depuración inalámbrica. Anotá el puerto de emparejamiento y el de conexión. El puerto de conexión cambia; no lo dejes escrito como si fuera fijo.

Primero contra la IP de Tailscale, no contra la IP de Wi-Fi que muestra el teléfono:

```bash
adb pair 100.x.x.x:PUERTO_DE_EMPAREJAMIENTO
```

Te pide el código que está en la pantalla del OPPO. Después:

```bash
jolufa conectar
jolufa estado
```

Si el `adb pair` a la IP `100.` no contesta, en esta visita y en una red que controles podés emparejar una sola vez con la IP local que muestra el OPPO. `jolufa` no va a usar esa IP local después: solo acepta Tailscale. Cuando termines el emparejamiento, seguí con `jolufa conectar` usando el puerto de conexión y la IP `100.`.

No ejecutes `adb tcpip`. Eso abre el puerto en todas las interfaces y esta herramienta no lo hace.

## 7. Probar el bloqueo

Bloqueá el OPPO con su PIN o patrón. En el HONOR:

```bash
jolufa tocar 200 400
```

Tiene que negarse. No le pidas a la herramienta que escriba el PIN. La persona lo desbloquea en la mano. Recién ahí:

```bash
jolufa pantalla
```

o, si no hay imagen:

```bash
jolufa captura
jolufa tocar 200 400
```

## 8. SMS

La casilla de SMS comunes queda apagada hasta que la marques en persona. Aunque esté prendida, un mensaje que parece código de un solo uso no entra a la cola del HONOR. En el OPPO aparece el pedido. Si tocás **Permitir**, `jolufa sms` lo muestra. Si no tocás nada, a los 10 minutos se borra.

Los chats RCS no salen por este camino.

## 9. Cerrar

```bash
jolufa cerrar-sesion
```

Si el teléfono no confirma el valor 0, apagá Depuración inalámbrica a mano. Un reinicio también la apaga en la plataforma Android, pero **no está demostrado en este OPPO** y tampoco vuelve a encenderla. Después de reiniciar hay que repetir el paso 6 y anotar el puerto nuevo. `jolufa reiniciar --confirmo` existe y avisa eso; no promete reconexión.

`jolufa recuperar` solo reintenta el mismo puerto. Si el puerto cambió, no lo adivina.
