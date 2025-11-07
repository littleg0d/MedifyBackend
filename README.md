# Medicamentos Backend - API de Pagos

Este README resume los endpoints disponibles, el formato que espera el frontend, las posibles salidas/respuestas, la estructura del documento en Firestore y cómo configurar las credenciales de Mercado Pago y Firebase.

## Endpoints

1) POST /api/pagos/crear-preferencia
- Descripción: crea un pedido en Firestore y genera una preferencia de pago en Mercado Pago.
- Content-Type: application/json
- Request body (JSON) ejemplo:

```json
{
  "nombreComercial": "Farmacia La Salud",
  "precio": 1700.00,
  "recetaId": "xORTYX8WnkwGDgaD2oV",
  "userId": "jdiDKUPyY0ZumjxrRWHZT0ORE0H3",
  "farmaciaId": "farm_4",
  "cotizacionId": "G5VsIFvt4U9QdljsqHOO",
  "imagenUrl": "https://images.unsplash.com/158554357343-3b092031a891?w=400",
  "descripcion": "Cotización de receta seleccionada",
  "direccion": {
    "street": "Calle 64 277",
    "city": "La Plata",
    "province": "Buenos Aires",
    "postalCode": "1900"
  }
}
```
- Campos validados por el backend:
  - `nombreComercial` (string) - requerido
  - `precio` (number) - requerido, >= 1
  - `recetaId` (string) - requerido
  - `userId` (string) - requerido
  - `direccion` / `address` (objeto) - validado con campos obligatorios:
    - `street` (string) - requerido
    - `city` (string) - requerido
    - `province` (string) - requerido
    - `postalCode` (string) - requerido
  - `imagenUrl` (string) - si se envia, debe ser URL válida

- Flujo y respuestas:
  1. El backend crea un documento en Firestore (colección `pedidos`) con `estado = "pendiente"` y `fechacreacion = serverTimestamp`. El campo anidado de dirección se guarda exactamente en Firestore con la clave `addressUser`.
  2. Intenta crear una preferencia en Mercado Pago usando `external_reference = pedidoId`.

  - Si la preferencia se crea correctamente:
    - HTTP 200
    - Body JSON: `{ "paymentUrl": "<url_de_pago>" }`
    - El frontend debe abrir esa URL en WebView o navegador.

  - Si Mercado Pago no está configurado (no hay `MERCADOPAGO_ACCESS_TOKEN`) o la creación falla:
    - El backend BORRA el documento `pedidos/{pedidoId}` para no dejar pedidos huérfanos.
    - Si existe la propiedad `mercadopago.failure.url` configurada, el backend responde con HTTP 302 (Location = failureUrl). Algunos clientes (navegadores) seguirán la redirección; desde móviles puede manejarse abriendo la URL de fallo.
    - Si no hay `failureUrl`, el backend devuelve HTTP 503 (o 500) con JSON: `{ "error": "No se pudo crear la preferencia de pago", "detail": "mensaje interno" }`.

2) POST /api/pagos/webhook
- Descripción: webhook para notificaciones de Mercado Pago.
- Body: Mercado Pago postea el payload estándar. El backend:
  - Extrae `payment.id` desde `payload.data.id` o usa el resourceId indicado.
  - Consulta a Mercado Pago con el `paymentId` y verifica el estado.
  - Si el pago está `approved` y el `external_reference` (pedidoId) está presente, actualiza el documento en Firestore:
    - `estado = "pagado"`
    - `fechaPago = serverTimestamp`
    - `paymentId`, `paymentStatus` (opcional)
  - Responde 200 OK siempre (no provocar reintentos automáticos en caso de errores internos).

3) GET /api/pagos/verificar/{paymentId}
- Consulta manual al SDK de Mercado Pago para verificar el pago (usa `PaymentClient.get(paymentId)`).
- Útil para debugging; responde con el objeto `Payment` retornado por el SDK.

4) GET /api/pagos/health
- Devuelve 200 OK y un texto simple para comprobar que la API está corriendo.


## Estructura del documento en Firestore (colección `pedidos`)
Ejemplo de documento creado por `crear-preferencia`:

```json
{
  "recetaId": "xORTY...",
  "farmaciaId": "farm_4",
  "userId": "jdiDKU...",
  "addressUser": {
    "street": "Calle 64 277",
    "city": "La Plata",
    "province": "Buenos Aires",
    "postalCode": "1900"
  },
  "precio": 1700.00,
  "cotizacionId": "G5VsI...",
  "nombreComercial": "Farmacia La Salud",
  "imagenUrl": "https://images.unsplash.com/...",
  "estado": "pendiente",
  "fechacreacion": <serverTimestamp>,
  "fechaPago": null
}
```

Observa: la clave del objeto dirección en Firestore es `addressUser` (tal como pediste).

## Validación
- El backend usa Bean Validation (`jakarta.validation`) en `PreferenciaRequest` y en la clase `Address`:
  - Si falta un campo obligatorio, la respuesta será HTTP 400 con detalles por campo (el GlobalExceptionHandler maneja `MethodArgumentNotValidException`).
  - `imagenUrl` debe ser una URL válida si se envía.

## Configuración necesaria (credenciales)
- Mercado Pago
  - Variable de entorno recomendada: `MERCADOPAGO_ACCESS_TOKEN`.
  - Alternativa (no recomendado): colocar el token directamente en `src/main/resources/application.properties` como `mercadopago.access.token=...`.

- Firebase
  - Opción A (recomendada para local): exportar variable de entorno `GOOGLE_APPLICATION_CREDENTIALS` apuntando al JSON de service account.
    - Windows cmd.exe:
      ```cmd
      set GOOGLE_APPLICATION_CREDENTIALS=C:\ruta\a\firebasesdk.json
      ```
  - Opción B: establecer la propiedad `firebase.service.account.path` en `application.properties` con la ruta absoluta al JSON.
  - Nota: la inicialización de Firebase en `FirebaseService` es tolerante: si no encuentra credenciales no fallará el arranque (solo mostrará advertencia), pero las operaciones hacia Firestore lanzarán excepciones en tiempo de ejecución.

## Cómo ejecutar la API localmente (Windows cmd.exe)
1. Configura las variables de entorno (ejemplo):
```cmd
set MERCADOPAGO_ACCESS_TOKEN=TEST-xxxxxxxxxxxxx
set GOOGLE_APPLICATION_CREDENTIALS=C:\ruta\a\firebasesdk.json
```
2. Ejecuta la aplicación:
```cmd
.\mvnw.cmd spring-boot:run
```
3. Prueba con Postman: POST a `http://localhost:8080/api/pagos/crear-preferencia` con JSON de ejemplo arriba.

## Respuestas esperadas para el frontend (resumen rápido)
- Éxito: 200 OK + `{ "paymentUrl": "..." }` (abrir URL de pago)
- Error al crear preferencia (MP falla): 302 Redirect a `failureUrl` (si configurada) OR 503/500 + JSON de error si no hay `failureUrl`.
- Error de validación: 400 Bad Request + JSON con errores por campo (ej: `{ "errors": { "direccion.street": "street es requerido" } }`).

