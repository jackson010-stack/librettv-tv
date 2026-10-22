# LibreTV 原生电视客户端（librettv-tv）

给国产电视（红米 / 小米 / TCL 等 Android TV）用的 **LibreTV 影视聚合客户端**，纯原生 Android 实现，
**不包含任何 WebView**，因此不受电视自带浏览器的内核版本限制。

- 应用包名：`com.gathin.librettv`
- 最低系统：Android 8.0（API 26），目标 API 34
- 播放内核：AndroidX Media3 1.3.1（ExoPlayer + HLS）
- 授权：MIT

---

## 为什么不用上游的 LibreTV APK

上游的 [KeyRotate/LibreTV-App](https://github.com/KeyRotate/LibreTV-App) 是 Tauri 打包的，
在红米电视 ASTP0（Android 9）上**永远卡在 Logo 启动页**：

- 电视自带的 WebView 是 **Chromium 66**，且是 `/system/app` 下的不可更新组件；
- Tauri 2 注入的 IPC 脚本用了 `Object.fromEntries`（Chrome 73+）和可选链 `?.`（Chrome 80+），
  初始化脚本直接抛异常，`window.__TAURI__` 根本不存在；
- 前端代码被编译进 `libapp_lib.so`，**没法就地打补丁**；
- 想换 WebView 也不行：电视是 32 位 ARM（`armeabi-v7a`），而 Chrome 在 93 之后就不再支持 32 位 ARM，
  系统 WebView 也没有应用商店可更新。

所以干脆重写一个原生客户端：直接打苹果 CMS（Mac CMS）采集接口 + Media3 播放，全程不碰 WebView。

## 功能

| 功能 | 说明 |
| --- | --- |
| 合并片库 | 并发向全部启用的采集源发请求，结果去重后铺成海报墙；单源失败不影响整体 |
| **单源浏览** | 点左侧采集源的**名称**，右侧只列这个源的片库，带分页（顶栏「更多」按钮） |
| **历史播放（续播）** | 播放进度自动存档，点「继续观看」里的片子直接从上次的位置接着放 |
| **影视分类检索** | 左侧新增「影视分类」入口，按「分类 → 子类 → 片单」逐层下钻，支持分页 |
| 启用/停用 | 采集源行右侧的「开/关」单独控制该源是否参与合并搜索，与浏览动作互不干扰 |
| 搜索 | 顶栏输入片名（例如「庆余年」），同样按当前模式决定是合并搜索还是单源搜索 |
| 详情页 | 剧集列表 + 多线路切换，页内有「继续播放」按钮 |
| 播放 | Media3 拉 HLS 直链播放，自带播放器界面 |
| 结果缓存 | 每个「关键词 × 采集源」的结果都缓存，来回切换秒开 |

## 两个新功能

### 一、历史播放：点一下接着看

左侧采集源列表顶部新增了 **「继续观看」**（▶）一行。

- 播放时每 5 秒记一次进度，退到后台、退出播放器、按返回键时也会再记一次；
- 记的内容包括：片名、海报、来源站、线路、剧集、**播放地址**、**分享页地址**、已播时长、总时长；
- 只有播过 5 秒以上的片子才会进历史，最多留 50 条，新的在前；
- 点历史卡片 → **直接从上次的位置续播**，播放器顶部会提示「从 02:29 继续播放」；
- 看完一整集标记为「已看完」，之后点它会从头开始，而不是跳到结尾；
- 海报墙上也会给看过的片子加角标：`▶ 续播`（还没看完）或 `▶ 重看`（看完了）；
- 详情页里同样有「继续播放」按钮，从详情页进去也是接着播；
- 历史面板顶栏有「清空记录」。

进度存在应用本地的 `SharedPreferences`（`librettv.xml` 的 `history` 键）里，不联网、不上传。

### 二、影视分类：按分类逐层找片

左侧采集源列表顶部新增了 **「影视分类」**（▦）一行。

- 进入后自动挑一个**能用的**采集源（默认按 360资源 → 天涯资源 … 的顺序探测，拿到第一个有分类的就用它）；
- 右侧列出这个源的分类卡片，点一个分类：
  - 如果它**有子类**（例如「电影」→ 动作片 / 喜剧片 / 科幻片），先展开子类；
  - 如果它**没有子类**，直接列出该分类下的片子，并显示 `已显示 20 条 · 共 5059 条 · 第 1/253 页`；
- 点分类里的片子照常进详情页播放，播放完也会进「继续观看」；
- 分类页和片单页都支持「更多」翻页；
- 按返回键会**一级一级退回**：片单 → 子类 → 分类列表 → 首页。

#### 扁平分类源的层级是自动分析出来的

有的采集站（天涯资源、百度云资源、如意资源等）返回的分类列表是**扁平的**，
接口里既没有 `type_pid`，直接点「电影」这种分组也是空的——因为分组只是目录，真正有内容的是它下面的子类。

这类站的分组关系藏在片子里：每条片子都带一个 `type_id_1`，指回它所属的分组。
所以 App 会**对每个分类各发一次探测请求**（并发 4 路），用返回的第一条片子的 `type_id_1` 反推分组，
把扁平列表还原成真正的两级目录。实测 39 个分类约 3 秒完成。

分析结果会缓存到本地（`librettv.xml` 的 `category_tree_<源>` 键），**同一个源只分析一次**，以后秒开。
分析失败也不影响使用：叶子分类本来就有内容，照样能直接看片。

> 参考：天涯资源还原后是 电影(9 个子类) / 电视剧(8) / 综艺(4) / 动漫(4) / 短剧(7)。

### 采集源清单

共 **29 个**（21 个普通源 + 8 个成人源），全部默认启用，接口为公开的苹果 CMS 协议：

```
电影天堂资源 如意资源 暴风资源 天涯资源 小猫咪资源 非凡影视 黑木耳 360资源
卧龙资源 华为吧资源 极速资源 豆瓣资源 魔爪资源 魔都资源 最大资源 樱花资源
百度云资源 无尽资源 旺旺短剧 iKun资源 空内容测试源
CK资源 jkun资源 百万资源 souav资源 155资源 lsb资源 黄色仓库 玉兔资源
```

> 成人源在左侧列表中单独分组，标题为「成人站（上游默认关闭）」。

## 构建与安装

### 环境要求

- JDK 17（`/usr/libexec/java_home -v 17`，或 Homebrew 的 `openjdk@17`）
- Android SDK（`ANDROID_HOME` 或本机 `~/Library/Android/sdk`）

### 编译

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools

# 本仓库以纯文本分发，gradle-wrapper.jar 需要先还原一次
bash tools/restore-wrapper.sh

bash gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

> 仓库里的脚本没有可执行位（发布渠道只保留文件内容，不保留权限），
> 所以一律用 `bash gradlew`、`bash tools/restore-wrapper.sh` 调用；
> 想直接 `./gradlew` 的话，先 `chmod +x gradlew tools/restore-wrapper.sh`。

`tools/restore-wrapper.sh` 会把仓库里的 `gradle/wrapper/gradle-wrapper.jar.b64` 解码回真正的
`gradle-wrapper.jar`（GitHub 连接器只能写文本，二进制文件没法直接提交，所以用 base64 存了一份）。
如果你本地已经装好 Gradle 8.9，也可以直接 `gradle wrapper --gradle-version 8.9` 重新生成。

### 装到电视

```bash
adb connect <电视IP>:5555          # 电视需先在「设置 → 账号与安全 → ADB调试」里打开
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> 电视每次重启都会把 5555 端口关掉，必须用遥控器重新打开 ADB。
> 家里同时装了 Homebrew 版 adb 和仓库自带的 Python 客户端时，先 `adb kill-server` 再用 `tools/tv.py`。

## 遥控器怎么用

界面是「左栏采集源 + 右侧片库」两栏布局，焦点走位：

```
        搜索框 ──→ 搜索按钮 ──→ 更多(分页)
          ↓                              ↓
   采集源列表 ←── 左键 ── 海报卡片 ←───────┘
```

| 按键 | 效果 |
| --- | --- |
| 采集源行上按「确认」 | 右侧切到该源片库（单源浏览） |
| 采集源行上按「右键」 | 跳到该行的「开/关」开关 |
| 第一行「全部采集站（合并）」 | 回到合并片库 |
| 第二行「继续观看」 | 右侧切到播放历史，点卡片直接续播 |
| 第三行「影视分类」 | 右侧切到分类浏览，点分类逐层下钻 |
| 海报卡片按「上」 | 跳到顶栏「更多」按钮（单源浏览时） |
| 海报卡片按「左」 | 回到采集源列表的第一行 |
| 顶栏「更多」 | 该源翻下一页 |
| 分类/片单页按「返回」 | 退回上一级（片单 → 子类 → 分类 → 首页） |

## 已知限制

**大约一半的采集源在电视上连不上。** 这不是 App 的问题：这些源的 CDN（`23.225.x.x` 等）
在电视所处的直连网络路径上握手会被重置，而同一批 URL 在 Mac 上必须走 Clash 才能访问。
界面上会明确区分三种「空」：

- `该源没有内容` —— 源能连上，但确实没有结果；
- `连不上（网络被阻断或站点已下线）` —— 源不可达；
- 合并视图顶栏显示 `N/29 个源可用 · M 个源连不上`。

想让这些源也可用，有两条路：

1. Clash Verge 开「允许局域网连接」，电视设全局代理指向 `Mac:7897`；
2. 在软路由上装 OpenClash，全家流量统一走代理（更稳，电视不用改设置）。

另外，部分源的播放 CDN 会对本机所在地区返回 `403 The region has been denied`，属于源站方的地域限制。

## 目录结构

```
app/src/main/java/com/gathin/librettv/
  MainActivity.java     首页：采集源列表 + 合并/单源两种片库模式 + 分类浏览 + 播放历史 + 焦点管理
  DetailActivity.java   详情页：剧集与线路、继续播放
  PlayerActivity.java   Media3 播放，进度存档与续播
  History.java          播放历史（进度、片名、线路、地址）的本地存储
  Api.java              苹果 CMS 接口：合并搜索、单源分页、分类列表与分类树解析
  Sources.java          29 个采集源的地址与分页参数
  ImageLoader.java      海报图异步加载（磁盘 + 内存缓存）
  Prefs.java            采集源启用状态、分类树缓存的本地存储
```

## 致谢

- 苹果 CMS（Mac CMS）公开采集接口协议
- [KeyRotate/LibreTV-App](https://github.com/KeyRotate/LibreTV-App) —— 采集源清单的来源
- [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv) —— 电视端部署与裁剪方案

## 许可证

MIT，见 [LICENSE](LICENSE)。仓库里的采集站地址均为第三方公开接口，本项目只做客户端聚合，不分发任何影视内容。
