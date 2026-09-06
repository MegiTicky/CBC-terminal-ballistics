package com.cbc_terminal_ballistics.mixin;

import com.cbc_terminal_ballistics.client.hitcam.ArtilleryChunkCache;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Consumer;

/**
 * Terrain retention for the artillery/terminal hitcam view beyond the client's
 * view distance. Vanilla {@code replaceWithPacketData} throws away every chunk
 * packet that lands outside the player's view square ("not in the view range")
 * and {@code getChunk} serves null everywhere else, so a far-flying shell's
 * neighbourhood can never be rendered by the hitcam PiP's nested world pass.
 * While the hitcam wants streamed terrain, chunks on that discard path are
 * parked in {@link ArtilleryChunkCache} instead, and the {@code getChunk}
 * fallback serves them back so section meshes can compile around the PiP
 * camera.
 *
 * <p>Production Forge 1.20.1 runs vanilla members under SRG names while dev
 * runtimes use official ones, and this workspace's Mixin AP has no vanilla
 * mapping table, so both spellings are injected with {@code remap = false,
 * require = 0} — exactly one of them resolves per environment, and a miss is
 * a silent no-op (worst case: the PiP reverts to a terrain-less view past the
 * render distance boundary).</p>
 */
@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheMixin {

    private static final String REPLACE_DESCRIPTOR =
        "(IILnet/minecraft/network/FriendlyByteBuf;Lnet/minecraft/nbt/CompoundTag;"
            + "Ljava/util/function/Consumer;)Lnet/minecraft/world/level/chunk/LevelChunk;";
    private static final String GET_CHUNK_DESCRIPTOR =
        "(IILnet/minecraft/world/level/chunk/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/LevelChunk;";

    @Inject(method = "m_194116_" + REPLACE_DESCRIPTOR, remap = false, require = 0,
        at = @At("HEAD"), cancellable = true)
    private void ctb$retainFarChunkSrg(int x, int z, FriendlyByteBuf buf, CompoundTag tag,
                                       Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> consumer,
                                       CallbackInfoReturnable<LevelChunk> cir) {
        ctb$retainFarChunk(x, z, buf, tag, consumer, cir);
    }

    @Inject(method = "replaceWithPacketData" + REPLACE_DESCRIPTOR, remap = false, require = 0,
        at = @At("HEAD"), cancellable = true)
    private void ctb$retainFarChunkNamed(int x, int z, FriendlyByteBuf buf, CompoundTag tag,
                                         Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> consumer,
                                         CallbackInfoReturnable<LevelChunk> cir) {
        ctb$retainFarChunk(x, z, buf, tag, consumer, cir);
    }

    private void ctb$retainFarChunk(int x, int z, FriendlyByteBuf buf, CompoundTag tag,
                                    Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> consumer,
                                    CallbackInfoReturnable<LevelChunk> cir) {
        if (!ArtilleryChunkCache.probeCaptureHook()) return;
        // Anything vanilla would keep itself (inside the view square) takes the
        // normal path; we only rescue the discard branch.
        ClientChunkCache self = (ClientChunkCache) (Object) this;
        if (self.getChunk(x, z, ChunkStatus.FULL, false) != null) return;

        ClientLevel level = (ClientLevel) self.getLevel();
        ChunkPos pos = new ChunkPos(x, z);
        LevelChunk chunk = new LevelChunk(level, pos);
        chunk.replaceWithPacketData(buf, tag, consumer);
        ArtilleryChunkCache.put(chunk);
        // Same side effects as the in-range path: tint caches invalidated,
        // block entities registered, load event fired. Section mesh compiles
        // follow from the light task ClientPacketListener queues after this
        // returns (it re-queries the chunk, which the fallback below serves).
        level.onChunkLoaded(pos);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
            new net.minecraftforge.event.level.ChunkEvent.Load(chunk, false));
        cir.setReturnValue(chunk);
    }

    @Inject(method = "m_7587_" + GET_CHUNK_DESCRIPTOR, remap = false, require = 0,
        at = @At("TAIL"), cancellable = true)
    private void ctb$serveFarChunkSrg(int x, int z, ChunkStatus status, boolean requireChunk,
                                      CallbackInfoReturnable<LevelChunk> cir) {
        ctb$serveFarChunk(x, z, status, requireChunk, cir);
    }

    @Inject(method = "getChunk" + GET_CHUNK_DESCRIPTOR, remap = false, require = 0,
        at = @At("TAIL"), cancellable = true)
    private void ctb$serveFarChunkNamed(int x, int z, ChunkStatus status, boolean requireChunk,
                                        CallbackInfoReturnable<LevelChunk> cir) {
        ctb$serveFarChunk(x, z, status, requireChunk, cir);
    }

    private void ctb$serveFarChunk(int x, int z, ChunkStatus status, boolean requireChunk,
                                   CallbackInfoReturnable<LevelChunk> cir) {
        // TAIL is only reached on the miss path (missing in-range slot or out
        // of range), so a vanilla-served chunk is never overridden here.
        if (ArtilleryChunkCache.isEmpty()) return;
        LevelChunk current = cir.getReturnValue();
        if (current != null && !(current instanceof EmptyLevelChunk)) return;
        LevelChunk aux = ArtilleryChunkCache.get(x, z);
        if (aux != null) {
            ArtilleryChunkCache.probeServeHook(x, z);
            cir.setReturnValue(aux);
        }
    }
}
