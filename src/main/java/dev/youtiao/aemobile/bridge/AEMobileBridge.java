package dev.youtiao.aemobile.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
import net.md_5.bungee.event.EventHandler;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * AEMobileBridge：代理侧通用命令桥与 TAB 样式下发。
 *
 * <p>后端 mod 通过 127.0.0.1 的 JSON TCP 把「以某个玩家身份执行一条代理命令」的请求发过来，
 * 本插件用捕获型 CommandSender 执行，把命令返回的聊天原样回给 App —— 不发到公屏。
 *
 * <p>同时注册 ServerUtilities 的 {@code SU|TabHF} 信道，玩家进入子服时自动通过 BC 代理下发
 * 定制的对称 GTNH 风格 TAB Header/Footer，客户端无需修改配置、无需重启即可实时生效。
 */
public final class AEMobileBridge extends Plugin implements Listener {

    public static final String TAB_CHANNEL = "SU|TabHF";
    private static final String DEFAULT_TAB_HEADER = "&b&l{ONLINE} &7位冒险者在线";
    private static final String DEFAULT_TAB_FOOTER = "&7本服: &b{SERVER}";

    private static final Gson GSON = new Gson();
    private static final String DEFAULT_SECRET = "aemobile-bridge";
    private static final int DEFAULT_PORT = 44445;
    private static final int DEFAULT_CAPTURE_MS = 800;

    private final Map<String, long[]> rate = new ConcurrentHashMap<String, long[]>();
    private final Set<String> offlinePermissions = new HashSet<String>();
    private final Set<String> allowedCommands = new HashSet<String>();
    private final List<String> prefixes = new ArrayList<String>();
    private volatile boolean running;
    private ServerSocket serverSocket;
    private String bind = "127.0.0.1";
    private String secret = DEFAULT_SECRET;
    private int port = DEFAULT_PORT;
    private int defaultCaptureMs = DEFAULT_CAPTURE_MS;

    private boolean tabEnabled = true;
    /** 进服后按这些毫秒数逐次下发，跨过 Forge 的 FML 握手窗口（握手期间自定义负载会被丢掉）。 */
    private int[] tabDelaysMs = {2500, 5000, 8000, 12000, 18000, 26000};
    private String tabHeader = DEFAULT_TAB_HEADER;
    private String tabFooter = DEFAULT_TAB_FOOTER;
    /** {@code tab_set} 下发的临时覆盖，只在内存里，BC 重启即失效。 */
    private volatile String tabHeaderOverride;
    private volatile String tabFooterOverride;

    @Override
    public void onEnable() {
        loadConfig();
        startServer();
        if (tabEnabled) {
            getProxy().registerChannel(TAB_CHANNEL);
        }
        getProxy().getPluginManager().registerListener(this, this);
        getLogger().info("AEMobileBridge listening on " + bind + ":" + port
                + " commands=" + allowedCommands + " prefixes=" + prefixes
                + " tabEnabled=" + tabEnabled);
    }

    @Override
    public void onDisable() {
        running = false;
        if (tabEnabled) {
            try {
                getProxy().unregisterChannel(TAB_CHANNEL);
            } catch (Throwable ignored) {
            }
        }
        try {
            getProxy().getPluginManager().unregisterListener(this);
        } catch (Throwable ignored) {
        }
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ------------------------------------------------------------ tab listener

    /**
     * 玩家连上子服后按 {@link #tabDelaysMs} 逐次下发。
     *
     * <p>为什么不是发一次：{@code ServerConnectedEvent} 触发时客户端还在跑 Forge 的 FML 握手
     * （本机实测握手在 <em>进服后 1 秒左右</em>完成，整合包越重越慢），握手期间的自定义负载
     * 会被客户端网络层直接丢掉，模组收不到 {@code ClientCustomPacketEvent}。所以用一个
     * 递增的延迟阶梯反复下发，落地的那一次就生效；负载只有几百字节，重复下发没有副作用。
     */
    @EventHandler
    public void onServerConnected(final ServerConnectedEvent event) {
        if (!tabEnabled) return;
        final ProxiedPlayer player = event.getPlayer();
        final String serverName = event.getServer() != null && event.getServer().getInfo() != null
                ? event.getServer().getInfo().getName() : "";
        for (int i = 0; i < tabDelaysMs.length; i++) {
            final int attempt = i + 1;
            final int delay = tabDelaysMs[i];
            getProxy().getScheduler().schedule(this, new Runnable() {
                @Override
                public void run() {
                    if (!player.isConnected()) return;
                    // 切服了就别再往旧连接上发
                    String current = player.getServer() != null && player.getServer().getInfo() != null
                            ? player.getServer().getInfo().getName() : "";
                    if (!serverName.isEmpty() && !serverName.equals(current)) return;
                    sendTabHeaderFooter(player, current, attempt);
                }
            }, delay, TimeUnit.MILLISECONDS);
        }
    }

    public void sendTabHeaderFooter(ProxiedPlayer player, String serverName) {
        sendTabHeaderFooter(player, serverName, 0);
    }

    private void sendTabHeaderFooter(ProxiedPlayer player, String serverName, int attempt) {
        try {
            String rawHeader = tabHeaderOverride != null ? tabHeaderOverride : tabHeader;
            String rawFooter = tabFooterOverride != null ? tabFooterOverride : tabFooter;
            JsonObject json = new JsonObject();
            String h = expand(rawHeader, player, serverName);
            String f = expand(rawFooter, player, serverName);
            if (!h.isEmpty()) json.addProperty("header", h);
            if (!f.isEmpty()) json.addProperty("footer", f);
            if (json.entrySet().isEmpty()) {
                // 空包会把客户端的 hasServerData 置真、页眉页脚被清空，比不发更糟
                getLogger().warning("tab header/footer is empty, skipped for " + player.getName());
                return;
            }
            byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
            player.sendData(TAB_CHANNEL, bytes);
            getLogger().log(java.util.logging.Level.FINE, "SU|TabHF -> " + player.getName()
                    + " (attempt " + attempt + ", " + bytes.length + "B)");
        } catch (Throwable t) {
            getLogger().warning("Failed to send " + TAB_CHANNEL + " to " + player.getName() + ": " + t);
        }
    }

    private static String expand(String text, ProxiedPlayer player, String serverName) {
        if (text == null) return "";
        return text
                .replace("{PLAYER}", player.getName())
                .replace("{SERVER}", serverName == null ? "" : serverName)
                .replace("{ONLINE}", String.valueOf(ProxyServer.getInstance().getOnlineCount()));
    }

    // ------------------------------------------------------------ config

    private void loadConfig() {
        try {
            File folder = getDataFolder();
            if (!folder.isDirectory() && !folder.mkdirs()) {
                getLogger().warning("cannot create " + folder);
            }
            File file = new File(folder, "config.yml");
            ConfigurationProvider provider = ConfigurationProvider.getProvider(YamlConfiguration.class);
            if (!file.isFile()) {
                Configuration defaults = new Configuration();
                defaults.set("bind", "127.0.0.1");
                defaults.set("port", DEFAULT_PORT);
                defaults.set("secret", DEFAULT_SECRET);
                defaults.set("capture-ms", DEFAULT_CAPTURE_MS);
                defaults.set("allow-commands", Arrays.asList("music"));
                defaults.set("prefixes", Arrays.asList("[肥猫电台]", "[AllMusic3]"));
                defaults.set("offline-permissions", Arrays.asList(
                        "allmusic.play", "allmusic.search", "allmusic.vote",
                        "allmusic.list", "allmusic.cancel", "allmusic.mute", "allmusic.push"));
                defaults.set("tab.enabled", true);
                defaults.set("tab.delays-ms", Arrays.asList(2500, 5000, 8000, 12000, 18000, 26000));
                defaults.set("tab.header", DEFAULT_TAB_HEADER);
                defaults.set("tab.footer", DEFAULT_TAB_FOOTER);
                provider.save(defaults, file);
            }
            Configuration c = provider.load(file);
            bind = c.getString("bind", "127.0.0.1");
            port = c.getInt("port", DEFAULT_PORT);
            secret = c.getString("secret", DEFAULT_SECRET);
            defaultCaptureMs = c.getInt("capture-ms", DEFAULT_CAPTURE_MS);
            allowedCommands.clear();
            for (String s : c.getStringList("allow-commands")) {
                if (s != null && !s.trim().isEmpty()) allowedCommands.add(s.trim().toLowerCase(Locale.ROOT));
            }
            if (allowedCommands.isEmpty()) allowedCommands.add("music");
            prefixes.clear();
            prefixes.addAll(c.getStringList("prefixes"));
            offlinePermissions.clear();
            offlinePermissions.addAll(c.getStringList("offline-permissions"));

            tabEnabled = c.getBoolean("tab.enabled", true);
            tabDelaysMs = parseDelays(c.get("tab.delays-ms"));
            // 老配置里的 tab.delay-ms 还能用：只有一个值时当作唯一一次下发
            if (c.get("tab.delays-ms") == null && c.get("tab.delay-ms") != null) {
                tabDelaysMs = new int[]{Math.max(0, c.getInt("tab.delay-ms", 2500))};
            }
            tabHeader = c.getString("tab.header", DEFAULT_TAB_HEADER);
            tabFooter = c.getString("tab.footer", DEFAULT_TAB_FOOTER);
        } catch (Throwable t) {
            getLogger().warning("config load failed, using defaults: " + t);
            allowedCommands.add("music");
        }
    }

    private static int[] parseDelays(Object raw) {
        java.util.List<Integer> out = new ArrayList<Integer>();
        if (raw instanceof Iterable) {
            for (Object o : (Iterable<?>) raw) {
                if (o instanceof Number) out.add(Math.max(0, ((Number) o).intValue()));
                else if (o != null) {
                    try {
                        out.add(Math.max(0, Integer.parseInt(o.toString().trim())));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        if (out.isEmpty()) return new int[]{2500, 5000, 8000, 12000, 18000, 26000};
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        Arrays.sort(arr);
        return arr;
    }

    // ------------------------------------------------------------ server

    private void startServer() {
        running = true;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "AEMobileBridge-accept");
        thread.setDaemon(true);
        thread.start();
    }

    private void acceptLoop() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(bind, port));
        } catch (Throwable t) {
            getLogger().severe("cannot bind " + bind + ":" + port + " — " + t);
            return;
        }
        while (running) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (Throwable t) {
                if (running) getLogger().warning("accept failed: " + t);
                break;
            }
            Thread worker = new Thread(new ClientHandler(socket), "AEMobileBridge-client");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private final class ClientHandler implements Runnable {
        private final Socket socket;

        ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                socket.setSoTimeout(12000);
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter writer = new PrintWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                String line = reader.readLine();
                if (line == null) return;
                JsonObject response = handle(line);
                writer.println(GSON.toJson(response));
            } catch (Throwable t) {
                getLogger().warning("request failed: " + t);
            } finally {
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ------------------------------------------------------------ handle

    private JsonObject handle(String line) {
        try {
            JsonObject req = new JsonParser().parse(line).getAsJsonObject();
            String op = str(req, "op").trim().toLowerCase(Locale.ROOT);

            String givenSecret = str(req, "secret");
            if (secret != null && !secret.isEmpty() && !secret.equals(givenSecret)) {
                return error("BAD_SECRET");
            }

            if ("tab_sync".equals(op)) {
                int count = 0;
                for (ProxiedPlayer p : getProxy().getPlayers()) {
                    String sName = p.getServer() != null && p.getServer().getInfo() != null
                            ? p.getServer().getInfo().getName() : "";
                    sendTabHeaderFooter(p, sName, 0);
                    count++;
                }
                JsonObject out = new JsonObject();
                out.addProperty("ok", true);
                out.addProperty("synced", count);
                return out;
            }

            if ("tab_status".equals(op)) {
                JsonObject out = new JsonObject();
                out.addProperty("ok", true);
                out.addProperty("enabled", tabEnabled);
                JsonArray d = new JsonArray();
                for (int v : tabDelaysMs) d.add(new JsonPrimitive(v));
                out.add("delays", d);
                out.addProperty("header", tabHeaderOverride != null ? tabHeaderOverride : tabHeader);
                out.addProperty("footer", tabFooterOverride != null ? tabFooterOverride : tabFooter);
                out.addProperty("hasOverride", tabHeaderOverride != null || tabFooterOverride != null);
                return out;
            }

            if ("tab_set".equals(op)) {
                // 内存覆盖，用来在不重启 BC 的前提下试文案；传空串表示清除覆盖
                if (req.has("header")) {
                    String h = str(req, "header");
                    tabHeaderOverride = h.isEmpty() ? null : h;
                }
                if (req.has("footer")) {
                    String f = str(req, "footer");
                    tabFooterOverride = f.isEmpty() ? null : f;
                }
                int count = 0;
                for (ProxiedPlayer p : getProxy().getPlayers()) {
                    String sName = p.getServer() != null && p.getServer().getInfo() != null
                            ? p.getServer().getInfo().getName() : "";
                    sendTabHeaderFooter(p, sName, 0);
                    count++;
                }
                JsonObject out = new JsonObject();
                out.addProperty("ok", true);
                out.addProperty("synced", count);
                out.addProperty("hasOverride", tabHeaderOverride != null || tabFooterOverride != null);
                return out;
            }

            if ("tab_probe".equals(op)) {
                // 故意发一段坏 JSON：客户端 ServerUtilities 解析失败会打
                // "Failed to parse SU|TabHF packet" 警告 —— 客户端日志里出现这条，
                // 就说明信道是通的，问题在文案/渲染而不是投递。
                byte[] bad = ("{\"probe\":\"aemobile\"," + System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8);
                int count = 0;
                for (ProxiedPlayer p : getProxy().getPlayers()) {
                    p.sendData(TAB_CHANNEL, bad);
                    count++;
                }
                JsonObject out = new JsonObject();
                out.addProperty("ok", true);
                out.addProperty("probed", count);
                out.addProperty("payload", new String(bad, StandardCharsets.UTF_8));
                return out;
            }

            if (!op.isEmpty() && !"exec".equals(op)) return error("UNKNOWN_OP");

            String playerName = str(req, "player").trim();
            if (!playerName.matches("[A-Za-z0-9_]{1,16}")) return error("BAD_PLAYER");

            String limited = rateCheck(playerName);
            if (limited != null) return error(limited);

            String command = str(req, "command").trim();
            if (command.startsWith("/")) command = command.substring(1);
            if (command.isEmpty() || command.length() > 512
                    || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) {
                return error("BAD_COMMAND");
            }
            String root = command.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
            if (!allowedCommands.contains(root)) return error("COMMAND_NOT_ALLOWED:" + root);

            int captureMs = req.has("captureMs") ? req.get("captureMs").getAsInt() : defaultCaptureMs;
            captureMs = Math.max(100, Math.min(10000, captureMs));

            ProxiedPlayer online = ProxyServer.getInstance().getPlayer(playerName);
            CaptureSender sender = new CaptureSender(online, playerName, offlinePermissions);
            boolean found = ProxyServer.getInstance().getPluginManager()
                    .dispatchCommand(sender, command);

            try {
                Thread.sleep(captureMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            JsonObject out = new JsonObject();
            out.addProperty("ok", found);
            out.addProperty("found", found);
            out.addProperty("online", online != null);
            if (!found) out.addProperty("error", "UNKNOWN_COMMAND");
            JsonArray arr = new JsonArray();
            for (String l : cleanLines(sender.lines())) arr.add(new JsonPrimitive(l));
            out.add("lines", arr);
            return out;
        } catch (Throwable t) {
            return error("EXCEPTION:" + t);
        }
    }

    private static JsonObject error(String message) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", false);
        out.addProperty("found", false);
        out.addProperty("online", false);
        out.addProperty("error", message);
        out.add("lines", new JsonArray());
        return out;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private List<String> cleanLines(List<String> raw) {
        List<String> out = new ArrayList<String>();
        for (String l : raw) {
            if (l == null) continue;
            String s = l.replaceAll("§[0-9a-fk-orA-FK-OR]", "");
            for (String p : prefixes) {
                if (p != null && !p.isEmpty() && s.startsWith(p)) {
                    s = s.substring(p.length());
                    break;
                }
            }
            s = s.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    /** 单玩家 1 秒一条、每分钟 30 条 */
    private String rateCheck(String player) {
        long now = System.currentTimeMillis();
        long[] st = rate.get(player);
        if (st == null) {
            st = new long[]{0L, 0L, now};
            rate.put(player, st);
        }
        synchronized (st) {
            if (now - st[2] >= 60000L) {
                st[2] = now;
                st[1] = 0L;
            }
            if (st[1] >= 30) return "RATE_LIMITED";
            if (now - st[0] < 1000L) return "RATE_LIMITED";
            st[0] = now;
            st[1]++;
        }
        return null;
    }
}
