package com.medify.medicamentos_backend.scheduler;

import com.medify.medicamentos_backend.service.FirebaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Scheduler que busca y cierra pedidos pendientes de pago antiguos.
 *
 * Los pedidos que queden en estado "pendiente_de_pago" por más de X horas
 * (configurado en orders.pending.age.hours) serán marcados como "abandonada".
 */
@Component
public class OrderCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderCleanupScheduler.class);

    private final FirebaseService firebaseService;

    // Frecuencia del job en milisegundos (por defecto 2 minutos = 120000 ms)
    @Value("${orders.cleanup.fixedRateMs:120000}")
    private long fixedRateMs;

    // Umbral de antigüedad en minutos para considerar un pedido como antiguo (por defecto 5 min)
    @Value("${orders.pending.age.minutes:5}")
    private int pendingAgeMinutes;

    public OrderCleanupScheduler(FirebaseService firebaseService) {
        this.firebaseService = firebaseService;
        log.info("✅ OrderCleanupScheduler inicializado - Limpieza cada {} ms, umbral {} minutos",
                fixedRateMs, pendingAgeMinutes);
    }

    /**
     * Ejecuta la limpieza de pedidos pendientes antiguos.
     * Se ejecuta según el intervalo configurado en orders.cleanup.fixedRateMs
     */
    @Scheduled(
            fixedRateString = "${orders.cleanup.fixedRateMs:120000}",
            initialDelayString = "${orders.cleanup.initialDelayMs:30000}"
    )
    public void cleanupPendingOrders() {
        try {
            log.info("🧹 OrderCleanupScheduler: iniciando limpieza de pedidos pendientes > {} minutos",
                    pendingAgeMinutes);

            // Buscar pedidos antiguos (estado "pendiente_de_pago")
            List<String> pedidos = firebaseService.buscarPedidosPendientesAntiguos(pendingAgeMinutes);

            if (pedidos == null || pedidos.isEmpty()) {
                log.info("✅ OrderCleanupScheduler: no hay pedidos pendientes antiguos para limpiar");
                return;
            }

            log.info("📦 OrderCleanupScheduler: se encontraron {} pedidos antiguos a cerrar", pedidos.size());

            int exitosos = 0;
            int fallidos = 0;

            for (String pedidoId : pedidos) {
                try {
                    firebaseService.marcarPedidoAbandonado(pedidoId);
                    exitosos++;
                    log.debug("🚫 Pedido {} marcado como abandonado", pedidoId);
                } catch (Exception e) {
                    fallidos++;
                    log.error("❌ Error marcando pedido {} como abandonado: {}",
                            pedidoId, e.getMessage(), e);
                }
            }

            log.info("✅ OrderCleanupScheduler: limpieza completada - Exitosos: {}, Fallidos: {}",
                    exitosos, fallidos);

        } catch (Exception e) {
            log.error("❌ OrderCleanupScheduler: error crítico ejecutando la limpieza: {}",
                    e.getMessage(), e);
        }
    }
}