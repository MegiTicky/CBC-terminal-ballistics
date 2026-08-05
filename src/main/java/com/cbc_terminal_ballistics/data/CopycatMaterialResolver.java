package com.cbc_terminal_ballistics.data;

import com.cbc_terminal_ballistics.armor.FramedCollapsibleCopycatArmorBlockEntity;
import com.cbc_terminal_ballistics.util.CBCReflect;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the copied/rendered material of copycat-style blocks for terminal-ballistics material stats.
 *
 * <p>This intentionally only returns a material BlockState for {@link MaterialManager}; it does not replace
 * the impacted copycat state for CBC armor toughness/hardness calculations.</p>
 */
public final class CopycatMaterialResolver {
    private static final ConcurrentHashMap<MethodKey, Optional<Method>> METHOD_CACHE = new ConcurrentHashMap<>();

    public static Optional<BlockState> resolve(Level level, BlockPos pos, BlockState state) {
        return resolve(level, pos, state, null);
    }

    public static Optional<BlockState> resolve(Level level, BlockPos pos, BlockState state, BlockHitResult hit) {
        if (level == null || pos == null) return Optional.empty();
        try {
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null) return Optional.empty();

            Optional<BlockState> own = resolveOwnCopycat(be);
            if (own.isPresent()) return own;

            Optional<BlockState> multiState = resolveMultiStateCopycat(level, pos, be);
            if (multiState.isPresent()) return multiState;

            Optional<BlockState> framed = resolveFramedBlock(level, pos, be, hit);
            if (framed.isPresent()) return framed;

            return resolveSingleStateCopycat(be);
        } catch (Throwable ignored) {
            // Copycat support is optional and impact-time hot; fail soft without warning spam.
            return Optional.empty();
        }
    }

    private static Optional<BlockState> resolveOwnCopycat(BlockEntity be) {
        if (be instanceof FramedCollapsibleCopycatArmorBlockEntity framed && framed.hasCopiedMaterial()) {
            return validMaterial(framed.getCopiedMaterial());
        }
        return Optional.empty();
    }

    private static Optional<BlockState> resolveSingleStateCopycat(BlockEntity be) {
        Object hasCustom = invokeNoArg(be, "hasCustomMaterial");
        if (!(hasCustom instanceof Boolean custom) || !custom) return Optional.empty();
        Object material = invokeNoArg(be, "getMaterial");
        return material instanceof BlockState materialState ? validMaterial(materialState) : Optional.empty();
    }

    private static Optional<BlockState> resolveMultiStateCopycat(Level level, BlockPos pos, BlockEntity be) {
        Object storage = invokeNoArg(be, "getMaterialItemStorage");
        if (storage == null) return Optional.empty();

        ArrayList<BlockState> candidates = new ArrayList<>();
        Object materialMap = invokeNoArg(storage, "getMaterialMap");
        if (materialMap instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object key = entry.getKey();
                Object value = entry.getValue();
                if (key instanceof String property) {
                    Object hasCustom = invoke(storage, "hasCustomMaterial", new Class<?>[]{String.class}, property);
                    if (hasCustom instanceof Boolean custom && !custom) continue;
                }
                if (value instanceof BlockState materialState) {
                    validMaterial(materialState).ifPresent(candidates::add);
                }
            }
            return chooseStrongest(level, pos, candidates);
        }

        Object allMaterials = invokeNoArg(storage, "getAllMaterials");
        if (allMaterials instanceof Iterable<?> iterable) {
            for (Object value : iterable) {
                if (value instanceof BlockState materialState) {
                    validMaterial(materialState).ifPresent(candidates::add);
                }
            }
        }
        return chooseStrongest(level, pos, candidates);
    }

    private static Optional<BlockState> resolveFramedBlock(Level level, BlockPos pos, BlockEntity be, BlockHitResult hit) {
        ArrayList<BlockState> candidates = new ArrayList<>();

        if (hit != null) {
            Object hitCamo = invoke(be, "getCamo", new Class<?>[]{BlockHitResult.class}, hit);
            collectMaterialStates(hitCamo, candidates, newIdentitySet());
            Optional<BlockState> hitResolved = chooseStrongest(level, pos, candidates);
            if (hitResolved.isPresent()) return hitResolved;
            candidates.clear();
        }

        // FramedBlocks 9.x exposes getCamo(), getCamo(BlockState), getCamo(Direction),
        // and some multi-camo subclasses/addons expose getCamoTwo()/pairs.  Keep this
        // reflection-only so the mod stays loadable without FramedBlocks.
        collectMaterialStates(invokeNoArg(be, "getCamo"), candidates, newIdentitySet());
        collectMaterialStates(invokeNoArg(be, "getCamoTwo"), candidates, newIdentitySet());
        collectMaterialStates(invokeNoArg(be, "getCamoPair"), candidates, newIdentitySet());
        return chooseStrongest(level, pos, candidates);
    }

    private static Set<Object> newIdentitySet() {
        return java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static void collectMaterialStates(Object value, Collection<BlockState> out, Set<Object> seen) {
        if (value == null || !seen.add(value)) return;
        if (value instanceof BlockState state) {
            validMaterial(state).ifPresent(out::add);
            return;
        }
        if (value instanceof Optional<?> optional) {
            optional.ifPresent(v -> collectMaterialStates(v, out, seen));
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Object v : map.values()) collectMaterialStates(v, out, seen);
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object v : iterable) collectMaterialStates(v, out, seen);
            return;
        }
        Class<?> type = value.getClass();
        if (type.isArray()) {
            int len = Array.getLength(value);
            for (int i = 0; i < len; i++) collectMaterialStates(Array.get(value, i), out, seen);
            return;
        }

        // FramedBlocks CamoContainer#getState() and CamoPair#getCamoOne/#getCamoTwo.
        collectMaterialStates(invokeNoArg(value, "getState"), out, seen);
        collectMaterialStates(invokeNoArg(value, "getCamoOne"), out, seen);
        collectMaterialStates(invokeNoArg(value, "getCamoTwo"), out, seen);
    }

    private static Optional<BlockState> chooseStrongest(Level level, BlockPos pos, Collection<BlockState> candidates) {
        BlockState best = null;
        double bestToughness = Double.NEGATIVE_INFINITY;
        double bestDuctility = Double.NEGATIVE_INFINITY;
        for (BlockState candidate : candidates) {
            double toughness = copiedMaterialToughness(level, pos, candidate);
            double ductility = MaterialManager.INSTANCE.get(candidate).ductility();
            if (best == null
                    || toughness > bestToughness + 1.0e-6
                    || (Math.abs(toughness - bestToughness) <= 1.0e-6 && ductility > bestDuctility)) {
                best = candidate;
                bestToughness = toughness;
                bestDuctility = ductility;
            }
        }
        return Optional.ofNullable(best);
    }

    private static double copiedMaterialToughness(Level level, BlockPos pos, BlockState material) {
        double fallbackToughness = Math.max(0.0, material.getBlock().getExplosionResistance());
        try {
            return CBCReflect.armorToughness(level, material, pos, fallbackToughness);
        } catch (Throwable ignored) {
            return fallbackToughness;
        }
    }

    private static Optional<BlockState> validMaterial(BlockState material) {
        if (material == null || material.isAir() || isCreateCopycatBase(material)) return Optional.empty();
        return Optional.of(material);
    }

    private static boolean isCreateCopycatBase(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id != null && "create".equals(id.getNamespace()) && "copycat_base".equals(id.getPath());
    }

    private static Object invokeNoArg(Object target, String methodName) {
        return invoke(target, methodName, new Class<?>[0]);
    }

    private static Object invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... args) {
        if (target == null) return null;
        try {
            Method method = method(target.getClass(), methodName, parameterTypes);
            return method == null ? null : method.invoke(target, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method method(Class<?> owner, String name, Class<?>[] parameterTypes) {
        MethodKey key = new MethodKey(owner, name, parameterTypes);
        Optional<Method> cached = METHOD_CACHE.get(key);
        if (cached != null) return cached.orElse(null);
        Optional<Method> resolved = Optional.ofNullable(findMethod(owner, name, parameterTypes));
        METHOD_CACHE.put(key, resolved);
        return resolved.orElse(null);
    }

    private static Method findMethod(Class<?> owner, String name, Class<?>[] parameterTypes) {
        try {
            Method method = owner.getMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (Throwable ignored) {
        }

        Class<?> current = owner;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (Throwable ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private record MethodKey(Class<?> owner, String name, java.util.List<Class<?>> parameterTypes) {
        MethodKey(Class<?> owner, String name, Class<?>[] parameterTypes) {
            this(owner, name, java.util.List.of(parameterTypes));
        }
    }

    private CopycatMaterialResolver() {}
}
