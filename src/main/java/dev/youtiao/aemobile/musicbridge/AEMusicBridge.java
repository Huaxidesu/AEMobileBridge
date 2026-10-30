package dev.youtiao.aemobile.musicbridge;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * App → 后端 mod → 本插件（BC 代理）→ AllMusic 的桥。
 *
 * <p>为什么要在代理上单独做一个插件：AllMusic 的 BC 版命令（/music）只存在于代理进程里，
 * 后端的 Forge mod 根本看不到它。这里开一个 127.0.0.1 的 JSON TCP 端口，后端 mod 转发请求，
 * 本插件用捕获型 CommandSender 执行 /music 命令，把返回的聊天原样回给 App。
 *
 * <p>离线玩家也能用：用一个虚拟 CommandSender（名字 = 玩家名，权限按 config 放行），
 * search/list/vote/点歌 都会走 AllMusic 的命令逻辑；如果某个子命令要求真实 ProxiedPlayer，
 * 会返回 AllMusic 的报错文本，App 原样展示。
 */
public final class AEMusicBridge extends Plugin {

    private static final Gson GSON = new Gson();
    private static final String DEFAULT_SECRET = "aemobile-music";
    private static final int DEFAULT_PORT = 44445;

    private final Map<String, long[]> rate = new ConcurrentHashMap<String, long[]>();
    private final Set<String> offlinePermissions = new HashSet<String>();
    private final List<String> prefixes = new ArrayList<String>();
    private volatile boolean running;
    private ServerSocket serverSocket;
    private String secret = DEFAULT_SECRET;
    private int port = DEFAULT_PORT;
    private String bind = "127.0.0.1";

    @Override
    public void onEnable() {
        loadConfig();
        startServer();
        getLogger().info("AEMobileMusicBridge listening on 127.0.0.1:" + port);
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
            prefixes.clear();
            prefixes.addAll(c.getStringList("prefixes"));
            offlinePermissions.clear();
            offlinePermissions.addAll(c.getStringList("offline-permissions"));
            getLogger().info("prefixes=" + prefixes + " offlinePermissions=" + offlinePermissions);
        } catch (Throwable t) {
            getLogger().warning("config load failed, using defaults: " + t);
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
        }, "AEMobileMusicBridge-accept");
        thread.setDaemon(true);
        thread.start();
    }

    private void acceptLoop() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(bind, port));
        } catch (Throwable t) {
            getLogger().severe("cannot bind 127.0.0.1:" + port + " — " + t);
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
            Thread worker = new Thread(new ClientHandler(socket), "AEMobileMusicBridge-client");
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
                socket.setSoTimeout(10000);
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
        JsonObject out = new JsonObject();
        try {
            JsonObject req = new JsonParser().parse(line).getAsJsonObject();
            String givenSecret = str(req, "secret");
            if (secret != null && !secret.isEmpty() && !secret.equals(givenSecret)) {
                return error(out, "BAD_SECRET");
            }

            String playerName = str(req, "player").trim();
            if (!playerName.matches("[A-Za-z0-9_]{1,16}")) {
                return error(out, "BAD_PLAYER");
            }
            String limited = rateCheck(playerName);
            if (limited != null) {
                return error(out, limited);
            }

            String cmd = str(req, "cmd").trim().toLowerCase(Locale.ROOT);
            if (cmd.isEmpty() || !cmd.matches("[a-z0-9]+")) {
                return error(out, "BAD_COMMAND");
            }
            List<String> args = new ArrayList<String>();
            JsonElement argsEl = req.get("args");
            if (argsEl != null && argsEl.isJsonArray()) {
                JsonArray arr = argsEl.getAsJsonArray();
                for (JsonElement e : arr) {
                    String a = e == null || e.isJsonNull() ? "" : e.getAsString();
                    a = a.replace('\n', ' ').replace('\r', ' ').trim();
                    if (a.length() > 80) a = a.substring(0, 80);
                    args.add(a);
                }
            }
            if (args.size() > 6) {
                return error(out, "TOO_MANY_ARGS");
            }

            StringBuilder command = new StringBuilder("music ").append(cmd);
            for (String a : args) {
                if (a.isEmpty()) continue;
                command.append(' ').append(a);
            }

            ProxiedPlayer online = ProxyServer.getInstance().getPlayer(playerName);
            CaptureSender sender = new CaptureSender(
                    online,
                    playerName,
                    offlinePermissions);
            boolean found = ProxyServer.getInstance().getPluginManager()
                    .dispatchCommand(sender, command.toString());

            // 搜歌是异步的：命令先回“正在排队”，结果晚一点才 sendMessage 给同一个 sender。
            // 这里留一个窗口把延迟消息也收进来；其它命令 400ms 足够。
            try {
                Thread.sleep("search".equals(cmd) ? 3500L : 400L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            List<String> lines = cleanLines(sender.lines());
            out.addProperty("ok", found);
            out.addProperty("online", online != null);
            if (!found) out.addProperty("error", "COMMAND_NOT_FOUND");
            JsonArray arr = new JsonArray();
            for (String l : lines) arr.add(new JsonPrimitive(l));
            out.add("lines", arr);
            return out;
        } catch (Throwable t) {
            return error(out, "EXCEPTION:" + t);
        }
    }

    private static JsonObject error(JsonObject out, String message) {
        out.addProperty("ok", false);
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

    /** 单玩家 500ms 一条、每分钟 30 条 */
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
