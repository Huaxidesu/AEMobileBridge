# AEMobileBridge

代理侧**通用命令桥**：后端 mod 把「以某个玩家身份执行一条代理命令」的请求发过来，本插件执行并把命令返回的聊天**只回给 App**，不发公屏。

目前 App 用它做 AllMusic 点歌（`/music`），但协议不绑定 music：以后要加别的代理命令（比如其它聊天/活动插件），把命令根加到 `allow-commands` 即可。

另外它也负责给 1.7.10 客户端下发 ServerUtilities 的现代 TAB 页眉页脚（`SU|TabHF` 信道），
玩家进子服时自动重发，客户端无需改配置。详见下文「TAB 样式下发」。

## 部署

1. 把 `AEMobileBridge.jar` 放进 BC 的 `plugins/`；
2. 重启 BungeeCord（或重载插件）；
3. 编辑 `plugins/AEMobileBridge/config.yml`：

```yaml
bind: 127.0.0.1          # BC 和后端同机；分开部署时改 0.0.0.0 并加防火墙
port: 44445
secret: aemobile-bridge  # 必须和后端 AEMobile.cfg 的 [bridge] secret 一致
capture-ms: 800          # 默认收集窗口；搜歌这类异步命令由请求方传 captureMs=3500
allow-commands:
  - music
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
tab:
  enabled: true
  # 玩家进子服后按这些毫秒数逐次下发；FML 握手完成前的自定义负载会被客户端丢掉，所以要重发
  delays-ms: [2500, 5000, 8000, 12000, 18000, 26000]
  # 优先用子服 ServerUtilities 上报的 tab.headerText/footerText（AEMobile 3.5.1+ 会自动上报）
  prefer-server-config: true
  # 下面两项只是兜底/覆盖，内置默认值是中性的；真正的文案放在 SU 配置里
  header: "&b&l{ONLINE} &7位冒险者在线"    # 支持 \n 换行、& 颜色码
  footer: "&7本服: &b{SERVER}"
```

## 协议

TCP 一行一个 JSON：

```json
{"op":"exec","secret":"aemobile-bridge","player":"Steve","command":"music search 晴天","captureMs":3500}
```

返回：

```json
{"ok":true,"found":true,"online":false,"lines":["正在排队搜歌","搜索结果","1->晴天 | ..."]}
```

- `player` 必须是 `[A-Za-z0-9_]{1,16}`；
- `command` 去掉开头的 `/`，根命令必须在 `allow-commands` 里；
- `captureMs` 是命令返回后继续收集聊天的时间（100–10000ms）；
- 命令不存在：`ok:false, found:false, error:"UNKNOWN_COMMAND"`；
- 单玩家 1 秒 1 条、每分钟 30 条。

除 `exec` 外还有几个运维用的 op（同样要带 `secret`）：

| op | 作用 | 返回 |
|---|---|---|
| `tab_source` | **后端上报**：AEMobile 把本服 `serverutilities.cfg` 的 `tab.headerText/footerText` 发上来，带 `port`（本服 `server.properties` 端口）由代理反查子服名，也可直接传 `server` 指定；不带 `header`/`footer` 表示清除该缓存 | `{"ok":true,"server":"mgl","synced":N}` |
| `tab_sync` | 立刻给所有在线玩家重发一次页眉页脚 | `{"ok":true,"synced":N}` |
| `tab_status` | 查看开关、下发档位、当前生效的文案，以及各子服上报的原文 | `{"ok":true,"enabled":true,"delays":[...],"header":"...","footer":"...","hasOverride":false,"serverSources":{...}}` |
| `tab_set` | 临时覆盖文案，可传 `header` / `footer`；**只在内存里**，BC 重启失效，传空串表示清除 | `{"ok":true,"synced":N,"hasOverride":true}` |
| `tab_probe` | 故意发一段坏 JSON，客户端会打印 `Failed to parse SU|TabHF packet`，用来确认信道通不通 | `{"ok":true,"probed":N,"payload":"..."}` |

## TAB 样式下发

**文案只有一个来源：子服 ServerUtilities 的 `tab.headerText` / `tab.footerText`。**

- AEMobile 3.5.1+ 在玩家登录时反射读本服 `ServerUtilitiesConfig.tab.headerText/footerText`，
  通过 `tab_source` 上报给代理；内容没变不重复发。代理按子服缓存，玩家进服时下发。
- **不用在每台后端填子服名**（3.5.2+）：后端上报自己 `server.properties` 的 `server-port`，
  代理在 `servers:` 里按端口反查（如 25588 → `mgl`），命中时日志打
  `tab_source: port 25588 -> backend 'mgl'`。端口对不上才退回手填的 `server`，再不行对所有子服生效。
- 生效优先级：`tab_set` 运行时覆盖 → 子服上报值 → `config.yml` 的 `tab.header/footer` → 内置中性文案。
- 内置默认值刻意保持中性（只有 `{ONLINE}` / `{SERVER}`），仓库和 release 里不写具体服务器的
  群号或口号；要改文案请改 SU 配置，改完下一次有人登录就会上报（或手动发一次 `tab_source`）。
- 插件注册 `SU|TabHF` 信道，向 ServerUtilities 发 `{"header":"...","footer":"..."}`（UTF-8 JSON，
  `\n` 换行、`&` 颜色码由客户端转 `§`），文案里的 `{PLAYER}` / `{SERVER}` / `{ONLINE}` 会替换成当前值。
- `ServerConnectedEvent` 触发时客户端还在跑 FML 握手，握手期间的自定义负载会被丢掉，
  所以按 `delays-ms` 阶梯重发；切服或断线后自动停止，不会往旧连接上发。
- **客户端要求**：GTNH 整合包自带的 ServerUtilities。若 TAB 渲染权被别的模组抢走
  （典型是 `BetterPingDisplay`，以及 `NecroTempus` 的 PlayerTab 模块），需要
  [AEMobile](https://github.com/Huaxidesu/AEMobile) 3.5.0+ 的兜底钩子，或直接移除那些模组。

## 离线玩家

- 用虚拟 `CommandSender`（名字 = 请求里的玩家名）执行，权限按 `offline-permissions` 放行。
- 具体命令的行为由目标插件决定。以 AllMusic 为例：`help/search/list/vote/cancel/mute` 离线可用；
  `select`/`music <id>` 需要服务器**有音乐会话**（AllMusic 客户端在线），否则 AllMusic 自己返回
  “没有播放的玩家”，App 会转成友好提示。

## 构建

需要 Java 8 和 BC 的 API jar（默认取本机 HexaCord）：

```powershell
$env:JAVA_HOME="D:/Library/DevelopmentKit/Java/Zulu/zulu8"
./build.ps1
```

产物：`AEMobileBridge.jar`，复制到 BC `plugins/`。
