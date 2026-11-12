package com.circulation.prototype_eng.machine;

import ae2.api.config.Actionable;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.MultithreadController;
import com.circulation.prototype_eng.api.machine.attribute.AttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.NumericAttributeModifier;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.component.MachineScheduling;
import com.circulation.prototype_eng.api.machine.component.MachineSystem;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleHost;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleListener;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleRegistry;
import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.machine.persistence.MachineComponentCodec;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlotsNbt;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotDefinition;
import com.circulation.prototype_eng.api.persistence.AttributeModifierNbt;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.scheduler.ControllerExecutionPolicy;
import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.localization.RecipeText;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("resource") // Fixture-local registries own subscriptions throughout each scenario.
class MachineAssemblyTest {
    private static ResourceLocation id(String path) {
        return new ResourceLocation("machine_assembly_test", path);
    }

    private static MachineType.Builder declaration() {
        return MachineType.builder(id("machine"), RecipeText.Cancelled, id("shape"));
    }

    private static ComponentType<MachineInstance, State> stateType(String name, Function<MachineInstance, State> factory) {
        return new ComponentType<>(id(name), factory, List.of());
    }

    private static ComponentType<MachineInstance, ImmutableState> immutableType(String name, List<String> log, Runnable factoryAction) {
        var system = new MachineSystem<ImmutableState>() {
            @Override
            public ResourceLocation id() {
                return MachineAssemblyTest.id(name + "_tick");
            }

            @Override
            public void onTick(MachineInstance owner, ImmutableState component) {
                log.add("tick:" + component.name());
            }
        };
        return new ComponentType<>(id(name), _ -> {
            factoryAction.run();
            return new ImmutableState(17, name, log);
        }, List.of(system));
    }

    private static NBTTagCompound valueTag(int value) {
        var tag = new NBTTagCompound();
        tag.setInteger("Value", value);
        return tag;
    }

    private static NBTTagCompound entry(NBTTagCompound saved, ComponentType<MachineInstance, ?> component) {
        var entries = saved.getTagList("Components", 10);
        for (int i = 0; i < entries.tagCount(); i++) {
            var entry = entries.getCompoundTagAt(i);
            if (entry.getString("Type").equals(component.id().toString())) {
                return entry;
            }
        }
        throw new AssertionError("Missing saved component " + component.id());
    }

    @SafeVarargs
    private static NBTTagList dependencyList(ComponentType<MachineInstance, ?>... dependencies) {
        var tags = new NBTTagList();
        for (var dependency : dependencies) {
            tags.appendTag(new NBTTagString(dependency.id().toString()));
        }
        return tags;
    }

    private static MachineLifecycleRegistry recordingRegistry(List<String> events) {
        var registry = new MachineLifecycleRegistry();
        registry.register(new MachineLifecycleListener() {
            @Override
            public void onLoaded(MachineLifecycleEvent.Loaded event) {
                events.add("loaded");
            }

            @Override
            public void onUnloaded(MachineLifecycleEvent.Unloaded event) {
                events.add("unloaded:" + event.reason());
            }
        });
        return registry;
    }

    private static void assertStopped(MachineInstance machine, MachineAssembly assembly) {
        assertThrows(IllegalStateException.class, machine::onSchedule);
        assertThrows(IllegalStateException.class, () -> assembly.encode(machine));
    }

    @Test
    void explicitControllerPolicySurvivesPersistenceAndSavedSelectionOverridesTheDeclaration() {
        for (var policy : ControllerExecutionPolicy.VALUES) {
            var type = declaration().executionMode(ExecutionMode.MAIN_THREAD).executionPolicy(policy).build();
            var assembly = MachineAssembly.builder(type, "v1").instanceFactory(MultithreadController::new).build();
            var owner = assembly.create(new Host(), new MachineLifecycleRegistry());
            assertEquals(ExecutionMode.PLANNED_PARALLEL, owner.executionMode());
            assertEquals(policy, owner.executionPolicy());
            var saved = assembly.encode(owner);
            assertEquals(policy.name(), entry(saved, MachineScheduling.TYPE).getCompoundTag("Data").getString("Policy"));
            var restored = assembly.restore(new Host(), saved, new MachineLifecycleRegistry());
            assertEquals(policy, restored.executionPolicy());
            assertEquals(saved, assembly.encode(restored));
            restored.components().put(MachineScheduling.TYPE, new MachineScheduling(ExecutionMode.CONCURRENT, ControllerExecutionPolicy.SYNCHRONOUS));
            var changed = assembly.restore(new Host(), assembly.encode(restored), new MachineLifecycleRegistry());
            assertEquals(ControllerExecutionPolicy.SYNCHRONOUS, changed.executionPolicy());
            assertEquals(ExecutionMode.PLANNED_PARALLEL, changed.executionMode());
        }
    }

    @Test
    void legacySchedulingNbtKeepsItsOriginalModeAndRejectsMalformedPolicy() {
        var type = declaration().executionPolicy(ControllerExecutionPolicy.FULL_ASYNC).build();
        var assembly = MachineAssembly.builder(type, "v1").instanceFactory(MultithreadController::new).build();
        var saved = assembly.encode(assembly.create(new Host(), new MachineLifecycleRegistry()));
        var scheduling = entry(saved, MachineScheduling.TYPE).getCompoundTag("Data");
        scheduling.removeTag("Policy");
        scheduling.setString("Mode", ExecutionMode.MAIN_THREAD.name());
        var legacy = assembly.restore(new Host(), saved, new MachineLifecycleRegistry());
        assertNull(legacy.executionPolicy());
        assertEquals(ExecutionMode.MAIN_THREAD, legacy.executionMode());
        assertFalse(entry(assembly.encode(legacy), MachineScheduling.TYPE).getCompoundTag("Data").hasKey("Policy"));
        scheduling.setInteger("Policy", 1);
        assertThrows(IllegalArgumentException.class, () -> assembly.restore(new Host(), saved, new MachineLifecycleRegistry()));
        scheduling.setString("Policy", "UNKNOWN");
        assertThrows(IllegalArgumentException.class, () -> assembly.restore(new Host(), saved, new MachineLifecycleRegistry()));
    }

    @Test
    void factoriesSeeAttachedHostAndDependencyRestorationFinishesBeforeLoadedPublication() {
        var host = new Host();
        var log = new ObjectArrayList<String>();
        var first = stateType("first", owner -> {
            assertSame(host, Objects.requireNonNull(owner.lifecycle()).host());
            owner.requirePersistenceBoundary();
            log.add("factory:first");
            return new State();
        });
        var second = new ComponentType<>(id("second"), owner -> {
            assertSame(host, Objects.requireNonNull(owner.lifecycle()).host());
            log.add("factory:second");
            return new State();
        }, List.of(), List.of(first));
        var type = declaration().component(second).component(first).attribute(id("speed"), 6).build();
        var assembly = MachineAssembly.builder(type, "v1")
                                      .component(first, new StateCodec() {
                                          @Override
                                          public State restore(MachineInstance owner, State fresh, NBTTagCompound tag) {
                                              log.add("restore:first");
                                              return super.restore(owner, fresh, tag);
                                          }
                                      }).component(second, new StateCodec() {
                @Override
                public State restore(MachineInstance owner, State fresh, NBTTagCompound tag) {
                    assertEquals(41, Objects.requireNonNull(owner.components().get(first)).value);
                    assertEquals(9, owner.attributes().value(id("speed")));
                    log.add("restore:second");
                    return super.restore(owner, fresh, tag);
                }
            }).build();
        var original = assembly.create(host, new MachineLifecycleRegistry());
        Objects.requireNonNull(original.components().get(first)).value = 41;
        Objects.requireNonNull(original.components().get(second)).value = 82;
        original.attributes().setBase(id("speed"), 9);
        var saved = assembly.encode(original);
        log.clear();
        var registry = new MachineLifecycleRegistry();
        registry.register(new MachineLifecycleListener() {
            @Override
            public void onLoaded(MachineLifecycleEvent.Loaded event) {
                assertSame(MachineLifecycleEvent.LoadBoundary.RESTORED, event.boundary());
                assertFalse(event.context().formed());
                assertEquals(41, Objects.requireNonNull(event.context().machine().components().get(first)).value);
                assertEquals(82, Objects.requireNonNull(event.context().machine().components().get(second)).value);
                assertEquals(9, event.context().machine().attributes().value(id("speed")));
                log.add("loaded");
            }
        });
        var restored = assembly.restore(host, saved, registry);
        assertEquals(List.of("factory:second", "factory:first", "restore:first", "restore:second", "loaded"), log);
        restored.onSchedule();
        assertEquals(saved, assembly.encode(restored));
    }

    @Test
    void defaultsRespectFactoryAttributesAndRestoreWholeAttributeIdsBeforeSupplementingMissingDefaults() {
        var factoryBase = id("factory_base");
        var missing = id("missing");
        var modifierOnly = id("modifier_only");
        var factory = stateType("attribute_factory", owner -> {
            owner.attributes().setBase(factoryBase, 15);
            owner.attributes().setBase(modifierOnly, 27);
            return new State();
        });
        var type = declaration().component(factory).attribute(factoryBase, 1).attribute(missing, 2).attribute(modifierOnly, 100).build();
        var assembly = MachineAssembly.builder(type, "v1").component(factory, new StateCodec() {
                                          @Override
                                          public State restore(MachineInstance owner, State fresh, NBTTagCompound tag) {
                                              assertEquals(19, owner.attributes().value(factoryBase));
                                              assertEquals(2, owner.attributes().value(missing));
                                              assertEquals(3, owner.attributes().value(modifierOnly));
                                              assertNull(Objects.requireNonNull(owner.attributes().localSnapshot().get(modifierOnly)).baseOverride());
                                              return super.restore(owner, fresh, tag);
                                          }
                                      })
                                      .attributes(new AttributeModifierNbt()).build();
        var original = assembly.create(new Host(), new MachineLifecycleRegistry());
        assertEquals(15, original.attributes().value(factoryBase));
        original.attributes().setBase(factoryBase, 19);
        original.attributes().clearBase(missing);
        original.attributes().clearBase(modifierOnly);
        original.attributes().addModifier(modifierOnly, new NumericAttributeModifier("bonus", 3, AttributeModifier.Operation.ADDITION));
        var restored = assembly.restore(new Host(), assembly.encode(original), new MachineLifecycleRegistry());
        assertEquals(19, restored.attributes().value(factoryBase));
        assertEquals(2, restored.attributes().value(missing));
        assertEquals(3, restored.attributes().value(modifierOnly));
        assertNull(Objects.requireNonNull(restored.attributes().localSnapshot().get(modifierOnly)).baseOverride());
    }

    @Test
    void twoRestoresAndSavedOutputDoNotShareMutableTagsOrComponentState() {
        var component = stateType("tag_owner", _ -> new State());
        var codec = new StateCodec() {
            @Override
            public NBTTagCompound encode(MachineInstance owner, State state) {
                return state.tag;
            }

            @Override
            public State restore(MachineInstance owner, State fresh, NBTTagCompound tag) {
                fresh.tag = tag;
                return fresh;
            }
        };
        var assembly = MachineAssembly.builder(declaration().component(component).build(), "v1").component(component, codec).build();
        var original = assembly.create(new Host(), new MachineLifecycleRegistry());
        var initial = Objects.requireNonNull(original.components().get(component));
        initial.tag.setInteger("Counter", 7);
        var saved = assembly.encode(original);
        initial.tag.setInteger("Counter", 99);
        var first = assembly.restore(new Host(), saved, new MachineLifecycleRegistry());
        var second = assembly.restore(new Host(), saved, new MachineLifecycleRegistry());
        var left = Objects.requireNonNull(first.components().get(component));
        var right = Objects.requireNonNull(second.components().get(component));
        assertNotSame(left, right);
        assertNotSame(left.tag, right.tag);
        left.tag.setInteger("Counter", 12);
        saved.getTagList("Components", 10).getCompoundTagAt(0).getCompoundTag("Data").setInteger("Counter", 33);
        assertEquals(7, right.tag.getInteger("Counter"));
        assertEquals(12, entry(assembly.encode(first), component).getCompoundTag("Data").getInteger("Counter"));
        assertEquals(7, entry(assembly.encode(second), component).getCompoundTag("Data").getInteger("Counter"));
    }

    @Test
    void immutableReplacementsPreserveActualAssemblyOrderDynamicEdgesDeletedDeclarationsAndPendingDependents() {
        var log = new ObjectArrayList<String>();
        int[] removedFactories = {0};
        var first = immutableType("first", log, () -> {
        });
        var dependent = immutableType("dependent", log, () -> {
        });
        var removed = immutableType("removed", log, () -> removedFactories[0]++);
        var dynamic = immutableType("dynamic", log, () -> {
        });
        var pending = immutableType("pending", log, () -> {
        });
        var codec = new MachineComponentCodec<ImmutableState>() {
            @Override
            public NBTTagCompound encode(MachineInstance owner, ImmutableState state) {
                return valueTag(state.value());
            }

            @Override
            public ImmutableState restore(MachineInstance owner, ImmutableState fresh, NBTTagCompound tag) {
                log.add("restore:" + fresh.name());
                return new ImmutableState(tag.getInteger("Value"), fresh.name(), fresh.log());
            }
        };
        var assembly = MachineAssembly.builder(declaration().component(first).component(dependent).component(removed).build(), "v1")
                                      .component(first, codec).component(dependent, codec).component(removed, codec).component(dynamic, codec).component(pending, codec).build();
        var original = assembly.create(new Host(), new MachineLifecycleRegistry());
        original.components().remove(removed);
        original.components().remove(first);
        original.components().create(first, original);
        original.components().create(dynamic, original);
        original.components().addDependency(dependent, dynamic);
        original.components().addDependency(dynamic, first);
        original.components().addDependency(pending, dependent);
        var saved = assembly.encode(original);
        log.clear();
        var restored = assembly.restore(new Host(), saved, new MachineLifecycleRegistry());
        assertEquals(List.of("restore:first", "restore:dynamic", "restore:dependent",
            "load:first", "load:dynamic", "load:dependent"), log);
        assertEquals(1, removedFactories[0]);
        assertNull(restored.components().get(removed));
        assertEquals(saved, assembly.encode(restored));
        log.clear();
        restored.onSchedule();
        assertEquals(List.of("tick:first", "tick:dynamic", "tick:dependent"), log);
        restored.components().create(pending, restored);
        log.clear();
        restored.onSchedule();
        assertEquals(List.of("tick:first", "tick:dynamic", "tick:dependent", "tick:pending"), log);
    }

    @Test
    void invalidMetadataUnknownDuplicateAndCyclicSavedComponentsAreRejectedBeforeAnyFactoryRuns() {
        int[] factories = {0};
        var first = stateType("first", _ -> {
            factories[0]++;
            return new State();
        });
        var second = stateType("second", _ -> {
            factories[0]++;
            return new State();
        });
        var assembly = MachineAssembly.builder(declaration().component(first).component(second).build(), "v1")
                                      .component(first, new StateCodec()).component(second, new StateCodec()).build();
        var saved = assembly.encode(assembly.create(new Host(), new MachineLifecycleRegistry()));
        List<Consumer<NBTTagCompound>> corruptions = List.of(
            tag -> tag.setString("Revision", "old"),
            tag -> tag.setString("Type", id("other").toString()),
            tag -> tag.setInteger("Version", 99),
            tag -> entry(tag, second).setString("Type", id("unknown").toString()),
            tag -> tag.getTagList("Components", 10).appendTag(entry(tag, first).copy()),
            tag -> {
                entry(tag, first).setTag("Dependencies", dependencyList(second));
                entry(tag, second).setTag("Dependencies", dependencyList(first));
            },
            tag -> entry(tag, first).setTag("Dependencies", dependencyList(first, first)),
            tag -> entry(tag, first).setTag("Dependencies", dependencyList(stateType("unknown_dependency", _ -> new State()))),
            tag -> entry(tag, second).setString("Type", MachineHatches.TYPE.id().toString())
        );
        for (var corruption : corruptions) {
            var broken = saved.copy();
            corruption.accept(broken);
            factories[0] = 0;
            assertThrows(RuntimeException.class, () -> assembly.restore(new Host(), broken, new MachineLifecycleRegistry()));
            assertEquals(0, factories[0]);
        }
    }

    @Test
    void factoryCannotPublishExtraComponentsAndFailedAssemblyNeverNotifiesLoadedListeners() {
        var captured = new MachineInstance[1];
        var extra = stateType("extra", _ -> new State());
        var illegal = stateType("illegal_factory", owner -> {
            captured[0] = owner;
            owner.components().create(extra, owner);
            return new State();
        });
        var assembly = MachineAssembly.builder(declaration().component(illegal).build(), "v1")
                                      .component(illegal, new StateCodec()).component(extra, new StateCodec()).build();
        var events = new ObjectArrayList<String>();
        assertThrows(IllegalStateException.class, () -> assembly.create(new Host(), recordingRegistry(events)));
        assertTrue(events.isEmpty());
        assertStopped(captured[0], assembly);
    }

    @Test
    void recreatedComponentsReceiveFreshFactoryStateAndRejectInventedPersistentDataBeforeFactories() {
        int[] factories = {0};
        var recreated = stateType("recreated", _ -> {
            factories[0]++;
            var state = new State();
            state.value = 4;
            return state;
        });
        var assembly = MachineAssembly.builder(declaration().component(recreated).build(), "v1").recreated(recreated).build();
        var original = assembly.create(new Host(), new MachineLifecycleRegistry());
        Objects.requireNonNull(original.components().get(recreated)).value = 31;
        var saved = assembly.encode(original);
        var restored = assembly.restore(new Host(), saved, new MachineLifecycleRegistry());
        assertEquals(4, Objects.requireNonNull(restored.components().get(recreated)).value);
        assertEquals(saved, assembly.encode(restored));
        entry(saved, recreated).getCompoundTag("Data").setInteger("Value", 31);
        factories[0] = 0;
        assertThrows(IllegalArgumentException.class, () -> assembly.restore(new Host(), saved, new MachineLifecycleRegistry()));
        assertEquals(0, factories[0]);
    }

    @Test
    void restoreCodecsAndValidatorsCannotChangeOwnerGraphOrAttributesBeforeLoad() {
        for (int mode = 0; mode < 4; mode++) {
            int selected = mode;
            boolean[] corrupt = {false};
            var captured = new MachineInstance[1];
            var component = stateType("mutation_target", owner -> {
                captured[0] = owner;
                return new State();
            });
            Consumer<MachineInstance> mutation = owner -> {
                if ((selected & 1) == 0) {
                    owner.components().addDependency(component, component);
                } else {
                    owner.attributes().setBase(id("unexpected"), 2);
                }
            };
            var codec = new StateCodec() {
                @Override
                public State restore(MachineInstance owner, State fresh, NBTTagCompound tag) {
                    if (corrupt[0] && selected < 2) {
                        mutation.accept(owner);
                    }
                    return super.restore(owner, fresh, tag);
                }
            };
            var assembly = MachineAssembly.builder(declaration().component(component).build(), "v1").component(component, codec)
                                          .validator(owner -> {
                                              if (corrupt[0] && selected >= 2) {
                                                  mutation.accept(owner);
                                              }
                                          }).build();
            var saved = assembly.encode(assembly.create(new Host(), new MachineLifecycleRegistry()));
            corrupt[0] = true;
            var events = new ObjectArrayList<String>();
            assertThrows(IllegalStateException.class, () -> assembly.restore(new Host(), saved, recordingRegistry(events)));
            assertTrue(events.isEmpty());
            assertStopped(captured[0], assembly);
        }
    }

    @Test
    void restoredReplacementIsValidatedBeforePublicationAndInvalidPayloadFaultsTheNewOwner() {
        var captured = new MachineInstance[1];
        var component = stateType("validated", owner -> {
            captured[0] = owner;
            return new State();
        });
        var codec = new StateCodec() {
            @Override
            public State restore(MachineInstance owner, State fresh, NBTTagCompound tag) {
                var replacement = new State();
                replacement.value = tag.getInteger("Value");
                return replacement;
            }

            @Override
            public void validate(MachineInstance owner, State state) {
                if (state.value < 0) {
                    throw new IllegalArgumentException("Negative saved value");
                }
            }
        };
        var assembly = MachineAssembly.builder(declaration().component(component).build(), "v1").component(component, codec).build();
        var saved = assembly.encode(assembly.create(new Host(), new MachineLifecycleRegistry()));
        entry(saved, component).getCompoundTag("Data").setInteger("Value", -1);
        var events = new ObjectArrayList<String>();
        assertThrows(IllegalArgumentException.class, () -> assembly.restore(new Host(), saved, recordingRegistry(events)));
        assertTrue(events.isEmpty());
        assertStopped(captured[0], assembly);
    }

    @Test
    void encodeGraphOrAttributeMutationFaultsLoadedMachineAndRejectsLaterRunAndSave() {
        for (boolean changeGraph : List.of(false, true)) {
            boolean[] corrupt = {false};
            var component = stateType("encode_target", _ -> new State());
            var codec = new StateCodec() {
                @Override
                public NBTTagCompound encode(MachineInstance owner, State state) {
                    if (corrupt[0]) {
                        if (changeGraph) {
                            owner.components().remove(component);
                        } else {
                            owner.attributes().setBase(id("unexpected"), 3);
                        }
                    }
                    return super.encode(owner, state);
                }
            };
            var assembly = MachineAssembly.builder(declaration().component(component).build(), "v1").component(component, codec).build();
            var events = new ObjectArrayList<String>();
            var machine = assembly.create(new Host(), recordingRegistry(events));
            corrupt[0] = true;
            assertThrows(IllegalStateException.class, () -> assembly.encode(machine));
            assertStopped(machine, assembly);
            Objects.requireNonNull(machine.lifecycle()).unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            assertEquals(List.of("loaded", "unloaded:CHUNK_UNLOAD"), events);
        }
    }

    @Test
    void assemblyLoadFailurePreservesPrimaryFailureAndAggregatesEveryCapturedCleanup() {
        var primary = new AssertionError("load failed");
        var cleanup = new IllegalStateException("component cleanup");
        var globalCleanup = new AssertionError("global cleanup");
        var events = new ObjectArrayList<String>();
        var captured = new MachineInstance[1];
        var component = stateType("listener", owner -> {
            captured[0] = owner;
            var state = new State();
            state.loaded = _ -> {
                events.add("component:loaded");
                throw primary;
            };
            state.unloaded = event -> {
                events.add("component:" + event.reason());
                throw cleanup;
            };
            return state;
        });
        var registry = new MachineLifecycleRegistry();
        registry.register(new MachineLifecycleListener() {
            @Override
            public void onLoaded(MachineLifecycleEvent.Loaded event) {
                events.add("unexpected:loaded");
            }

            @Override
            public void onUnloaded(MachineLifecycleEvent.Unloaded event) {
                events.add("global:" + event.reason());
                throw globalCleanup;
            }
        });
        var assembly = MachineAssembly.builder(declaration().component(component).build(), "v1").component(component, new StateCodec()).build();
        assertSame(primary, assertThrows(AssertionError.class, () -> assembly.create(new Host(), registry)));
        assertArrayEquals(new Throwable[]{cleanup, globalCleanup}, primary.getSuppressed());
        assertEquals(List.of("component:loaded", "component:LOAD_FAILED", "global:LOAD_FAILED"), events);
        assertStopped(captured[0], assembly);
    }

    @Test
    void runningWorkSlotSurvivesUnloadRestoreAndFreshStructureWithoutReplayingStartOrDuplicatingOutput() {
        var group = id("group");
        var energy = RecipeRequirements.ENERGY.requirement(id("energy"), new EnergyRequirement(3, 2, 0, 5, false));
        var recipe = new MachineRecipe(id("recipe"), 3, List.of(energy), Set.of(group));
        var slotsCodec = new MachineWorkSlotsNbt("slots-v1", List.of(RecipeProcessNbt.builder(recipe, "recipe-v1").build()));
        var type = declaration().component(MachineWorkSlots.TYPE).workSlot(new WorkSlotDefinition("main", Set.of(group))).build();
        var assembly = MachineAssembly.builder(type, "v1").workSlots(slotsCodec).build();
        var input = new EnergyBuffer(100, () -> {
        });
        var output = new EnergyBuffer(100, () -> {
        });
        input.receiveEnergy(20, Actionable.MODULATE);
        var hatches = new MachineHatches(List.of(), List.of(input), List.of(output));
        var original = assembly.create(new Host(), new MachineLifecycleRegistry());
        var slots = Objects.requireNonNull(original.components().get(MachineWorkSlots.TYPE));
        Objects.requireNonNull(original.lifecycle()).form(hatches);
        var process = new RecipeProcess(original, recipe, 13);
        assertTrue(slots.start(process));
        original.onSchedule();
        var committed = process.state();
        assertEquals(1, committed.progress());
        assertEquals(15, input.storedEnergy());
        Objects.requireNonNull(original.lifecycle()).unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
        var saved = assembly.encode(original);
        int[] loads = {0};
        var registry = new MachineLifecycleRegistry();
        registry.register(new MachineLifecycleListener() {
            @Override
            public void onLoaded(MachineLifecycleEvent.Loaded event) {
                var restoredSlots = Objects.requireNonNull(event.context().machine().components().get(MachineWorkSlots.TYPE));
                assertEquals(committed, Objects.requireNonNull(restoredSlots.requireSlot("main").process()).state());
                assertFalse(event.context().formed());
                loads[0]++;
            }
        });
        var restored = assembly.restore(new Host(), saved, registry);
        var restoredSlots = Objects.requireNonNull(restored.components().get(MachineWorkSlots.TYPE));
        var resumed = Objects.requireNonNull(restoredSlots.requireSlot("main").process());
        assertNotSame(process, resumed);
        assertSame(restoredSlots, resumed.host());
        restored.onSchedule();
        restored.onSchedule();
        assertEquals(committed, resumed.state());
        assertEquals(15, input.storedEnergy());
        Objects.requireNonNull(restored.lifecycle()).form(hatches);
        restored.onSchedule();
        restored.onSchedule();
        restored.onSchedule();
        assertEquals(1, loads[0]);
        assertEquals(RecipeProcess.Lifecycle.COMPLETED, resumed.state().lifecycle());
        assertTrue(restoredSlots.activeProcesses().isEmpty());
        assertEquals(11, input.storedEnergy());
        assertEquals(5, output.storedEnergy());
        assertEquals(committed, process.state());
        var complete = assembly.restore(new Host(), assembly.encode(restored), new MachineLifecycleRegistry());
        Objects.requireNonNull(complete.lifecycle()).form(hatches);
        complete.onSchedule();
        assertEquals(5, output.storedEnergy());
    }

    private static class StateCodec implements MachineComponentCodec<State> {
        @Override
        public NBTTagCompound encode(MachineInstance owner, State component) {
            return valueTag(component.value);
        }

        @Override
        public State restore(MachineInstance owner, State fresh, NBTTagCompound saved) {
            fresh.value = saved.getInteger("Value");
            return fresh;
        }
    }

    private static final class State implements MachineLifecycleListener {
        private int value;
        private NBTTagCompound tag = new NBTTagCompound();
        private Consumer<MachineLifecycleEvent.Loaded> loaded = _ -> {
        };
        private Consumer<MachineLifecycleEvent.Unloaded> unloaded = _ -> {
        };

        @Override
        public void onLoaded(MachineLifecycleEvent.Loaded event) {
            loaded.accept(event);
        }

        @Override
        public void onUnloaded(MachineLifecycleEvent.Unloaded event) {
            unloaded.accept(event);
        }
    }

    private record ImmutableState(int value, String name, List<String> log) implements MachineLifecycleListener {
        @Override
        public void onLoaded(MachineLifecycleEvent.Loaded event) {
            log.add("load:" + name);
        }
    }

    private static final class Host implements MachineLifecycleHost {
        private final Thread ownerThread = Thread.currentThread();

        @Override
        public MachineIdentity identity() {
            return new MachineIdentity(id("machine"), 0, BlockPos.ORIGIN);
        }

        @Override
        public World world() {
            throw new AssertionError("Pure assembly tests must not access a Minecraft World");
        }

        @Override
        public void requireServerThread() {
            if (Thread.currentThread() != ownerThread) {
                throw new IllegalStateException("Foreign test thread");
            }
        }
    }
}
