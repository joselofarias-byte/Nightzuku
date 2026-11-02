# Oracle A1

El equipo ARM64 con Ubuntu 24.04 no forma parte del camino de asistencia.

No instales ahí un `adb` escuchando, un proxy inverso ni un reenvío del puerto del OPPO. No hace falta una cuenta SSH para este módulo: los scripts no se conectan a ese servidor.

Tailscale puede tener ese nodo en la tailnet para otras cosas. No lo uses como router de subred para alcanzar el ADB del OPPO: ensancharía el acceso en vez de achicarlo.

La ACL de ejemplo está en `configuracion-presencial.md` y se carga en el panel de Tailscale, no en el Oracle.
