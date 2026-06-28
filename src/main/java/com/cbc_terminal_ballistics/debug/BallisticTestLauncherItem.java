package com.cbc_terminal_ballistics.debug;

import com.cbc_terminal_ballistics.compat.TestLauncherProjectileCompat;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.List;

public class BallisticTestLauncherItem extends Item {
    public static final String NBT_MUZZLE_VELOCITY = "MuzzleVelocityMps";
    public static final int DEFAULT_MUZZLE_VELOCITY_MPS = 160;
    public static final int MIN_MUZZLE_VELOCITY_MPS = 20;
    public static final int MAX_MUZZLE_VELOCITY_MPS = 500;
    public static final int STEP_MUZZLE_VELOCITY_MPS = 10;

    public BallisticTestLauncherItem(Properties properties) {
        super(properties);
    }

    public static int velocity(ItemStack stack) {
        if (stack.hasTag() && stack.getTag().contains(NBT_MUZZLE_VELOCITY)) {
            return clampVelocity(stack.getTag().getInt(NBT_MUZZLE_VELOCITY));
        }
        return DEFAULT_MUZZLE_VELOCITY_MPS;
    }

    public static int clampVelocity(int value) {
        return Mth.clamp(value, MIN_MUZZLE_VELOCITY_MPS, MAX_MUZZLE_VELOCITY_MPS);
    }

    public static void setVelocity(ItemStack stack, int value) {
        stack.getOrCreateTag().putInt(NBT_MUZZLE_VELOCITY, clampVelocity(value));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, net.minecraft.world.entity.player.Player player, InteractionHand hand) {
        ItemStack launcher = player.getItemInHand(hand);
        if (hand != InteractionHand.MAIN_HAND) return InteractionResultHolder.pass(launcher);

        if (level.isClientSide) return InteractionResultHolder.success(launcher);
        if (!(level instanceof ServerLevel server) || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.fail(launcher);
        }

        ItemStack ammo = player.getOffhandItem();
        if (ammo.isEmpty()) {
            serverPlayer.displayClientMessage(Component.translatable("message.cbc_terminal_ballistics.ballistic_test_launcher.no_ammo").withStyle(ChatFormatting.RED), true);
            return InteractionResultHolder.fail(launcher);
        }

        ItemStack ammoCopy = deepCopyItemStack(ammo);
        Entity projectile = createProjectile(server, ammoCopy);
        if (projectile == null) {
            serverPlayer.displayClientMessage(Component.translatable("message.cbc_terminal_ballistics.ballistic_test_launcher.unsupported").withStyle(ChatFormatting.RED), true);
            return InteractionResultHolder.fail(launcher);
        }
        TestLauncherProjectileCompat.initializeLauncherProjectile(projectile);

        int velocityMps = velocity(launcher);
        Vec3 look = player.getLookAngle().normalize();
        Vec3 spawn = player.getEyePosition().add(look.scale(1.35));
        Vec3 motion = look.scale(velocityMps / 20.0D);

        projectile.setPos(spawn.x, spawn.y - projectile.getBbHeight() * 0.5D, spawn.z);
        projectile.setDeltaMovement(motion);
        if (projectile instanceof Projectile p) p.setOwner(player);
        invoke(projectile, "setOwner", new Class<?>[]{Entity.class}, player);
        invoke(projectile, "setOrientation", new Class<?>[]{Vec3.class}, look);
        invoke(projectile, "setChargePower", new Class<?>[]{float.class}, (float) Math.max(0.1D, velocityMps / 160.0D));
        invoke(projectile, "addAlwaysUntouchableEntity", new Class<?>[]{Entity.class}, player);

        server.addFreshEntity(projectile);
        player.getCooldowns().addCooldown(this, 5);
        serverPlayer.displayClientMessage(Component.translatable("message.cbc_terminal_ballistics.ballistic_test_launcher.fired", velocityMps).withStyle(ChatFormatting.GRAY), true);
        return InteractionResultHolder.success(launcher);
    }

    @Nullable
    private static Entity createProjectile(Level level, ItemStack ammo) {
        Object fromItem = invokeFirstEntity(ammo.getItem(), ammo, level);
        if (fromItem instanceof Entity entity) return entity;

        if (ammo.getItem() instanceof BlockItem blockItem) {
            Block block = blockItem.getBlock();
            Object fromBlock = invokeFirstEntity(block, level, ammo);
            if (fromBlock instanceof Entity entity) return entity;
        }
        return null;
    }

    private static Object invokeFirstEntity(Object target, ItemStack stack, Level level) {
        for (String name : new String[]{
                "getAutocannonProjectile",
                "getMediumcannonProjectile",
                "getMediumCannonProjectile",
                "getProjectile",
                "createProjectile"
        }) {
            Object result = invoke(target, name, new Class<?>[]{ItemStack.class, Level.class}, stack, level);
            if (result instanceof Entity) return result;
            result = invoke(target, name, new Class<?>[]{Level.class, ItemStack.class}, level, stack);
            if (result instanceof Entity) return result;
        }
        return null;
    }

    private static Object invokeFirstEntity(Object target, Level level, ItemStack stack) {
        for (String name : new String[]{"getProjectile", "createProjectile", "getCannonProjectile"}) {
            Object result = invoke(target, name, new Class<?>[]{Level.class, ItemStack.class}, level, stack);
            if (result instanceof Entity) return result;
            result = invoke(target, name, new Class<?>[]{ItemStack.class, Level.class}, stack, level);
            if (result instanceof Entity) return result;
        }
        return null;
    }

    private static Object invoke(Object target, String name, Class<?>[] paramTypes, Object... args) {
        if (target == null) return null;
        try {
            Method method = findMethod(target.getClass(), name, paramTypes);
            if (method == null) return null;
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findMethod(Class<?> owner, String name, Class<?>... params) {
        for (Class<?> cur = owner; cur != null; cur = cur.getSuperclass()) {
            try {
                return cur.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignored) {
            }
        }
        for (Class<?> iface : owner.getInterfaces()) {
            try {
                return iface.getMethod(name, params);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private static ItemStack deepCopyItemStack(ItemStack original) {
        if (original.isEmpty()) return ItemStack.EMPTY;
        CompoundTag tag = new CompoundTag();
        original.save(tag);
        return ItemStack.of(tag);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.cbc_terminal_ballistics.ballistic_test_launcher.velocity", velocity(stack)).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.cbc_terminal_ballistics.ballistic_test_launcher.controls").withStyle(ChatFormatting.DARK_GRAY));
    }
}
