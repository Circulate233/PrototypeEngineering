package com.circulation.prototype_eng.integration.crafttweaker.network;

import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.identity.MachineOwnership;
import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.network.MachineNetworkDevice;
import com.circulation.prototype_eng.api.network.MachineComputerComponents;
import com.circulation.prototype_eng.api.network.MachineDataCenterStorage;
import com.circulation.prototype_eng.api.network.NetworkDeviceRole;
import com.circulation.prototype_eng.api.network.NetworkDeviceSnapshot;
import com.circulation.prototype_eng.api.network.NetworkRuntime;
import com.circulation.prototype_eng.api.network.recipe.NetworkRecipeWork;
import com.circulation.prototype_eng.api.network.resource.NetworkComputerComponentRegistry;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.integration.crafttweaker.resource.ZenComputingResources;
import com.circulation.prototype_eng.integration.crafttweaker.resource.ZenComputingModifier;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import java.util.Objects;
import java.util.Locale;
import java.util.function.Consumer;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

/** Declaration attachments only; real ownership, contents, permits and persistence remain in Java components. */
@ZenRegister
@ZenClass("mods.prototype_eng.network.NetworkConfiguration")
@SuppressWarnings("unused") // Script parameter conversion delegates to public Java contracts.
public final class ZenNetworkConfiguration {
 private final Consumer<MachineType.Builder> machine;
 private final Consumer<MachineAssembly.Builder> assembly;
 public ZenNetworkConfiguration(Consumer<MachineType.Builder> machine,Consumer<MachineAssembly.Builder> assembly) {
  this.machine=Objects.requireNonNull(machine); this.assembly=Objects.requireNonNull(assembly);
 }
 @ZenMethod public static ZenNetworkConfiguration create(String id,String roleName,int level,ZenComputingResources supply,ZenComputingModifier modifier,int dataCenterCapacity,ZenNetworkRequirements defaults) {
  var name=new ResourceLocation(id); var role=NetworkDeviceRole.valueOf(roleName.toUpperCase(Locale.ROOT));
  var resources=Objects.requireNonNull(supply).value(); var adjustment=Objects.requireNonNull(modifier).value(); var requirements=Objects.requireNonNull(defaults).value();
  if(level<0 || ((role==NetworkDeviceRole.NETWORK_CENTER || role==NetworkDeviceRole.RESEARCH_STATION) && level==0)
   || (role==NetworkDeviceRole.DATA_CENTER ? dataCenterCapacity<1 : dataCenterCapacity!=0)) throw new IllegalArgumentException("Invalid network declaration level or database capacity");
  var database=role==NetworkDeviceRole.DATA_CENTER ? MachineDataCenterStorage.component(child(name,"database"),level,dataCenterCapacity,ResearchRegistry.INSTANCE) : null;
  var computer=role==NetworkDeviceRole.COMPUTER ? MachineComputerComponents.component(child(name,"components"),NetworkComputerComponentRegistry.INSTANCE) : null;
  var base=MachineNetworkDevice.capturedComponent(child(name,"device"),(owner,identity) -> {
   boolean formed=Objects.requireNonNull(owner.lifecycle()).formed();
   if(database!=null) return Objects.requireNonNull(owner.components().get(database)).networkCapture(identity,formed,resources,adjustment);
   if(computer!=null) return Objects.requireNonNull(owner.components().get(computer)).networkCapture(identity,level,formed,resources,adjustment);
   return new NetworkRuntime.Capture(new NetworkDeviceSnapshot(identity.identity(),role,level,formed,identity.loadOrder(),resources,adjustment,0,0),()->true);
  });
  var dependencies=new ObjectArrayList<>(base.dependencies());
  if(database!=null) dependencies.add(database); if(computer!=null) dependencies.add(computer);
  var device=new ComponentType<>(base.id(),base::create,base.systems(),dependencies);
  var work=NetworkRecipeWork.component(child(name,"work"),device,requirements);
  return new ZenNetworkConfiguration(builder -> {
   builder.component(MachineOwnership.TYPE); if(database!=null) builder.component(database); if(computer!=null) builder.component(computer);
   builder.component(device).component(work);
  },builder -> {
   builder.component(MachineOwnership.TYPE,MachineOwnership.CODEC); if(database!=null) builder.component(database,MachineDataCenterStorage.CODEC);
   if(computer!=null) builder.recreated(computer); builder.recreated(device,work);
  });
 }
 private static ResourceLocation child(ResourceLocation id,String suffix) { return new ResourceLocation(id.getNamespace(),id.getPath()+"/"+suffix); }
 public void configure(MachineType.Builder builder) { machine.accept(builder); }
 public void configure(MachineAssembly.Builder builder) { assembly.accept(builder); }
}
