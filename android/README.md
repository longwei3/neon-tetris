# 霓虹方块 · Android 原生版

Kotlin + Jetpack Compose 完全原生实现的俄罗斯方块。游戏内核不依赖任何 Android API，
UI 全部走 Compose Canvas 直绘。

![游戏画面](screenshots/gameplay.png)

> **棋盘是 12 列 × 20 行**（标准俄罗斯方块为 10 列）。
> 竖屏手机上 10 列只能占到屏幕宽度的 75%，两侧各空出 1.7 格；12 列正好填满（90%）。
> 单格尺寸不变（由棋盘高度决定），纯粹是多了两列。
> 代价是每行要多凑两格才消，整体难度低于标准规则。

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
app/src/main/java/com/neon/tetris/├── game/
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

## 音频

**APK 里没有任何音频资源文件**，音效和音乐都是运行时合成 PCM。

### 音效

9 个一次性音效（[`Sfx.kt`](app/src/main/java/com/neon/tetris/Sfx.kt)），用方波/三角波/锯齿波加指数衰减
包络合成，写进静态 `AudioTrack`。移动音效做了 45ms 节流，否则连发时会糊成一片。

### 背景音乐

一首原创的**舒缓合成器氛围乐**（[`BgmSynth.kt`](app/src/main/java/com/neon/tetris/BgmSynth.kt)），
Am7 - Fmaj7 - Cmaj7 - Em7 四小节循环，**完全没有鼓组**。三层织体：

- **铺底和弦**：正弦波，慢起音慢收尾，跨小节持续 1.4 小节 —— 与下一小节的和弦交叠，
  和弦切换是渐变而不是断点；
- **低音**：正弦波长音；
- **旋律**：三角波加缓衰减，每小节只有两三个音，留白很足。

针对「循环到腻」做了一件事：**速度与编曲随等级变化**（70 BPM 起，每级 +3，封顶 110；
4 级进对位旋律，8 级加一层高频泛音）。强度不高，是刻意的 —— 舒缓的曲子不适合越玩越吵。

#### 实现要点

早先的版本是「每个音符都在一步内衰减完」的打击乐写法，做不出舒缓的效果——舒缓的核心
就是长音。所以合成器改成了**跨步的声音模型**：每个声音记录自己的起始采样与总时长，
渲染某一步时只画它与这一步重叠的部分，因此一个音可以持续任意长。

另外长音会拖过循环终点，如果不处理，每次循环回头时尾巴会被切掉。构建声音表时会把这类
声音按循环长度平移一份到开头，保证接缝无缝。

播放用流式 `AudioTrack`（而不是静态缓冲），这样每一步都能按当前等级重新计算八分音符时长。
游戏暂停或结束时音乐停止，免得盖过结束音效。

真机实测的音色对比（同一分析脚本）：

| | 频谱重心 | 2kHz 以上能量 | 静音空隙 |
|---|---|---|---|
| 旧版（鼓组 synthwave） | 4547 Hz | 42.9% | 14.3% |
| 当前（舒缓氛围乐） | **721 Hz** | **4.1%** | **1.6%** |

频谱重心降了 6 倍多、高频能量降到十分之一，这是「听起来舒缓」最直接的客观指标。

### 试听与测试

作曲和合成逻辑被拆成不依赖 Android 的 `BgmSynth`，所以可以在 JVM 上直接渲染：

```bash
./gradlew testDebugUnitTest --tests 'com.neon.tetris.BgmSynthTest'
# 会导出 app/build/bgm-preview.wav —— 同一首曲子在 1 / 4 / 9 级各放一圈
```

测试会断言电平合理（不是静音、不削波、无直流偏置）、分层确实生效、同种子可复现。

## 真机验证记录

在 Xiaomi M2012K11C（Android 14 / arm64-v8a）上通过 adb 实测：

- 安装、启动、冷启动无崩溃；日志无 `FATAL` / `AndroidRuntime` 异常
- 分数结算、最高分持久化（SharedPreferences）跨重启有效
- 轻点旋转、左右拖动横移、下滑硬降、长按连发（DAS）均用截图 + 像素分析逐项验证
- 游戏结束遮罩能自动出现，显示「新纪录！」或「最高分 N」
- 背景音乐经过系统层确认：`dumpsys audio` 中本应用有 `content=CONTENT_TYPE_MUSIC`、
  `state:started`、`sampleRate=44100`、单声道的活跃音轨；暂停 → 停止、恢复 → 重新播放的循环
  连续验证三轮均正确

## 已知限制

- 未开启 R8 混淆（收益小，且能避免任何环境下的类裁剪问题）；需要时把 `isMinifyEnabled` 改为 `true`
- `release` 复用调试签名。要上架请生成自己的 keystore 并替换 `signingConfigs`
- 构建环境无法写入家目录时，可用 `-PdebugKeystore=<path>` 指定调试签名文件；
  正常开发机上无需该参数
