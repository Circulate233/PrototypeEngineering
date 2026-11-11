package com.circulation.prototype_eng.item;

import ae2.items.AEBaseItem;
import com.circulation.prototype_eng.OrientationToolConfig;
import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.machine.MachineControllerHost;
import com.circulation.prototype_eng.localization.OrientationToolText;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.EnumEnchantmentType;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.init.SoundEvents;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * PM's switchable controller wrench, weapon and iron pickaxe/axe/shovel hybrid.
 */
public class ControllerOrientationTool extends AEBaseItem {
    private static final String NBT_ENABLED = "enabled";
    private static final Set<String> TOOL_CLASSES = Set.of("pickaxe", "axe", "shovel");

    public ControllerOrientationTool() {
        setRegistryName(new ResourceLocation(Tags.MOD_ID, "orientation_tool"));
        setMaxStackSize(1);
        setCreativeTab(CreativeTabs.TOOLS);
        setMaxDamage(OrientationToolConfig.settings().maxDurability());
    }

    public static boolean isEnabled(ItemStack stack) {
        var tag = stack.getTagCompound();
        return tag != null && tag.getBoolean(NBT_ENABLED);
    }

    public static boolean rotating(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof ControllerOrientationTool && isEnabled(stack);
    }

    public static void setEnabled(ItemStack stack, boolean enabled) {
        var tag = stack.getTagCompound();
        if (tag == null) {
            tag = new NBTTagCompound();
            stack.setTagCompound(tag);
        }
        tag.setBoolean(NBT_ENABLED, enabled);
    }

    @Override
    public @NotNull String getTranslationKey() {
        return OrientationToolText.Name.getTranslationKey();
    }

    @Override
    public @NotNull String getTranslationKey(@NotNull ItemStack stack) {
        return getTranslationKey();
    }

    @Override
    public @NotNull String getItemStackDisplayName(@NotNull ItemStack stack) {
        return OrientationToolText.Name.getLocal();
    }

    @Override
    public @NotNull ActionResult<ItemStack> onItemRightClick(@NotNull World world, @NotNull EntityPlayer player, @NotNull EnumHand hand) {
        var stack = player.getHeldItem(hand);
        if (!player.isSneaking()) {
            return new ActionResult<>(EnumActionResult.PASS, stack);
        }
        if (!world.isRemote) {
            boolean enabled = !isEnabled(stack);
            setEnabled(stack, enabled);
            player.sendStatusMessage((enabled ? OrientationToolText.Enabled : OrientationToolText.Disabled).text(), true);
            world.playSound(null, player.posX, player.posY, player.posZ, SoundEvents.UI_BUTTON_CLICK,
                SoundCategory.PLAYERS, 0.35F, enabled ? 1.25F : 0.9F);
        }
        return new ActionResult<>(EnumActionResult.SUCCESS, stack);
    }

    @Override
    public @NotNull EnumActionResult onItemUseFirst(@NotNull EntityPlayer player, @NotNull World world, @NotNull BlockPos pos,
                                                    @NotNull EnumFacing side, float hitX, float hitY, float hitZ, @NotNull EnumHand hand) {
        return onItemUse(player, world, pos, hand, side, hitX, hitY, hitZ);
    }

    @Override
    public @NotNull EnumActionResult onItemUse(@NotNull EntityPlayer player, @NotNull World world, @NotNull BlockPos pos,
                                               @NotNull EnumHand hand, @NotNull EnumFacing side, float hitX, float hitY, float hitZ) {
        var stack = player.getHeldItem(hand);
        if (!isEnabled(stack) || !world.isBlockLoaded(pos, false) || !(world.getTileEntity(pos) instanceof MachineControllerHost tile)) {
            return EnumActionResult.PASS;
        }
        if (!world.isBlockModifiable(player, pos) || !player.canPlayerEdit(pos, side, stack)) {
            return EnumActionResult.FAIL;
        }
        var part = OrientationToolOrientations.part(side, hitX, hitY, hitZ);
        if (!world.isRemote && part != OrientationToolOrientations.Part.CORNER) {
            // Read current orientation only when this relative request reaches its owner FIFO boundary.
            tile.transformOrientation(current -> OrientationToolOrientations.apply(current, side, part));
            stack.damageItem(OrientationToolConfig.settings().durabilityCostRotateEnabled(), player);
            player.inventory.markDirty();
        }
        return EnumActionResult.SUCCESS;
    }

    @Override
    public boolean hitEntity(@NotNull ItemStack stack, @NotNull EntityLivingBase target, @NotNull EntityLivingBase attacker) {
        boolean enabled = isEnabled(stack);
        if (!enabled && !attacker.world.isRemote && attacker instanceof EntityPlayerMP player) {
            OrientationToolAdvancements.grantCherishTool(player);
        }
        var settings = OrientationToolConfig.settings();
        stack.damageItem(enabled ? settings.durabilityCostAttackEnabled() : settings.durabilityCostAttackDisabled(), attacker);
        return true;
    }

    @Override
    public boolean onBlockDestroyed(@NotNull ItemStack stack, @NotNull World world, @NotNull IBlockState state,
                                    @NotNull BlockPos pos, @NotNull EntityLivingBase entity) {
        if (state.getBlockHardness(world, pos) != 0.0F) {
            var settings = OrientationToolConfig.settings();
            stack.damageItem(isEnabled(stack) ? settings.durabilityCostBreakEnabled() : settings.durabilityCostBreakDisabled(), entity);
        }
        return true;
    }

    @Override
    public float getDestroySpeed(@NotNull ItemStack stack, @NotNull IBlockState state) {
        if (isEnabled(stack)) {
            return 1.0F;
        }
        return Math.max(Items.IRON_PICKAXE.getDestroySpeed(stack, state),
            Math.max(Items.IRON_AXE.getDestroySpeed(stack, state), Items.IRON_SHOVEL.getDestroySpeed(stack, state)));
    }

    @Override
    public int getHarvestLevel(@NotNull ItemStack stack, @NotNull String toolClass,
                               @Nullable EntityPlayer player, @Nullable IBlockState state) {
        return !isEnabled(stack) && TOOL_CLASSES.contains(toolClass) ? 2 : super.getHarvestLevel(stack, toolClass, player, state);
    }

    @Override
    public @NotNull Set<String> getToolClasses(@NotNull ItemStack stack) {
        return isEnabled(stack) ? Set.of() : new ObjectOpenHashSet<>(TOOL_CLASSES);
    }

    @Override
    public boolean canHarvestBlock(@NotNull IBlockState state, @NotNull ItemStack stack) {
        return !isEnabled(stack) && (Items.IRON_PICKAXE.canHarvestBlock(state)
            || Items.IRON_AXE.canHarvestBlock(state) || Items.IRON_SHOVEL.canHarvestBlock(state));
    }

    @Override
    public int getItemEnchantability() {
        return OrientationToolConfig.settings().enchantability();
    }

    @Override
    public boolean canApplyAtEnchantingTable(@NotNull ItemStack stack, @NotNull Enchantment enchantment) {
        var type = enchantment.type;
        return type == EnumEnchantmentType.DIGGER || type == EnumEnchantmentType.WEAPON
            || type == EnumEnchantmentType.BREAKABLE || super.canApplyAtEnchantingTable(stack, enchantment);
    }

    @Override
    public boolean isBookEnchantable(@NotNull ItemStack stack, @NotNull ItemStack book) {
        var enchantments = EnchantmentHelper.getEnchantments(book);
        return !enchantments.isEmpty() && enchantments.keySet().stream().allMatch(enchantment -> canApplyAtEnchantingTable(stack, enchantment));
    }

    @Override
    @SideOnly(Side.CLIENT)
    public boolean hasEffect(@NotNull ItemStack stack) {
        return isEnabled(stack) || super.hasEffect(stack);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addCheckedInformation(@NotNull ItemStack stack, @Nullable World world, @NotNull List<String> tooltip, @NotNull ITooltipFlag flag) {
        tooltip.add(OrientationToolText.State.getLocal((isEnabled(stack) ? OrientationToolText.StateOn : OrientationToolText.StateOff).getLocal()));
        tooltip.add(OrientationToolText.ToggleHint.getLocal());
        tooltip.add(OrientationToolText.GridCenterHint.getLocal());
        tooltip.add(OrientationToolText.GridEdgeHint.getLocal());
        super.addCheckedInformation(stack, world, tooltip, flag);
    }

    @Override
    public @NotNull Multimap<String, AttributeModifier> getAttributeModifiers(@NotNull EntityEquipmentSlot slot, @NotNull ItemStack stack) {
        Multimap<String, AttributeModifier> modifiers = HashMultimap.create();
        if (slot == EntityEquipmentSlot.MAINHAND) {
            var settings = OrientationToolConfig.settings();
            modifiers.put(SharedMonsterAttributes.ATTACK_DAMAGE.getName(), new AttributeModifier(ATTACK_DAMAGE_MODIFIER,
                "Tool modifier", isEnabled(stack) ? settings.attackDamageEnabled() : settings.attackDamageDisabled(), 0));
            modifiers.put(SharedMonsterAttributes.ATTACK_SPEED.getName(), new AttributeModifier(ATTACK_SPEED_MODIFIER,
                "Tool modifier", settings.attackSpeed(), 0));
        }
        return modifiers;
    }
}
