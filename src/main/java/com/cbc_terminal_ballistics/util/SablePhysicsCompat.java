package com.cbc_terminal_ballistics.util;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Updates Sable's native voxel collider for block-entity-dependent shapes.
 *
 * Sable 1.2.2 and 2.0.3 bake collider data per BlockState. That cache cannot
 * see the per-position offsets stored by the collapsible armor block entity,
 * so this bridge installs the actual VoxelShape for that one plot position.
 */
public final class SablePhysicsCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("cbc_terminal_ballistics:SablePhysicsCompat");
    private static final String PHYSICS_SYSTEM = "dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem";
    private static final String RAPIER_PIPELINE = "dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline";
    private static final String RAPIER_3D = "dev.ryanhcode.sable.physics.impl.rapier.Rapier3D";
    private static final String PHYSICS_PROPERTIES = "dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper";
    private static final String COLLISION_CALLBACK = "dev.ryanhcode.sable.api.block.BlockWithSubLevelCollisionCallback";
    private static final String NEIGHBORHOOD_STATE = "dev.ryanhcode.sable.physics.chunk.VoxelNeighborhoodState";

    private static final Map<Object, Map<ColliderKey, Integer>> COLLIDER_HANDLES = new WeakHashMap<>();

    private static Method physicsSystemGetMethod;
    private static Method getPipelineMethod;
    private static Method wakeUpObjectsAtMethod;
    private static Method getFrictionMethod;
    private static Method getVolumeMethod;
    private static Method getRestitutionMethod;
    private static Method getCollisionCallbackMethod;
    private static Method createColliderMethod;
    private static Method colliderAddBoxMethod;
    private static Method colliderHandleMethod;
    private static Method changeBlockMethod;
    private static Method neighborhoodByteMethod;
    private static Field physicsStepField;
    private static Object cornerNeighborhoodState;
    private static boolean reflectionInitialized;
    private static boolean reflectionFailed;
    private static boolean failureLogged;

    public enum RefreshResult {
        UPDATED,
        NOT_APPLICABLE,
        RETRY
    }

    public static RefreshResult refreshBlockCollider(ServerLevel level, BlockPos pos, BlockState state,
                                                     VoxelShape shape, int packedOffsets) {
        if (!SableCompat.isPresent()) return RefreshResult.NOT_APPLICABLE;
        if (!SableCompat.hasLoadedSubLevel(level, pos)) {
            return SableCompat.isProbablyInSubLevel(pos) ? RefreshResult.RETRY : RefreshResult.NOT_APPLICABLE;
        }
        if (shape == null || shape.isEmpty()) return RefreshResult.NOT_APPLICABLE;

        try {
            if (!initReflection()) return RefreshResult.NOT_APPLICABLE;
            if (physicsStepActive()) return RefreshResult.RETRY;
            Object system = physicsSystemGetMethod.invoke(null, level);
            if (system == null) return RefreshResult.RETRY;
            Object pipeline = getPipelineMethod.invoke(system);
            if (pipeline == null) return RefreshResult.RETRY;
            if (!RAPIER_PIPELINE.equals(pipeline.getClass().getName())) return RefreshResult.NOT_APPLICABLE;

            int colliderHandle = colliderHandle(pipeline, state, shape, packedOffsets);
            int neighborhood = ((Number) neighborhoodByteMethod.invoke(cornerNeighborhoodState)).intValue() & 0xFF;
            int packedCollider = neighborhood | ((colliderHandle + 1) << 16);
            Number sceneHandle = sceneHandle(pipeline);
            invokeChangeBlock(sceneHandle, pos, packedCollider);
            wakeUpObjectsAtMethod.invoke(system, pos.getX(), pos.getY(), pos.getZ());
            return RefreshResult.UPDATED;
        } catch (Throwable error) {
            logFailure(error);
            return RefreshResult.RETRY;
        }
    }

    private static boolean initReflection() {
        if (reflectionInitialized) return !reflectionFailed;
        reflectionInitialized = true;
        try {
            Class<?> systemClass = Class.forName(PHYSICS_SYSTEM);
            physicsSystemGetMethod = systemClass.getMethod("get", net.minecraft.world.level.Level.class);
            getPipelineMethod = systemClass.getMethod("getPipeline");
            wakeUpObjectsAtMethod = systemClass.getMethod("wakeUpObjectsAt", int.class, int.class, int.class);
            try {
                physicsStepField = systemClass.getField("IN_PHYSICS_STEP");
            } catch (NoSuchFieldException ignored) {
                physicsStepField = systemClass.getField("currentlySteppingSystem");
            }

            Class<?> propertiesClass = Class.forName(PHYSICS_PROPERTIES);
            getFrictionMethod = propertiesClass.getMethod("getFriction", BlockState.class);
            getVolumeMethod = propertiesClass.getMethod("getVolume", BlockState.class);
            getRestitutionMethod = propertiesClass.getMethod("getRestitution", BlockState.class);

            Class<?> callbackClass = Class.forName(COLLISION_CALLBACK);
            getCollisionCallbackMethod = callbackClass.getMethod("sable$getCallback", BlockState.class);

            Class<?> rapierClass = Class.forName(RAPIER_3D);
            createColliderMethod = findMethod(rapierClass, "createVoxelColliderEntry", 5, true);
            changeBlockMethod = findMethod(rapierClass, "changeBlock", 5, true);

            Class<?> colliderDataClass = createColliderMethod.getReturnType();
            colliderAddBoxMethod = findMethod(colliderDataClass, "addBox", 2, false);
            colliderHandleMethod = colliderDataClass.getMethod("handle");

            Class<?> neighborhoodClass = Class.forName(NEIGHBORHOOD_STATE);
            cornerNeighborhoodState = neighborhoodClass.getField("CORNER").get(null);
            neighborhoodByteMethod = neighborhoodClass.getMethod("byteRepresentation");
            return true;
        } catch (Throwable error) {
            reflectionFailed = true;
            logFailure(error);
            return false;
        }
    }

    private static Method findMethod(Class<?> owner, String name, int parameterCount, boolean requireStatic)
            throws NoSuchMethodException {
        for (Method method : owner.getDeclaredMethods()) {
            if (method.getName().equals(name)
                    && method.getParameterCount() == parameterCount
                    && (!requireStatic || Modifier.isStatic(method.getModifiers()))) {
                method.setAccessible(true);
                return method;
            }
        }
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name)
                    && method.getParameterCount() == parameterCount
                    && (!requireStatic || Modifier.isStatic(method.getModifiers()))) {
                return method;
            }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name);
    }

    private static int colliderHandle(Object pipeline, BlockState state, VoxelShape shape, int packedOffsets)
            throws ReflectiveOperationException {
        synchronized (COLLIDER_HANDLES) {
            Map<ColliderKey, Integer> handles = COLLIDER_HANDLES.computeIfAbsent(pipeline, ignored -> new HashMap<>());
            ColliderKey key = new ColliderKey(state, packedOffsets);
            Integer existing = handles.get(key);
            if (existing != null) return existing;

            double friction = ((Number) getFrictionMethod.invoke(null, state)).doubleValue();
            double volume = ((Number) getVolumeMethod.invoke(null, state)).doubleValue();
            double restitution = ((Number) getRestitutionMethod.invoke(null, state)).doubleValue();
            Object callback = getCollisionCallbackMethod.invoke(null, state);
            Object colliderData = createColliderMethod.invoke(null, friction, volume, restitution, false, callback);

            for (double[] box : boxes(shape)) {
                colliderAddBoxMethod.invoke(colliderData,
                        new Vector3d(box[0], box[1], box[2]),
                        new Vector3d(box[3], box[4], box[5]));
            }
            int handle = ((Number) colliderHandleMethod.invoke(colliderData)).intValue();
            handles.put(key, handle);
            return handle;
        }
    }

    private static java.util.List<double[]> boxes(VoxelShape shape) {
        java.util.List<double[]> boxes = new java.util.ArrayList<>();
        shape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) ->
                boxes.add(new double[]{
                        Math.max(0.0D, minX), Math.max(0.0D, minY), Math.max(0.0D, minZ),
                        Math.min(1.0D, maxX), Math.min(1.0D, maxY), Math.min(1.0D, maxZ)
                }));
        return boxes;
    }

    private static Number sceneHandle(Object pipeline) throws ReflectiveOperationException {
        try {
            Method method = pipeline.getClass().getDeclaredMethod("getSceneHandle");
            method.setAccessible(true);
            return (Number) method.invoke(pipeline);
        } catch (NoSuchMethodException ignored) {
            Field field = pipeline.getClass().getDeclaredField("sceneId");
            field.setAccessible(true);
            return (Number) field.get(pipeline);
        }
    }

    private static boolean physicsStepActive() throws IllegalAccessException {
        Object value = physicsStepField.get(null);
        return value instanceof Boolean stepping ? stepping : value != null;
    }

    private static void invokeChangeBlock(Number sceneHandle, BlockPos pos, int packedCollider)
            throws ReflectiveOperationException {
        Class<?> firstParameter = changeBlockMethod.getParameterTypes()[0];
        Object sceneArgument = firstParameter == long.class ? sceneHandle.longValue() : sceneHandle.intValue();
        changeBlockMethod.invoke(null, sceneArgument, pos.getX(), pos.getY(), pos.getZ(), packedCollider);
    }

    private static void logFailure(Throwable error) {
        if (failureLogged) return;
        failureLogged = true;
        Throwable cause = error instanceof java.lang.reflect.InvocationTargetException invocation
                && invocation.getCause() != null ? invocation.getCause() : error;
        LOGGER.warn("Unable to refresh Sable native collider; using Sable's cached full-block collider: {}",
                cause.toString());
    }

    private record ColliderKey(BlockState state, int packedOffsets) {
    }

    private SablePhysicsCompat() {
    }
}
