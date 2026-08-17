package com.cbc_terminal_ballistics.network;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import com.cbc_terminal_ballistics.ballistics.TBCaliber;
import com.cbc_terminal_ballistics.state.EmbeddedShell;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.StreamDecoder;
import net.minecraft.network.codec.StreamEncoder;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record ClientboundEmbeddedShellsPacket(BlockPos pos, UUID subLevelId, List<EmbeddedShell> shells) implements CustomPacketPayload {
    public static final Type<ClientboundEmbeddedShellsPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(CBCTerminalBallistics.MOD_ID, "embedded_shells"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundEmbeddedShellsPacket> STREAM_CODEC = StreamCodec.of(
        (StreamEncoder<RegistryFriendlyByteBuf, ClientboundEmbeddedShellsPacket>) (buf, packet) -> packet.encode(buf),
        (StreamDecoder<RegistryFriendlyByteBuf, ClientboundEmbeddedShellsPacket>) ClientboundEmbeddedShellsPacket::decode);

    private void encode(RegistryFriendlyByteBuf buf) {
        buf.writeInt(pos.getX()); buf.writeInt(pos.getY()); buf.writeInt(pos.getZ());
        buf.writeBoolean(subLevelId != null); if (subLevelId != null) buf.writeUUID(subLevelId);
        buf.writeVarInt(shells.size());
        for (EmbeddedShell shell : shells) {
            buf.writeUUID(shell.id()); buf.writeEnum(shell.caliber()); buf.writeEnum(shell.face());
            buf.writeFloat(shell.x()); buf.writeFloat(shell.y()); buf.writeFloat(shell.z());
            buf.writeFloat(shell.directionX()); buf.writeFloat(shell.directionY()); buf.writeFloat(shell.directionZ());
            buf.writeBoolean(shell.visualState() != null); if (shell.visualState() != null) buf.writeNbt(NbtUtils.writeBlockState(shell.visualState()));
            buf.writeBoolean(shell.visualItem() != null && !shell.visualItem().isEmpty()); if (shell.visualItem() != null && !shell.visualItem().isEmpty()) buf.writeNbt(shell.visualItem().save(buf.registryAccess()));
            buf.writeFloat(shell.depth()); buf.writeLong(shell.gameTime());
        }
    }

    private static ClientboundEmbeddedShellsPacket decode(RegistryFriendlyByteBuf buf) {
        BlockPos pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt()); UUID subLevelId = buf.readBoolean() ? buf.readUUID() : null;
        int count = buf.readVarInt(); List<EmbeddedShell> shells = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID id = buf.readUUID(); TBCaliber caliber = buf.readEnum(TBCaliber.class); Direction face = buf.readEnum(Direction.class);
            float x = buf.readFloat(), y = buf.readFloat(), z = buf.readFloat(), dx = buf.readFloat(), dy = buf.readFloat(), dz = buf.readFloat();
            BlockStateHolder state = readState(buf); ItemStack item = readItem(buf);
            shells.add(new EmbeddedShell(id, caliber, face, x, y, z, dx, dy, dz, state.state(), item, buf.readFloat(), buf.readLong()));
        }
        return new ClientboundEmbeddedShellsPacket(pos, subLevelId, shells);
    }

    private static BlockStateHolder readState(RegistryFriendlyByteBuf buf) { if (!buf.readBoolean()) return new BlockStateHolder(null); CompoundTag tag = buf.readNbt(); return new BlockStateHolder(tag == null ? null : EmbeddedShell.readVisualState(tag, buf.registryAccess())); }
    private static ItemStack readItem(RegistryFriendlyByteBuf buf) { if (!buf.readBoolean()) return ItemStack.EMPTY; CompoundTag tag = buf.readNbt(); return tag == null ? ItemStack.EMPTY : ItemStack.parse(buf.registryAccess(), tag).orElse(ItemStack.EMPTY); }
    private record BlockStateHolder(net.minecraft.world.level.block.state.BlockState state) {}

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void handle(ClientboundEmbeddedShellsPacket packet, IPayloadContext ctx) { ctx.enqueueWork(() -> { try { Class<?> h = Class.forName("com.cbc_terminal_ballistics.client.ClientPacketHandlers"); h.getMethod("handleEmbeddedShells", ClientboundEmbeddedShellsPacket.class).invoke(null, packet); } catch (ReflectiveOperationException ex) { throw new RuntimeException(ex); } }); }
}
