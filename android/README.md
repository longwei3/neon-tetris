# 霓虹方块 · Android 原生版

Kotlin + Jetpack Compose 完全原生实现的俄罗斯方块。游戏内核不依赖任何 Android API，
UI 全部走 Compose Canvas 直绘。

![游戏画面](screenshots/gameplay.png)

## 直接安装

`dist/` 下有两个已签名的 APK：

| 文件 | 包名 | 大小 | 说明 |
|---|---|---|---|
| `NeonTetris-1.0-release.apk` | `com.neon.tetris` | 6.2 MB | 推荐，包名干净 |
| `NeonTetris-1.0-debug.apk` | `com.neon.tetris.debug` | 9.1 MB | 带调试信息，可与上面共存 |

两个包名不同，可以同时安装。都用调试密钥签名，仅供侧载，**不能直接上架应用商店**。

```bash
adb install -r dist/NeonTetris-1.0-release.apk
```

也可以直接把 APK 传到手机上，用文件管理器点击安装（需允许「安装未知来源应用」）。

## 操作

底部两排按钮，每个都带小字标注：

```
┌────────┬────────┬────────┐
│   H    │   ⟲    │   ⟳    │
│  暂存  │  左旋  │  右旋  │
├────────┼────────┼────────┤
│   ←    │   ↓    │   →    │
│  左移  │  软降  │  右移  │
└────────┴────────┴────────┘
```

**硬降没有按钮**，只在棋盘上用「下滑」手势触发 —— 它是一次性动作，放进按键盘里
既容易和「软降」混淆，也白占一个位置。

棋盘上的手势：

| 手势 | 作用 |
|---|---|
| 轻点 | 顺时针旋转 |
| 左右拖动 | 每拖过一格横移一格 |
| 下滑超过 3 格 | 硬降（直接落底并锁定） |

按住 ← → 会先停顿 150ms 再以 38ms 间隔连发（街机标准的 DAS/ARR）。
长按 ↓ 是软降，每格额外 +1 分。

其它：顶栏右侧三个按钮分别是音效开关、震动开关、暂停；返回键在游戏中会先暂停。

## 在 Android Studio 里构建

直接用 Android Studio 打开本目录（`android/`），或命令行：

```bash
./gradlew assembleDebug        # 产物在 app/build/outputs/apk/debug/
./gradlew installDebug
./gradlew testDebugUnitTest    # 跑规则回归测试
```

要求：JDK 17+，Android SDK Platform 34、Build-Tools 34.0.0。
SDK 路径写在 `local.properties`（与本机相关，未纳入版本控制）。

## 架构

```
app/src/main/java/com/neon/tetris/
├── game/
│   ├── Pieces.kt          SRS 方块定义 + 踢墙表（扁平 IntArray，零分配）
│   ├── GameEngine.kt      纯 Kotlin 规则内核，无 Android 依赖
│   └── GamePresenter.kt   帧循环 + 状态同步，纯 Kotlin，可单元测试
├── ui/
│   ├── GameViewModel.kt   把 presenter 接到 Compose，只负责镜像状态与持久化
│   ├── GameScreen.kt      布局、手势、触控按钮、遮罩层
│   ├── Render.kt          DrawScope 直绘：棋盘、方块、HOLD/NEXT 预览
│   └── Theme.kt           配色与 Material3 主题
├── Sfx.kt                 波形合成音效（无音频资源文件）
├── Haptics.kt             震动反馈
└── MainActivity.kt        沉浸式全屏、竖屏锁定
```

### 为什么把帧循环单独抽成 `GamePresenter`

因为这里踩过一个只能在真机上暴露的坑：**⤓ 硬降按钮是在 UI 线程直接调用
`engine.hardDrop()` 的，它可能在两次 `tick` 之间就让引擎进入 GAME_OVER**。
最初 `tick()` 写成「引擎不是 RUNNING 就提前 return」，于是这次状态变化永远同步不出去——
游戏结束遮罩不出现、画面永久冻结、分数停在旧值。

现在 `GamePresenter.tick()` **每一帧都无条件同步状态**，并且它是纯 Kotlin 的，
所以这条路径有单元测试覆盖（`GamePresenterTest`）。

### 其它实现取舍

- **重绘不等于重组**：`GameViewModel.frame` 每帧自增，Canvas 在**绘制阶段**读它。
  整局游戏 60fps 刷新不触发任何 Compose 重组。分数等文本状态只在值真变化时才写入。
- **音效零资源**：启动时用方波/三角波/锯齿波合成 PCM 写入静态 `AudioTrack`，
  APK 里没有任何 wav/ogg。任何一步失败都静默降级为无声。
- **自动连发放在引擎里**：按键只上报「按下/松开」两个边沿，DAS/ARR 由
  `GameEngine.update()` 按毫秒推进，长时间按住不会产生协程风暴。
- **手势判定不看速度**：下滑硬降只看拖动距离（>3 格）。实测加时间窗会导致
  手势时灵时不灵。
- **测试钩子**：`placeCurrent` / `markLastActionRotate` / `setCell` 供单元测试搭场景。

## 测试

`./gradlew testDebugUnitTest` —— 共 **31 个用例**，全部在 JVM 上以毫秒级跑完。

`GameEngineTest`（22 个）：7-bag 随机器、重力下落、硬降落点、幽灵方块、
SRS 踢墙、四个旋转态不越界、消行精确计分、连击、每十行升级、Tetris 计数、
T-spin 三角规则（正例与反例）、Hold 交换与限制、锁定延迟边界、触底微调、
暂停冻结、出生点被占判负、事件产出。

`GamePresenterTest`（9 个）：硬降/暂存在 tick 之外结束游戏时状态必须同步、
结束只播报一次、状态切换触发重绘、暂停后不空转重绘、重开后能再次播报。

## 真机验证记录

在 Xiaomi M2012K11C（Android 14 / arm64-v8a）上通过 adb 实测：

- 安装、启动、冷启动无崩溃；日志无 `FATAL` / `AndroidRuntime` 异常
- 分数结算、最高分持久化（SharedPreferences）跨重启有效
- 轻点旋转、左右拖动横移、下滑硬降、长按连发（DAS）均用截图 + 像素分析逐项验证
- 游戏结束遮罩能自动出现，显示「新纪录！」或「最高分 N」

## 已知限制

- 未开启 R8 混淆（收益小，且能避免任何环境下的类裁剪问题）；需要时把 `isMinifyEnabled` 改为 `true`
- `release` 复用调试签名。要上架请生成自己的 keystore 并替换 `signingConfigs`
- 构建环境无法写入家目录时，可用 `-PdebugKeystore=<path>` 指定调试签名文件；
  正常开发机上无需该参数
