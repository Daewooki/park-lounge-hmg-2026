package ai.nc.varcospaces;

import android.car.Car;
import android.car.VehicleGear;
import android.car.VehiclePropertyIds;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Platform UX restrictions AND confirmed Park selection; fail closed on either source. */
final class ParkedState implements AutoCloseable {
    interface Listener { void changed(boolean parked); }
    private final Context context;
    private final Handler handler;
    private final Listener listener;
    private final String reader;
    private final boolean diagnostics;
    private Car car;
    private CarUxRestrictionsManager manager;
    private CarPropertyManager properties;
    private CarPropertyManager.CarPropertyEventCallback gearCallback;
    private CarUxRestrictions restrictions;
    private boolean closed;
    private boolean serviceReady;
    private final GearLease gear = new GearLease();
    private boolean reported, lastAllowed;
    private int generation;
    private final Runnable retry = this::connect;
    private static final long READ_INTERVAL_MS = 1000, READ_TIMEOUT_MS = 1000;
    private long requestSequence, activeRequest;
    private CancellationSignal readCancellation;
    private Runnable readTimeout;
    private boolean useLegacyReader;
    private ExecutorService legacyReader;
    // A timed-out synchronous Binder call cannot be forcibly interrupted. Never queue another.
    private final AtomicBoolean legacyBusy = new AtomicBoolean();
    private final Runnable expireGear = this::emit;
    private final Runnable pollGear = new Runnable() {
        @Override public void run() {
            if (closed || !serviceReady) return;
            emit(); requestGearRead();
            handler.postDelayed(this, READ_INTERVAL_MS);
        }
    };

    ParkedState(Context context, Handler handler, String reader, Listener listener) {
        // Keep the Activity's display association for per-display UX restrictions.
        this.context = context;
        this.handler = handler;
        this.listener = listener;
        this.reader = reader + "#" + Integer.toHexString(System.identityHashCode(this));
        diagnostics = (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }
    void connect() {
        if (closed) return;
        handler.removeCallbacks(retry);
        final int token = ++generation;
        disconnect();
        emit();
        try {
            if (context.checkSelfPermission(Car.PERMISSION_POWERTRAIN) != PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("Powertrain read permission unavailable");
            car = Car.createCar(context, handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT, (connectedCar, ready) -> {
                if (closed || token != generation) return;
                if (!ready) { invalidate(); scheduleRetry(); return; }
                try {
                    serviceReady = true;
                    useLegacyReader = Build.VERSION.SDK_INT < 34;
                    manager = (CarUxRestrictionsManager) connectedCar.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE);
                    properties = (CarPropertyManager) connectedCar.getCarManager(Car.PROPERTY_SERVICE);
                    if (manager == null || properties == null) throw new IllegalStateException("Missing vehicle manager");
                    manager.registerListener(r -> { if (!closed && serviceReady && token == generation) apply(r); });
                    gearCallback = new CarPropertyManager.CarPropertyEventCallback() {
                        @Override public void onChangeEvent(CarPropertyValue value) {
                            if (closed || !serviceReady || token != generation) return;
                            applyGear(value);
                        }
                        @Override public void onErrorEvent(int propertyId, int areaId) {
                            handleError(propertyId, areaId, "unknown");
                        }
                        @Override public void onErrorEvent(int propertyId, int areaId, int errorCode) {
                            handleError(propertyId, areaId, Integer.toString(errorCode));
                        }
                        private void handleError(int propertyId, int areaId, String errorCode) {
                            if (closed || !serviceReady || token != generation) return;
                            if (propertyId == VehiclePropertyIds.GEAR_SELECTION) {
                                diagnostic("gear-event-error area=" + areaId + " errorCode=" + errorCode
                                    + " request=" + activeRequest);
                                cancelRead(); gear.fail(); emit(); requestGearRead();
                            }
                        }
                    };
                    // Events close the gate promptly. Only a successful independent read opens it:
                    // the platform does not guarantee events when availability changes.
                    if (!properties.registerCallback(gearCallback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE))
                        throw new IllegalStateException("Gear subscription unavailable");
                    apply(manager.getCurrentCarUxRestrictions());
                    handler.removeCallbacks(pollGear); pollGear.run();
                    if (restrictions == null) scheduleRetry();
                } catch (RuntimeException e) { invalidate(); scheduleRetry(); }
            });
        } catch (RuntimeException | LinkageError e) { invalidate(); scheduleRetry(); }
    }
    void refresh() {
        if (closed) return;
        try {
            if (serviceReady && manager != null) {
                apply(manager.getCurrentCarUxRestrictions()); requestGearRead();
            } else connect();
        }
        catch (RuntimeException e) { invalidate(); scheduleRetry(); }
    }
    private void apply(CarUxRestrictions r) {
        restrictions = r; emit();
        if (r == null) scheduleRetry();
        else if (serviceReady) handler.removeCallbacks(retry);
    }
    private void applyGear(CarPropertyValue value) {
        boolean available = value != null && value.getPropertyId() == VehiclePropertyIds.GEAR_SELECTION
            && value.getAreaId() == 0 && value.getStatus() == CarPropertyValue.STATUS_AVAILABLE
            && value.getValue() instanceof Integer;
        if (!gear.event(available, available ? (Integer)value.getValue() : 0,
                value == null ? 0 : value.getTimestamp())) return;
        if (!available) diagnostic("gear-event-unavailable status=" + (value == null ? "null" : value.getStatus())
            + " request=" + activeRequest);
        cancelRead();
        emit();
        requestGearRead();
    }
    private void emit() {
        long now = SystemClock.elapsedRealtime();
        boolean allowed = !closed && restrictions != null && SafetyPolicy.mayUseParkedContent(true, serviceReady,
            restrictions.isRequiresDistractionOptimization(), restrictions.getActiveRestrictions(),
            gear.available(now), gear.value == VehicleGear.GEAR_PARK);
        if (!reported || allowed != lastAllowed) {
            diagnostic("allowed=" + allowed + " gearAvailable=" + gear.available(now) + " gear=" + gear.value
                + " ux=" + (restrictions == null ? "unknown" : restrictions.getActiveRestrictions()));
            reported = true; lastAllowed = allowed;
            listener.changed(allowed);
        }
    }
    private void diagnostic(String message) {
        if (diagnostics) Log.d("VarcoParked", "reader=" + reader + " " + message);
    }
    private void invalidate() {
        serviceReady = false; stopValidation(); gear.reset(); restrictions = null; emit();
    }
    private void scheduleRetry() { handler.removeCallbacks(retry); if (!closed) handler.postDelayed(retry, 5000); }
    private void disconnect() {
        serviceReady = false; stopValidation(); gear.reset(); restrictions = null;
        try { if (properties != null && gearCallback != null) properties.unregisterCallback(gearCallback); } catch (RuntimeException ignored) {}
        properties = null; gearCallback = null;
        try { if (manager != null) manager.unregisterListener(); } catch (RuntimeException ignored) {}
        manager = null;
        try { if (car != null) car.disconnect(); } catch (RuntimeException ignored) {}
        car = null;
    }
    @Override public void close() {
        closed = true; ++generation; handler.removeCallbacks(retry); disconnect();
        if (legacyReader != null) legacyReader.shutdownNow();
        emit();
    }

    private void requestGearRead() {
        if (closed || !serviceReady || properties == null || activeRequest != 0) return;
        if (useLegacyReader && legacyBusy.get()) return;
        final int token = generation;
        final long request = ++requestSequence, revision = gear.revision;
        final long started = SystemClock.elapsedRealtime();
        final CarPropertyManager source = properties;
        activeRequest = request;
        readTimeout = () -> finishRead(token, source, request, revision, started, false, 0, 0, "deadline-timeout", true);
        handler.postDelayed(readTimeout, READ_TIMEOUT_MS);
        ReadResult result = (available, value, timestamp, outcome) -> finishRead(token, source, request,
            revision, started, available, value, timestamp, outcome, false);
        try {
            if (!useLegacyReader) {
                readCancellation = new CancellationSignal();
                try {
                    Api34.read(source, handler, readCancellation, result);
                    return;
                } catch (LinkageError | UnsupportedOperationException unsupported) {
                    // Isolate new callback classes from API 29-33 class verification.
                    diagnostic("reader-fallback request=" + request + " type=" + unsupported.getClass().getSimpleName());
                    useLegacyReader = true;
                    if (readCancellation != null) readCancellation.cancel();
                    readCancellation = null;
                }
            }
            readLegacy(source, result);
        } catch (RuntimeException | LinkageError failure) {
            finishRead(token, source, request, revision, started, false, 0, 0,
                "request-exception type=" + failure.getClass().getSimpleName(), true);
        }
    }

    private interface ReadResult { void completed(boolean available, int value, long timestamp, String outcome); }

    /** API 34 symbols stay in a separate class so older Car SDKs use the worker path. */
    private static final class Api34 {
        static void read(CarPropertyManager source, Handler handler, CancellationSignal cancellation,
                ReadResult result) {
            CarPropertyManager.GetPropertyRequest request = source.generateGetPropertyRequest(
                VehiclePropertyIds.GEAR_SELECTION, 0);
            source.getPropertiesAsync(Collections.singletonList(request), READ_TIMEOUT_MS, cancellation,
                command -> handler.post(command), new CarPropertyManager.GetPropertyCallback() {
                    @Override public void onSuccess(CarPropertyManager.GetPropertyResult<?> value) {
                        boolean valid = value != null && value.getRequestId() == request.getRequestId()
                            && value.getPropertyId() == VehiclePropertyIds.GEAR_SELECTION
                            && value.getAreaId() == 0 && value.getValue() instanceof Integer;
                        result.completed(valid, valid ? (Integer)value.getValue() : 0,
                            valid ? value.getTimestampNanos() : 0, valid ? "api34-success" : "api34-invalid-result");
                    }
                    @Override public void onFailure(CarPropertyManager.PropertyAsyncError error) {
                        result.completed(false, 0, 0, "api34-error errorCode=" + (error == null ? "null" : error.getErrorCode())
                            + " platformRequest=" + (error == null ? "null" : error.getRequestId()));
                    }
                });
        }
    }

    private void readLegacy(CarPropertyManager source, ReadResult result) {
        if (!legacyBusy.compareAndSet(false, true)) return;
        if (legacyReader == null) legacyReader = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "VarcoGearRead"); thread.setDaemon(true); return thread;
        });
        try {
            legacyReader.execute(() -> {
                boolean available = false; int value = 0; long timestamp = 0;
                String outcome = "legacy-unavailable";
                try {
                    CarPropertyValue<Integer> reading = source.getProperty(Integer.class, VehiclePropertyIds.GEAR_SELECTION, 0);
                    available = reading != null && reading.getPropertyId() == VehiclePropertyIds.GEAR_SELECTION
                        && reading.getAreaId() == 0 && reading.getStatus() == CarPropertyValue.STATUS_AVAILABLE
                        && reading.getValue() != null;
                    if (available) { value = reading.getValue(); timestamp = reading.getTimestamp(); outcome = "legacy-success"; }
                    else outcome += " status=" + (reading == null ? "null" : reading.getStatus());
                } catch (RuntimeException | LinkageError failure) {
                    // Unavailable, revoked permission and broken Binder all close the gate.
                    outcome = "legacy-exception type=" + failure.getClass().getSimpleName();
                } finally { legacyBusy.set(false); }
                final boolean valid = available; final int selected = value; final long at = timestamp;
                final String completedOutcome = outcome;
                handler.post(() -> result.completed(valid, selected, at, completedOutcome));
            });
        } catch (RejectedExecutionException failure) { legacyBusy.set(false); throw failure; }
    }

    private void finishRead(int token, CarPropertyManager source, long request, long revision,
            long started, boolean available, int value, long timestamp, String outcome, boolean cancelPending) {
        if (closed || !serviceReady || token != generation || source != properties || request != activeRequest) return;
        long now = SystemClock.elapsedRealtime();
        // Terminal callbacks already completed the platform request. Do not send a second
        // cancellation: another CarPropertyManager may have the same manager-local request ID.
        clearRead(cancelPending);
        if (!available || now - started >= READ_TIMEOUT_MS) {
            diagnostic("read-failed request=" + request + " outcome=" + outcome + " elapsedMs=" + (now - started)
                + " deadlineExceeded=" + (now - started >= READ_TIMEOUT_MS));
            gear.fail();
        } else {
            if (!gear.confirm(revision, value, timestamp, started))
                diagnostic("read-rejected request=" + request + " outcome=" + outcome + " elapsedMs=" + (now - started)
                    + " revision=" + revision + " currentRevision=" + gear.revision);
        }
        handler.removeCallbacks(expireGear);
        if (gear.available(now)) handler.postDelayed(expireGear, GearLease.MAX_AGE_MS - (now - gear.checkedAt));
        emit();
    }

    private void cancelRead() { clearRead(true); }
    private void clearRead(boolean cancelPending) {
        activeRequest = 0;
        if (readTimeout != null) handler.removeCallbacks(readTimeout);
        readTimeout = null;
        CancellationSignal previous = readCancellation; readCancellation = null;
        if (cancelPending && previous != null) try { previous.cancel(); } catch (RuntimeException ignored) { }
    }
    private void stopValidation() {
        handler.removeCallbacks(pollGear); handler.removeCallbacks(expireGear); cancelRead();
    }

    /** Pure state used by both native reads and deterministic host safety tests. */
    static final class GearLease {
        static final long MAX_AGE_MS = 2500;
        long revision, checkedAt, latestTimestamp = Long.MIN_VALUE;
        int value;
        boolean readValid;
        boolean event(boolean available, int selected, long timestamp) {
            if (available && timestamp < latestTimestamp) return false;
            ++revision; latestTimestamp = Math.max(latestTimestamp, timestamp);
            // P notifications cannot open or renew a lease. A changed/non-P event closes it.
            if (!available || selected != VehicleGear.GEAR_PARK || value != selected) readValid = false;
            value = available ? selected : 0;
            return true;
        }
        boolean confirm(long requestRevision, int selected, long timestamp, long requestedAt) {
            if (requestRevision != revision || timestamp < latestTimestamp || timestamp < 0) return false;
            latestTimestamp = timestamp; value = selected; checkedAt = requestedAt; readValid = true;
            return true;
        }
        boolean available(long now) {
            return readValid && now >= checkedAt && now - checkedAt < MAX_AGE_MS;
        }
        void fail() { readValid = false; value = 0; ++revision; }
        void reset() { fail(); latestTimestamp = Long.MIN_VALUE; checkedAt = 0; }
    }
}
