package com.circulation.prototype_eng.storage;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKeyTypes;
import com.circulation.prototype_eng.api.energy.EnergyHatchConfiguration;
import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.HatchReconfigurationStatus;
import com.circulation.prototype_eng.api.storage.HatchTier;
import com.circulation.prototype_eng.api.storage.ResourceHatchConfiguration;
import com.circulation.prototype_eng.energy.EnergyHatchBlockEntity;
import com.circulation.prototype_eng.scheduler.TaskScheduler;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.Reference2LongMaps;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HatchConfigurationRequestTest {
    private static final KeyType TYPE = new KeyType("configuration_request");
    private static final Key KEY = new Key(TYPE, "stored");

    static {
        AEKeyTypes.register(TYPE);
    }

    private static ResourceHatchConfiguration resource(int slots, long capacity) {
        return new ResourceHatchConfiguration(new ResourceHatchConfiguration.Side(slots, Reference2LongMaps.singleton(TYPE, capacity)), null);
    }

    private static EnergyHatchConfiguration energy(long capacity) {
        return EnergyHatchConfiguration.symmetric(HatchMode.INPUT, capacity, 1000);
    }

    private static void rejectForeign(Runnable request) {
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> assertThrows(IllegalStateException.class, request::run)).get(5, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError("Foreign request did not reject cleanly", failure);
        }
    }

    @Test
    void rejectedForeignRequestsCannotClearOrReplaceAlreadyQueuedPublicHostRequests() {
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 1, 1)) {
            var resource = new ResourceHost(scheduler);
            var energy = new EnergyHost(scheduler);
            var originalResource = resource.storage();
            var originalEnergy = energy.storage();
            var resourceTarget = resource(3, 10);
            var energyTarget = energy(1000);
            // Even the no-target and null/equal-target short circuits must enforce owner admission.
            rejectForeign(() -> resource.requestConfiguration(resource.effectiveConfiguration()));
            rejectForeign(() -> energy.requestConfiguration(null));
            scheduler.submit(() -> {
                assertEquals(HatchReconfigurationStatus.PENDING_BUSY, resource.requestConfiguration(resourceTarget));
                assertEquals(HatchReconfigurationStatus.PENDING_BUSY, energy.requestConfiguration(energyTarget));
                rejectForeign(() -> resource.requestConfiguration(resource.effectiveConfiguration()));
                rejectForeign(() -> resource.requestConfiguration(resourceTarget));
                rejectForeign(() -> resource.requestConfiguration(resource(4, 7)));
                rejectForeign(() -> energy.requestConfiguration(energy.effectiveConfiguration()));
                rejectForeign(() -> energy.requestConfiguration(energyTarget));
                rejectForeign(() -> energy.requestConfiguration(energy(2000)));
                rejectForeign(() -> resource.requestConfiguration(null));
                assertSame(originalResource, resource.storage());
                assertSame(originalEnergy, energy.storage());
                resource.current = false;
                energy.current = false;
                assertThrows(IllegalStateException.class, () -> resource.requestConfiguration(resourceTarget));
                assertThrows(IllegalStateException.class, () -> energy.requestConfiguration(energyTarget));
                resource.current = true;
                energy.current = true;
            }, ExecutionMode.MAIN_THREAD);
            scheduler.tick(); // The original actions must still run after the main callback returns.
            assertEquals(HatchReconfigurationStatus.APPLIED, resource.configurationStatus());
            assertEquals(HatchReconfigurationStatus.APPLIED, energy.configurationStatus());
            assertEquals(resourceTarget, resource.effectiveConfiguration());
            assertEquals(energyTarget, energy.effectiveConfiguration());
            assertNotSame(originalResource, resource.storage());
            assertNotSame(originalEnergy, energy.storage());
            assertEquals(12, Objects.requireNonNull(resource.storage().input()).amount(KEY));
            assertEquals(100, energy.storage().storedEnergy());
        }
    }

    @Test
    void rejectedForeignRequestsCannotDiscardCapacityBlockedTargetsOrTheirSubsequentRetry() {
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 1, 1)) {
            var resource = new ResourceHost(scheduler);
            var energy = new EnergyHost(scheduler);
            var resourceTarget = resource(1, 5);
            var energyTarget = energy(50);
            assertEquals(HatchReconfigurationStatus.BLOCKED_CAPACITY, resource.requestConfiguration(resourceTarget));
            assertEquals(HatchReconfigurationStatus.BLOCKED_CAPACITY, energy.requestConfiguration(energyTarget));
            var originalResource = resource.storage();
            var originalEnergy = energy.storage();
            rejectForeign(() -> resource.requestConfiguration(resource.effectiveConfiguration()));
            rejectForeign(() -> resource.requestConfiguration(resourceTarget));
            rejectForeign(() -> energy.requestConfiguration(energy.effectiveConfiguration()));
            rejectForeign(() -> energy.requestConfiguration(energyTarget));
            rejectForeign(() -> energy.requestConfiguration(null));
            assertEquals(8, Objects.requireNonNull(originalResource.input()).extract(KEY, 8, Actionable.MODULATE));
            assertEquals(32, originalEnergy.extractEnergy(32, Actionable.MODULATE));
            assertEquals(68, originalEnergy.storedEnergy());
            energy.update();
            assertEquals(HatchReconfigurationStatus.BLOCKED_CAPACITY, energy.configurationStatus());
            assertSame(originalEnergy, energy.storage());
            assertEquals(28, originalEnergy.extractEnergy(28, Actionable.MODULATE));
            assertEquals(40, originalEnergy.storedEnergy());
            resource.update();
            energy.update();
            assertEquals(HatchReconfigurationStatus.APPLIED, resource.configurationStatus());
            assertEquals(HatchReconfigurationStatus.APPLIED, energy.configurationStatus());
            assertNotSame(originalResource, resource.storage());
            assertNotSame(originalEnergy, energy.storage());
            assertEquals(4, Objects.requireNonNull(resource.storage().input()).amount(KEY));
            assertEquals(40, energy.storage().storedEnergy());
            assertEquals(resourceTarget, resource.effectiveConfiguration());
            assertEquals(energyTarget, energy.effectiveConfiguration());
        }
    }

    /**
     * Only the host-environment observation is supplied; request, queue, scheduler and buffers are real.
     */
    private static final class ResourceHost extends ResourceHatchBlockEntity {
        private final TaskScheduler scheduler;
        private boolean current = true;

        private ResourceHost(TaskScheduler scheduler) {
            this.scheduler = scheduler;
            var tag = new NBTTagCompound();
            tag.setString("Mode", "INPUT");
            tag.setInteger("Tier", 1);
            tag.setInteger("Slots", 2);
            var capacities = new NBTTagCompound();
            capacities.setLong(TYPE.getId().toString(), 20);
            tag.setTag("Capacities", capacities);
            loadTag(tag);
            Objects.requireNonNull(storage().input()).insert(KEY, 12, Actionable.MODULATE);
        }

        @Override
        protected HatchConfigurationRequest.Boundary configurationBoundary() {
            return new HatchConfigurationRequest.Boundary(scheduler, () -> current);
        }
    }

    private static final class EnergyHost extends EnergyHatchBlockEntity {
        private final TaskScheduler scheduler;
        private boolean current = true;

        private EnergyHost(TaskScheduler scheduler) {
            super(HatchMode.INPUT, HatchTier.LV1);
            this.scheduler = scheduler;
            storage().restore(100);
        }

        @Override
        protected HatchConfigurationRequest.Boundary configurationBoundary() {
            return new HatchConfigurationRequest.Boundary(scheduler, () -> current);
        }
    }
}
