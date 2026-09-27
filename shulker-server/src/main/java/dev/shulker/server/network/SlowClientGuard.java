package dev.shulker.server.network;

import com.mojang.logging.LogUtils;
import dev.shulker.server.ShulkerConfig;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOutboundBuffer;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

/**
 * Disconnects clients whose outbound backlog grows too large.
 *
 * <p>Minecraft never checks whether a client keeps up: every packet is encoded into a direct
 * buffer and queued. A client that stops reading is dropped by the keep-alive timeout after
 * 30 seconds, but within those 30 seconds a busy area can queue tens of megabytes per player.
 * With enough such connections the server runs out of direct memory and every player is
 * affected (reproduced with 100 bots: {@code OutOfDirectMemoryError} at 4 GiB). This guard
 * caps the backlog per connection.
 */
public final class SlowClientGuard {

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final Component MESSAGE = Component.literal("Connection too slow: the server could not deliver data to you");

    private SlowClientGuard() {
    }

    /** Called every tick for every connection, on the server thread. */
    public static void check(final Connection connection, final Channel channel) {
        if (!ShulkerConfig.slowClientProtection || channel == null || !channel.isActive()) {
            return;
        }
        final ChannelOutboundBuffer buffer = channel.unsafe().outboundBuffer();
        final long pending = buffer == null ? 0 : buffer.totalPendingWriteBytes();
        if (pending > (long) ShulkerConfig.slowClientMaxPendingMegabytes << 20) {
            LOGGER.warn("Disconnecting {} ({}): {} MiB waiting to be sent",
                connection.getPlayer() != null ? connection.getPlayer().getScoreboardName() : "unknown player",
                connection.getRemoteAddress(), pending >> 20);
            // No disconnect packet: it would only join the queue the client is not reading.
            connection.disconnect(MESSAGE);
            channel.close();
        }
    }
}
