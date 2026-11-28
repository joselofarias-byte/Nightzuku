# Qué hace NightDog

Nightzuku es gratuito y seguirá siéndolo. NightDog mantiene el servicio disponible y muestra qué ocurrió cuando tuvo que recuperarlo.

| Caso | Comportamiento |
| --- | --- |
| Binder deja de responder | NightDog busca un ADB autorizado, comprueba el shell, inicia el servicio y espera la confirmación de Binder. |
| Cambio de Wi-Fi o puerto ADB | Redescubre el endpoint local. Si falla el puerto TLS previo y no hay un respaldo TCP habilitado, puede continuar el descubrimiento sin aceptar de nuevo el anuncio obsoleto. |
| Reinicio del teléfono | Puede reanudar la recuperación tras desbloquear Android si el servicio estaba habilitado y hay un transporte ADB autorizado. El fabricante puede limitar el inicio automático en segundo plano. |
| Detener Nightzuku | Se guarda la parada manual. Para reactivar la recuperación, vuelve a iniciar el servicio. |

## Probar la persistencia

Abre **Menú ⋮ → Qué hace NightDog → Abrir prueba de recuperación**. En el laboratorio, toca **Probar recuperación automática** y confirma. La prueba detiene el servidor una vez. Comprueba que el panel vuelva a mostrar **Binder OK** y que la cronología registre la nueva recuperación.

En una prueba con r55 en el HONOR 200, Binder volvió aproximadamente 5,1 y 12,0 segundos después de las dos caídas registradas. La recuperación más larga incluyó un reintento programado y un puerto inalámbrico anterior que rechazó la conexión. Son mediciones de esa prueba.

## Apoyar el proyecto

Si Nightzuku te sirve, puedes darle una estrella en GitHub para ayudar a que más personas lo encuentren. [Nightzuku en GitHub](https://github.com/joselofarias-byte/Nightzuku)
