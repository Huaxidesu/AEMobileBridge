# AEMobileMusicBridge

App 点歌 → 后端 mod → 本插件（BungeeCord/HexaCord）→ AllMusic3 的桥。

AllMusic 的 BC 版 `/music` 命令只存在于**代理进程**，后端的 Forge mod 看不到它。
本插件开一个 localhost JSON TCP 端口，后端 `music` op 把请求转过来；插件用捕获型
`CommandSender` 执行 `/music ...`，把返回文本只回给 App，不广播到公屏。

## 部署

1. 把 `AEMobileMusicBridge.jar` 放进 BC 的 `plugins/`；
2. 重启 BungeeCord（或重载插件）；
3. 编辑 `plugins/AEMobileMusicBridge/config.yml`：

```yaml
bind: 127.0.0.1          # BC 和后端同机；分开部署时改 0.0.0.0 并加防火墙
port: 44445
secret: aemobile-music   # 必须和后端 AEMobile.cfg 的 [music] secret 一致
prefixes:
  - "[肥猫电台]"
  - "[AllMusic3]"
offline-permissions:
  - allmusic.play
  - allmusic.search
  - allmusic.vote
  - allmusic.list
  - allmusic.cancel
  - allmusic.mute
  - allmusic.push
```

4. 后端 `config/AEMobile.cfg`：

```ini
music {
    B:enabled=true
    S:host=127.0.0.1
    I:port=44445
    S:secret=aemobile-music
    S:prefixes <
        [AllMusic3]
        [肥猫电台]
    >
    S:publicMarkers <
        正在播放
    >
}
```

## 协议

TCP 一行一个 JSON：

```json
{"secret":"aemobile-music","player":"Steve","cmd":"search","args":["晴天"]}
```

返回：

```json
{"ok":true,"online":false,"lines":["正在排队搜歌","搜索结果","1->晴天 | ..."]}
```

- `player` 必须是 `[A-Za-z0-9_]{1,16}`；
- `cmd` 只能是 `[a-z0-9]+`（`search` / `select` / `list` / `vote` / `stop` ...，见 `/music help`）；
- 搜歌是异步的，插件会等 3.5 秒把延迟消息一起收回来；
- 单玩家 1 秒 1 条、每分钟 30 条。

## 离线玩家

- 用虚拟 `CommandSender`（名字 = 请求里的玩家名）执行，权限按 `offline-permissions` 放行。
- `help / search / list / vote / cancel / mute` 离线可用。
- 点歌（`select` / `music <id>`）仍然要求服务器**至少有一个音乐会话**（AllMusic 客户端
  在线）；没有会话时 AllMusic 会回“没有播放的玩家”，后端会转成 `NO_MUSIC_SESSION`，
  App 提示“服务器当前没有音乐会话”。

## 构建

需要 Java 8 和 BC 的 API jar（默认取本机 HexaCord）：

```powershell
$env:JAVA_HOME="D:/Library/DevelopmentKit/Java/Zulu/zulu8"
./build.ps1
```

产物：`AEMobileMusicBridge.jar`，复制到 BC `plugins/`。
