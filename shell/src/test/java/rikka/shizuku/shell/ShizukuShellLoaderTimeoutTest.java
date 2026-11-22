package rikka.shizuku.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class ShizukuShellLoaderTimeoutTest {

    private static final class FakeScheduler implements ShizukuShellLoader.TimeoutScheduler {
        Runnable scheduledTask;
        long scheduledDelayMs = -1L;
        int cancelCount = 0;

        @Override
        public void schedule(Runnable task, long delayMs) {
            this.scheduledTask = task;
            this.scheduledDelayMs = delayMs;
        }

        @Override
        public void cancel(Runnable task) {
            cancelCount++;
            if (this.scheduledTask == task) {
                this.scheduledTask = null;
            }
        }
    }

    @Test
    public void timeoutAbortaSiNoLlegaBinderEnCincoSegundos() {
        FakeScheduler scheduler = new FakeScheduler();
        AtomicInteger aborts = new AtomicInteger(0);
        ShizukuShellLoader.BinderAcquisitionGuard guard =
                new ShizukuShellLoader.BinderAcquisitionGuard(scheduler, aborts::incrementAndGet);

        guard.arm(ShizukuShellLoader.BINDER_ACQUISITION_TIMEOUT_MS);
        assertEquals(5000L, scheduler.scheduledDelayMs);
        assertNotNull(scheduler.scheduledTask);

        scheduler.scheduledTask.run();
        assertEquals(1, aborts.get());
    }

    @Test
    public void adquirirBinderCancelaTimeoutYNoCortaSesionMayorACincoSegundos() {
        FakeScheduler scheduler = new FakeScheduler();
        AtomicInteger aborts = new AtomicInteger(0);
        ShizukuShellLoader.BinderAcquisitionGuard guard =
                new ShizukuShellLoader.BinderAcquisitionGuard(scheduler, aborts::incrementAndGet);

        guard.arm(ShizukuShellLoader.BINDER_ACQUISITION_TIMEOUT_MS);
        Runnable capturedTask = scheduler.scheduledTask;
        assertNotNull(capturedTask);

        assertTrue(guard.markBinderReceived());
        assertNull("El timeout debe cancelarse al recibir Binder", scheduler.scheduledTask);
        assertEquals(1, scheduler.cancelCount);

        // Even if the delayed callback was already queued/raced at >5s, it must not abort an active session.
        capturedTask.run();
        assertEquals(0, aborts.get());
    }

    @Test
    public void entregaDuplicadaDeBinderSeDescarta() {
        FakeScheduler scheduler = new FakeScheduler();
        AtomicInteger aborts = new AtomicInteger(0);
        ShizukuShellLoader.BinderAcquisitionGuard guard =
                new ShizukuShellLoader.BinderAcquisitionGuard(scheduler, aborts::incrementAndGet);

        guard.arm(ShizukuShellLoader.BINDER_ACQUISITION_TIMEOUT_MS);
        assertTrue(guard.markBinderReceived());
        assertFalse(guard.markBinderReceived());
        assertEquals(0, aborts.get());
    }

    @Test
    public void binderRecibidoAntesDeArmarImpideProgramarTimeout() {
        FakeScheduler scheduler = new FakeScheduler();
        AtomicInteger aborts = new AtomicInteger(0);
        ShizukuShellLoader.BinderAcquisitionGuard guard =
                new ShizukuShellLoader.BinderAcquisitionGuard(scheduler, aborts::incrementAndGet);

        assertTrue(guard.markBinderReceived());
        guard.arm(ShizukuShellLoader.BINDER_ACQUISITION_TIMEOUT_MS);
        assertNull(scheduler.scheduledTask);
        assertEquals(0, aborts.get());
    }

    @Test
    public void binderTardioDespuesDeTimeoutSeDescarta() {
        FakeScheduler scheduler = new FakeScheduler();
        AtomicInteger aborts = new AtomicInteger(0);
        ShizukuShellLoader.BinderAcquisitionGuard guard =
                new ShizukuShellLoader.BinderAcquisitionGuard(scheduler, aborts::incrementAndGet);

        guard.arm(ShizukuShellLoader.BINDER_ACQUISITION_TIMEOUT_MS);
        scheduler.scheduledTask.run();
        assertEquals(1, aborts.get());
        assertFalse(guard.markBinderReceived());
    }

    @Test
    public void sesionRishProlongadaTrasRecibirBinderNuncaDisparaAbortoPorTimeout() {
        FakeScheduler scheduler = new FakeScheduler();
        AtomicInteger aborts = new AtomicInteger(0);
        ShizukuShellLoader.BinderAcquisitionGuard guard =
                new ShizukuShellLoader.BinderAcquisitionGuard(scheduler, aborts::incrementAndGet);

        guard.arm(ShizukuShellLoader.BINDER_ACQUISITION_TIMEOUT_MS);
        Runnable scheduled = scheduler.scheduledTask;
        assertNotNull(scheduled);

        // Binder llega dentro de la ventana y luego la sesión rish corre >10s (con cancelaciones adicionales).
        assertTrue(guard.markBinderReceived());
        guard.cancel();
        guard.arm(ShizukuShellLoader.BINDER_ACQUISITION_TIMEOUT_MS);
        assertNull("Re-armar después de adquirir Binder no debe programar timeout", scheduler.scheduledTask);

        // Simula ejecución tardía del callback antiguo más allá de 5s
        scheduled.run();
        assertEquals(0, aborts.get());
    }
}
