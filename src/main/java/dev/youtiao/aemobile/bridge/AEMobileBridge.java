package dev.youtiao.aemobile.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;

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

/**
 * AEMobileBridge：代理侧通用命令桥。
 *
 * <p>后端 mod 通过 127.0.0.1 的 JSON TCP 把「以某个玩家身份执行一条代理命令」的请求发过来，
 * 本插件用捕获型 CommandSender 执行，把命令返回的聊天原样回给 App —— 不发到公屏。
 *
 * <p>目前只开了 {@code music} 这个命令根（AllMusic 的 /music）；以后要点歌以外的功能，
 * 把新的命令根加到 config 的 {@code allow-commands} 即可，协议不用改。
 * 搜歌这类异步命令可以用 {@code captureMs} 指定收集窗口（默认 800ms，搜歌由 mod 传 3500ms）。
 *
 * <p>离线玩家也能用：虚拟 CommandSender（名字 = 请求里的玩家名，权限按 offline-permissions 放行）。
 */
public final class AEMobileBridge extends Plugin {

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

    @Override
    public void onEnable() {
        loadConfig();
        startServer();
        getLogger().info("AEMobileBridge listening on " + bind + ":" + port
                + " commands=" + allowedCommands + " prefixes=" + prefixes);
    }

    @Override
    public void onDisable() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Exception ignored) {
            }
        }
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
        } catch (Throwable t) {
            getLogger().warning("config load failed, using defaults: " + t);
            allowedCommands.add("music");
        }
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
            if (!op.isEmpty() && !"exec".equals(op)) return error("UNKNOWN_OP");

            String givenSecret = str(req, "secret");
            if (secret != null && !secret.isEmpty() && !secret.equals(givenSecret)) {
                return error("BAD_SECRET");
            }

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
