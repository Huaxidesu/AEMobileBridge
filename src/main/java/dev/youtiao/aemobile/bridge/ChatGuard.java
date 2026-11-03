package dev.youtiao.aemobile.bridge;

import com.google.gson.JsonObject;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.netty.ChannelWrapper;
import net.md_5.bungee.netty.PipelineUtils;
import net.md_5.bungee.protocol.PacketWrapper;
import net.md_5.bungee.protocol.packet.Chat;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * 聊天守卫：把超过协议上限的聊天拆成多条合法消息后放行。
 *
 * <p>断点位置在代理。{@code UpstreamBridge.handle(Chat)} 先执行
 * {@code Preconditions.checkArgument(message.length() <= limit, "Chat message too long")}，
 * 之后才构造 {@code ChatEvent}；超限直接抛 {@code IllegalArgumentException}，
 * {@code HandlerBoss.exceptionCaught} 随即关闭整条连接，表现为玩家掉线而非消息被拒。
 * 因此任何基于 {@code ChatEvent} 的实现都无法参与，只能在管道更前端改写消息。
 *
 * <p>处理方式为拆分而非改走自定义通道。每段都是合法的 {@code C01PacketChatMessage}，
 * 子服的禁言、过滤、聊天记录与其他模组的 {@code ServerChatEvent} 全部照常生效；
 * 改走自定义负载可以做到内容无损，但会绕过
 * {@code NetHandlerPlayServer.processChatMessage} 的全部治理逻辑，被禁言的玩家
 * 只需改用长消息即可发言。
 *
 * <p>以 {@code /} 开头的消息不拆分：拆开后第二段起会被子服当作普通聊天执行。
 * 这类消息不转发，并向发送者输出提示。
 */
final class ChatGuard {

    /** 管道中的处理器名，用于幂等检查。 */
    static final String HANDLER_NAME = "aemobile-chatguard";

    /** 与 {@code UpstreamBridge.handle(Chat)} 一致：1.11（协议 315）起上限为 256。 */
    private static final int PROTOCOL_SWITCH = 315;
    private static final int LIMIT_LEGACY = 100;
    private static final int LIMIT_MODERN = 256;

    private final AEMobileBridge plugin;

    private volatile boolean enabled = true;
    /** 单条消息的总长上限（UTF-16 字符数），与协议上限无关。 */
    private volatile int maxLength = 500;
    private volatile boolean split = true;
    private volatile boolean notify = true;

    private final AtomicLong overLongCount = new AtomicLong();
    private final AtomicLong splitMessageCount = new AtomicLong();
    private final AtomicLong segmentCount = new AtomicLong();
    private final AtomicLong truncatedCount = new AtomicLong();
    private final AtomicLong rejectedCommandCount = new AtomicLong();

    ChatGuard(AEMobileBridge plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ config

    void configure(Configuration c) {
        enabled = c.getBoolean("chat-guard.enabled", true);
        maxLength = Math.max(1, c.getInt("chat-guard.max-length", 500));
        split = c.getBoolean("chat-guard.split", true);
        notify = c.getBoolean("chat-guard.notify", true);
    }

    static void writeDefaults(Configuration defaults) {
        defaults.set("chat-guard.enabled", true);
        defaults.set("chat-guard.max-length", 500);
        defaults.set("chat-guard.split", true);
        defaults.set("chat-guard.notify", true);
    }

    boolean isEnabled() {
        return enabled;
    }

    void setEnabled(boolean value) {
        enabled = value;
    }

    // ------------------------------------------------------------ injection

    /**
     * 在玩家到代理的连接上安装守卫，插入位置为 {@code inbound-boss} 之前，
     * 该处拿到的已经是解码后的 {@link PacketWrapper}。重复调用无副作用。
     *
     * <p>玩家到代理的管道在登录后不再重建（切服只重建代理到子服的管道），
     * 因此 {@code PostLoginEvent} 注入一次即可，{@code ServerConnectedEvent} 的补充调用
     * 用于覆盖管道被其他插件改动的情况。
     */
    void inject(ProxiedPlayer player) {
        try {
            Channel channel = upstreamChannel(player);
            if (channel == null) return;
            if (channel.pipeline().get(HANDLER_NAME) != null) return;
            channel.pipeline().addBefore(PipelineUtils.BOSS_HANDLER, HANDLER_NAME, new Handler(player));
            plugin.getLogger().log(Level.FINE, HANDLER_NAME + " injected for " + player.getName());
        } catch (Throwable t) {
            plugin.getLogger().warning("聊天守卫注入失败：" + player.getName() + " " + t);
        }
    }

    /**
     * 取玩家到代理的连接。BungeeCord 未公开该接口，只能从 {@code UserConnection}
     * 中类型为 {@link ChannelWrapper} 的字段读取；按字段类型而非字段名查找，
     * 避免版本间字段改名导致失效。
     */
    private static Channel upstreamChannel(ProxiedPlayer player) {
        for (Class<?> type = player.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!ChannelWrapper.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    ChannelWrapper wrapper = (ChannelWrapper) field.get(player);
                    return wrapper == null ? null : wrapper.getHandle();
                } catch (Throwable ignored) {
                    // 继续尝试下一个字段
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------ handler

    private final class Handler extends ChannelInboundHandlerAdapter {

        private final ProxiedPlayer player;

        Handler(ProxiedPlayer player) {
            this.player = player;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            PacketWrapper wrapper = asChatWrapper(msg);
            if (wrapper == null) {
                ctx.fireChannelRead(msg);
                return;
            }
            Chat chat = (Chat) wrapper.packet;
            String text = chat.getMessage();
            int limit = protocolLimit(player);
            if (text == null || text.length() <= limit) {
                ctx.fireChannelRead(msg);
                return;
            }
            handleLongChat(ctx, wrapper, chat, text, limit);
        }

        /**
         * 超长消息的处理。
         *
         * <p>首段复用原 {@link PacketWrapper}：它的 {@code buf} 由 {@code HandlerBoss}
         * 在转发后释放，不能再新建一个指向同一缓冲的包装，否则会重复释放。
         * 后续各段使用 {@link Unpooled#EMPTY_BUFFER}，其 {@code release()} 为空操作，
         * 满足 {@code HandlerBoss} 对每个包装无条件调用 {@code trySingleRelease()} 的约束。
         */
        private void handleLongChat(ChannelHandlerContext ctx, PacketWrapper wrapper,
                                    Chat chat, String text, int limit) {
            overLongCount.incrementAndGet();

            if (!enabled) {
                // 守卫停用时保持原有行为，便于对照验证与快速回滚
                ctx.fireChannelRead(wrapper);
                return;
            }

            if (text.startsWith("/")) {
                // 命令不拆分：第二段起会被子服当作普通聊天执行
                wrapper.trySingleRelease();
                rejectedCommandCount.incrementAndGet();
                notifySender("命令超过 " + limit + " 字符，未发送");
                return;
            }

            String body = truncateSafely(text, maxLength);
            boolean cut = body.length() < text.length();
            List<String> parts = split ? split(body, limit) : single(body, limit);

            chat.setMessage(parts.get(0));
            ctx.fireChannelRead(wrapper);
            for (int i = 1; i < parts.size(); i++) {
                Chat piece = new Chat(parts.get(i), chat.getPosition());
                ctx.fireChannelRead(new PacketWrapper(piece, Unpooled.EMPTY_BUFFER));
            }

            if (parts.size() > 1) {
                splitMessageCount.incrementAndGet();
                segmentCount.addAndGet(parts.size());
                if (notify) {
                    notifySender("消息超过 " + limit + " 字符，已分 " + parts.size() + " 条发送"
                            + (cut ? "（单条上限 " + maxLength + " 字符，超出部分未发送）" : ""));
                }
            } else {
                truncatedCount.incrementAndGet();
                if (notify) {
                    notifySender("消息超过 " + limit + " 字符，已截断至 " + parts.get(0).length() + " 字符");
                }
            }
        }

        private void notifySender(String message) {
            try {
                if (player.isConnected()) player.sendMessage(message);
            } catch (Throwable ignored) {
                // 提示失败不影响消息转发
            }
        }
    }

    // ------------------------------------------------------------ helpers

    private static PacketWrapper asChatWrapper(Object msg) {
        if (!(msg instanceof PacketWrapper)) return null;
        PacketWrapper wrapper = (PacketWrapper) msg;
        return wrapper.packet instanceof Chat ? wrapper : null;
    }

    /**
     * 玩家协议版本对应的单条上限，与 {@code UpstreamBridge.handle(Chat)} 的判定保持一致。
     * 版本读取失败时按 1.7.10 处理，该值是更严格的一侧。
     */
    private static int protocolLimit(ProxiedPlayer player) {
        try {
            return player.getPendingConnection().getVersion() >= PROTOCOL_SWITCH ? LIMIT_MODERN : LIMIT_LEGACY;
        } catch (Throwable t) {
            return LIMIT_LEGACY;
        }
    }

    static String truncateSafely(String text, int max) {
        if (max <= 0 || text == null) return "";
        if (text.length() <= max) return text;
        int end = max;
        // 末位是高位代理字符时回退一位，避免切出孤立的代理字符
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return end <= 0 ? "" : text.substring(0, end);
    }

    /**
     * 按 UTF-16 单元切分，保证每段长度不超过 {@code limit}。
     *
     * <p>两条约束：
     * <ul>
     *   <li>不切出孤立的代理字符（emoji 每个占两个单元），末位是高位代理字符时回退一位；</li>
     *   <li>除首段外的各段不以 {@code /} 开头。代理对以 {@code /} 开头的消息会执行
     *       {@code dispatchCommand}，玩家可以在超长消息的切分点后植入 {@code /op ...} 之类的命令。
     *       正常情况通过向前收窄切点解决，内容无损；连续斜杠占满整段时改用全角斜杠。</li>
     * </ul>
     */
    static List<String> split(String text, int limit) {
        List<String> out = new ArrayList<String>();
        if (text == null || text.isEmpty()) {
            out.add("");
            return out;
        }
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + limit, text.length());
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
                end--;
            }
            if (end <= start) {
                end = Math.min(start + limit, text.length());
            }
            // 下一段以 / 开头时向前收窄切点，使 / 仍留在本段末尾。
            // 收窄幅度超过半段说明遇到长串斜杠，此时放弃收窄，改由下面的全角替换处理，
            // 否则每段会退化成只有一两个字符。
            int adjusted = end;
            while (adjusted > start + 1 && adjusted < text.length() && text.charAt(adjusted) == '/') {
                adjusted--;
            }
            if (adjusted > start && adjusted > start + limit / 2) end = adjusted;

            String piece = text.substring(start, end);
            if (piece.startsWith("/")) {
                // 连续斜杠占满整段的病态输入：改用全角斜杠，避免被代理当作命令
                piece = "\uFF0F" + piece.substring(1);
            }
            out.add(piece);
            start = end;
        }
        return out;
    }

    private static List<String> single(String text, int limit) {
        List<String> out = new ArrayList<String>(1);
        out.add(truncateSafely(text, limit));
        return out;
    }

    // ------------------------------------------------------------ status

    /**
     * 返回当前配置与计数。{@code changeEnabled} 为真时先按 {@code newEnabled} 修改开关，
     * 用于在不重启代理的前提下对照验证。
     */
    JsonObject status(boolean changeEnabled, Boolean newEnabled) {
        if (changeEnabled && newEnabled != null) {
            enabled = newEnabled;
        }
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("enabled", enabled);
        out.addProperty("maxLength", maxLength);
        out.addProperty("split", split);
        out.addProperty("notify", notify);
        out.addProperty("overLong", overLongCount.get());
        out.addProperty("splitMessages", splitMessageCount.get());
        out.addProperty("segments", segmentCount.get());
        out.addProperty("truncated", truncatedCount.get());
        out.addProperty("rejectedCommands", rejectedCommandCount.get());
        return out;
    }
}
