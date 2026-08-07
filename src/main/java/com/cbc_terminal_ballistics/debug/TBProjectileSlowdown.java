package com.cbc_terminal_ballistics.debug;

import com.cbc_terminal_ballistics.network.ClientboundProjectileSlowdownPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.PacketDistributor;

public final class TBProjectileSlowdown {
    public static final int MIN_FACTOR = 1;
    public static final int MAX_FACTOR = 20;

    private static volatile int serverFactor = 1;
    private static volatile int clientFactor = 1;

    public static int serverFactor() {
        return serverFactor;
    }

    public static int clientFactor() {
        return clientFactor;
    }

    public static int factor(Entity entity) {
        return entity.level().isClientSide ? clientFactor : serverFactor;
    }

    public static int setServerFactor(int factor) {
        serverFactor = clamp(factor);
        return serverFactor;
    }

    public static int setClientFactor(int factor) {
        clientFactor = clamp(factor);
        return clientFactor;
    }

    public static boolean shouldSkip(Entity entity) {
        int factor = factor(entity);
        if (factor <= 1) return false;
        long time = entity.level().getGameTime();
        return Math.floorMod(time + entity.getId(), factor) != 0;
    }

    public static void syncTo(ServerPlayer player) {
        ClientboundProjectileSlowdownPacket packet = new ClientboundProjectileSlowdownPacket(serverFactor);
        PacketDistributor.sendToPlayer(player, packet);
    }

    public static void syncAll(MinecraftServer server) {
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            syncTo(player);
        }
    }

    private static int clamp(int factor) {
        return Mth.clamp(factor, MIN_FACTOR, MAX_FACTOR);
    }

    private TBProjectileSlowdown() {}
}
