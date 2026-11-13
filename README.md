# TVKit —— 让老电视也能跑起来的 TV 界面工具包

> 面向 **Android 4.x 老电视 / 电视盒子**（minSdk 14，实测 Android 4.3 / API 18 / armeabi-v7a / 1GB RAM / 1920×1080）的
> 原生 View 界面 + 本地存储 + 手工构建脚本。**没有 Gradle，没有 AppCompat，没有 RecyclerView。**

## 为什么做这个

现在市面上的 TV 播放器开源项目，基本都要 `minSdk 21+`、要用 AppCompat / RecyclerView / AndroidX，
甚至默认走 MediaCodec 的新接口。2013～2015 年那批康佳、创维、小米电视的系统停在 Android 4.2～4.4，
装上直接 `INSTALL_FAILED_OLDER_SDK` 或者运行时各种 `NoClassDefFoundError`。

这个仓库把「在老电视上真的能跑」的那部分经验单独抽出来：

| 老电视上的坑 | 这里的做法 |
| --- | --- |
| 4.3 的 JSSE 默认不发 TLS1.2 ClientHello，HTTPS 图床直接握手失败 | `Ui.CoverLoader` 失败后自动降级重试（放宽 TLS + 宽松信任） |
| 4.x 没有 `ImageView.setImageTintList` | 用 `PorterDuff.SRC_IN` 给单色 png 上色 |
| 512MB～1GB 内存，封面一多就 OOM | LruCache 按 `maxMemory/4` 计量（不是按张数） |
| 焦点缩放会把卡片撑出格子、压到邻卡描边，光标一移整屏「跳」 | 焦点态**只描边、不缩放**；标题固定两行，卡片等高 |
| `org.json` 旧版本没有 `JSONArray.remove(int)` | 自己重建数组，别指望 API 补丁 |
| 没有 Gradle / 老 SDK 装不上新 AGP | 纯 `javac + d8 + aapt2 + apksigner` 的 PowerShell 脚本，一条命令出 APK |

## 目录

```
build.ps1                 一条命令出 APK（javac -> d8 -> aapt2 -> zipalign -> sign）
app/AndroidManifest.xml   minSdk 14 / targetSdk 18，主题走系统 NoTitleBar.Fullscreen
app/src/tv/oldtv/kit/
  Ui.java                 配色/圆角/描边、单色图标上色、封面异步加载（含 TLS 降级）
  Store.java              观看历史/收藏/搜索历史，SharedPreferences 存 JSON
  DemoActivity.java       左栏 + 卡片网格 + 焦点 + 底部小提示 + 历史页 MENU 删除
app/assets/icons/         单色 Material 图标（Apache-2.0，见 NOTICE.md）；仓库里存成 *.png.b64 文本，构建脚本自动还原
```

## 编译

需要 Android SDK（build-tools + `platforms/android-36/android.jar`）和 JDK 17：

```powershell
.\build.ps1 -SdkRoot D:\android-sdk -JdkHome D:\jdk17
# 或者设好 ANDROID_HOME / JAVA_HOME 后直接：
.\build.ps1
```

产物 `TVKit-demo.apk`，可直接 `adb install -r`。改代码后记得提 Manifest 里的 `versionCode`。

> Windows 上如果工程路径含中文，脚本会自动把源码复制到 ASCII 临时目录编译再拷回来
> （aapt2 / apksigner 在中文路径会报 `failed to open directory`）。
> 临时目录里的 keystore 一定要留着，删了重生成会导致覆盖安装签名冲突。

## 可以直接抄走的几个实现

### 1. 焦点态别用缩放

```java
// 错：放大后会溢出格子，压到邻居描边，光标移动时整块画面跳
v.setScaleX(1.03f); v.setScaleY(1.03f);

// 对：只换描边，尺寸不动
v.setBackground(focused
        ? Ui.roundStroke(Ui.SURFACE_HI, 8, Ui.ACCENT, 2, ctx)
        : Ui.round(Ui.SURFACE, 8, ctx));
```

配合 `TextView.setMinLines(2)` 让所有卡片标题固定两行，整排等高，网格就不会上下抖。

### 2. 换集不要 `stop()`，直接换源

ExoPlayer 上换集黑一帧的根因是 `player.stop()` 会把播放器打回 IDLE，Surface 内容被清空。
先试 `setMediaSource`，只有它抛异常时才回退到 stop 流程，老画面会一直留在 Surface 上直到新帧画出：

```java
try {
    player.setMediaSource(newSource);        // 无缝：老画面不动，新帧来了直接顶上
} catch (Throwable needIdle) {
    player.stop();                           // 少数封装/老内核才需要
    player.setMediaSource(newSource);
}
player.prepare();
player.setPlayWhenReady(true);
```

换集提示也只留**底部一条**（`flash("第 N 集")`），别做中间大面板、别加灰屏遮罩。

### 3. 进度条按状态显示

正常播放时隐藏进度条，暂停或菜单打开时才出现：

```java
boolean showBar = !player.getPlayWhenReady() || menuVisible;
bar.setVisibility(showBar ? View.VISIBLE : View.GONE);
```

注意：某些老封装上 `player.isPlaying()` 恒为 false，判断暂停要用 `getPlayWhenReady()`。

## 许可证

代码 MIT（见 [LICENSE](LICENSE)）。图标为 Material Design Icons，Apache-2.0，见 [NOTICE.md](NOTICE.md)。

## 说明

本仓库**只有界面/存储/构建这三块通用能力**，不包含任何第三方内容服务的接口、鉴权、播放内核或品牌素材。
想接自己的片源，改 `DemoActivity.buildItems()` 和 `Ui.CoverLoader` 的取图逻辑即可。
