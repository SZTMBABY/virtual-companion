# live2d.7z 资产审计报告

- 审计对象：`C:\Users\<you>\Desktop\live2d.7z`（11,927,547 B / 11.37 MiB）
- 解压落点：`docs/research/live2d-asset/`（仅此目录，原始 .7z 未改动）
- 解压工具：`C:/Program Files/7-Zip/7z.exe` v26.01 x64（`7z`/`7za` 不在 PATH，`Program Files (x86)` 下无 7-Zip）
- 审计日期：2026-11-14

---

## 一、事实（可直接验证的观测结果）

### 1. 模型数量：**1 个**

解压根目录只有 3 项：`0001.png`、`live_bg_03.jpeg`、`大肥鱼-B站/`。模型目录名唯一：

| 模型目录名 | 说明 |
|---|---|
| `大肥鱼-B站` | 唯一模型，`-B站` 后缀意为来自 Bilibili |

`7z l` 统计：24 个文件 / 3 个目录，解压后总 13,275,155 B（12.66 MiB / 13.28 MB 十进制）。其中模型目录本身 10,881,849 B（10.38 MiB）。

### 2. 目录树（≤3 层，完整 24 文件）

```
live2d-asset/
├── 0001.png                          (2,291,299 B)
├── live_bg_03.jpeg                   (  102,007 B)
└── 大肥鱼-B站/
    ├── 大肥鱼.model3.json             (      361 B)
    ├── 大肥鱼.moc3                    (1,239,040 B)
    ├── 大肥鱼.physics3.json           (   61,061 B)
    ├── 大肥鱼.cdi3.json               (   20,438 B)
    ├── 大肥鱼.vtube.json              (   80,280 B)
    ├── items_pinned_to_model.json     (      456 B)
    ├── EXP/
    │   ├── 01 口型切换.exp3.json      (      212 B)
    │   ├── 02 3嘴.exp3.json           (      212 B)
    │   ├── 02 龇牙.exp3.json          (      213 B)
    │   ├── 03 脸黑.exp3.json          (      212 B)
    │   ├── 04 脸红.exp3.json          (      212 B)
    │   ├── 05 O O.exp3.json           (      212 B)
    │   ├── 05 》《.exp3.json           (      213 B)
    │   ├── 06 X X.exp3.json           (      212 B)
    │   ├── 07 流口水.exp3.json        (      212 B)
    │   ├── 08 O嘴.exp3.json           (      212 B)
    │   ├── 08 担心嘴.exp3.json        (      213 B)
    │   ├── 08 栗子嘴.exp3.json        (      213 B)
    │   ├── 11 尾巴消失.exp3.json      (      212 B)
    │   ├── 12 耳朵消失.exp3.json      (      212 B)
    │   └── 13 水印消除.exp3.json      (      212 B)
    └── 大肥鱼.8192/
        └── texture_00.png             (9,477,029 B)
```

### 3. 格式版本：**Cubism 3+ 家族，具体为 Cubism 5（moc3 文件格式版本 5）**

- 入口文件是 `.model3.json`，**不存在** `.model.json`（Cubism 2.x 入口）。
- `大肥鱼.model3.json` 全文仅 361 B，关键字段：

```json
{
  "Version": 3,
  "FileReferences": {
    "Moc": "大肥鱼.moc3",
    "Textures": ["大肥鱼.8192/texture_00.png"],
    "Physics": "大肥鱼.physics3.json",
    "DisplayInfo": "大肥鱼.cdi3.json"
  },
  "Groups": [
    {"Target": "Parameter", "Name": "EyeBlink", "Ids": []},
    {"Target": "Parameter", "Name": "LipSync",  "Ids": []}
  ]
}
```

注意：`FileReferences` 里**没有 `Motions`、没有 `Expressions`、没有 `Pose`** 三个键；`EyeBlink` / `LipSync` 参数组 `Ids` 都是**空数组**。

- `大肥鱼.moc3` 文件头（offset 0，32 字节）：

```
4d 4f 43 33 05 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
```

  - 魔数 `4D 4F 43 33` = ASCII **"MOC3"**；
  - 第 5 字节（offset 4）= **`0x05`** = moc3 文件格式版本 5；
  - offset 5–63 全 0（标准保留区）；
  - offset 64 起是偏移量表，按**小端**读为 `1984, 2240, 2304, 2584, 4864, 5056, 5248, 5440`，严格单调递增且全部 < 文件长度 1,239,040，结构正常。

### 4. 文件类型逐个统计（唯一模型 `大肥鱼-B站`）

| 类别 | 期望扩展名 | 实际 | 数量 | 总大小 |
|---|---|---|---|---|
| 模型定义 | `.model3.json` | ✅ 有 | 1 | 361 B |
| 二进制模型 | `.moc3` | ✅ 有 | 1 | 1,239,040 B（1.18 MiB） |
| 物理 | `.physics3.json` | ✅ 有 | 1 | 61,061 B |
| 显示信息 | `.cdi3.json` | ✅ 有 | 1 | 20,438 B |
| 表情 | `.exp3.json` | ✅ 有 | 15 | 3,184 B |
| 姿态 | `.pose3.json` | ❌ **无** | 0 | — |
| 动作 | `.motion3.json` | ❌ **无** | 0 | — |
| Cubism 2 模型 | `.moc` | ❌ **无** | 0 | — |
| 贴图 | `.png` | ✅ 有 | 1（模型内） | 9,477,029 B |

**核心缺口：没有任何动作文件（`.motion3.json`）和姿态文件（`.pose3.json`），也没有 `motions` 目录。**

附带的其他文件（非模型运行时必需）：
- `大肥鱼-B站/大肥鱼.vtube.json`（80,280 B）—— VTube Studio 导出的模型配置：`Version 1`，`Name 大肥鱼`，`ModelID 70fd206242c3465a8e2694bff226a903`，`ModelSaveMetadata: VTS 1.35.10 / Steam / 2026-10-03 18:44:53Z / TotalSaveCount 41`。
- `大肥鱼-B站/items_pinned_to_model.json`（456 B）—— VTS 场景挂件元数据，`Items: []` 空。
- 根目录 `0001.png`（2,291,299 B，1296×1728，RGB 无 alpha）—— VTS 模型图标/缩略图（`.vtube.json` 里 `Icon` 指向它）。
- 根目录 `live_bg_03.jpeg`（102,007 B，1792×1344，RGB）—— 背景图，与模型无关。

### 5. 贴图尺寸与体积

| 项目 | 值 |
|---|---|
| 贴图总数 | **1 张**（单图集模型） |
| 文件 | `大肥鱼.8192/texture_00.png` |
| 像素尺寸 | **8192 × 8192** |
| 色彩模式 | RGBA（含 alpha 通道），PNG |
| 文件体积 | 9,477,029 B = **9.04 MiB** |
| 占整包比例 | 9.04 / 12.66 ≈ **71.4%** |
| alpha 统计 | 83.06% 像素 alpha=0（全透明），15.29% 完全不透明 |
| 有效像素包围盒 | (38,16)–(5607,5998) = **5569 × 5982** |

GPU 显存占用（RGBA8888，未压缩纹理）：

| 缩放后尺寸 | 显存 |
|---|---|
| 8192×8192（原图） | **256 MiB** |
| 4096×4096 | 64 MiB |
| 2048×2048 | 16 MiB |
| 1024×1024 | 4 MiB |

目录名 `大肥鱼.8192` 里的 `8192` 即贴图边长，是 Cubism / VTS 作者常用的命名约定。

### 6. `.can3` / PSD / 许可文件：**全部没有**

全量扩展名普查（24 个文件）：`json` ×20、`moc3` ×1、`png` ×2、`jpeg` ×1。

- `.can3`（Cubism Editor 工程源文件）：**不存在**
- `.psd`：**不存在**（`大肥鱼.cdi3.json` 里出现字符串 `Part77 嘴巴.psd`，那只是 Part 的**显示名**，磁盘上没有对应 PSD 文件）
- `LICENSE` / `README` / `*.txt` / `*.md` / 使用条款 / 版权声明：**一个都没有**
- 在 `大肥鱼.vtube.json` 里检索 `版权`、`作者`、`Author`、`Credit`、`License` 关键词：**零命中**

**唯一与授权相关的信号**，来自 `大肥鱼-B站/大肥鱼.vtube.json` 的 `GeneralSettings`：

```json
{"ModelVolume":90,"TimeUntilTrackingLostIdleAnimation":0.0,"WorkshopSharingForbidden":true,"EnableExpressionSaving":true}
```

`"WorkshopSharingForbidden": true` 是 VTube Studio 里作者可勾选的「禁止 Workshop 分享」开关，导出时写进了配置。这是**作者主动声明不得再分发**的痕迹，但**不是**一份可援引的许可条款。

因为包内没有任何许可文本，此处**没有「许可文本关键句」可摘**——这一点本身是结论，不是遗漏。

### 7. 加密 / 加壳特征：**无**

| 检测项 | 结果 |
|---|---|
| `.moc3` 魔数 | `4D4F4333` = `"MOC3"` ✅ 正确（被加密/改写过会不一致） |
| 版本字节 | `0x05`，落在合法枚举内 ✅ |
| offset 5–63 保留区 | 全 0x00 ✅ |
| 偏移量表（offset 64，LE） | `1984 < 2240 < 2304 < 2584 < 4864 < 5056 < 5248 < 5440`，单调递增且 < 文件长度 ✅ |
| 字节熵 | **6.301 bits/byte** —— 明文结构数据（加密/压缩数据通常 > 7.9） |
| 可读 ASCII 串（≥5 字节） | 13,244 段 |
| 可读标识符 | `ParamAngleX`、`ParamMouthOpenY`、`ParamEyeLOpen`、`Part46`、`ArtMesh239_Skinning` 等均可直接 grep 到 |
| 资源统计 | 139 个 ArtMesh id、32 个 Part id、166 个 Param id |

**结论：`.moc3` 是标准未加密的 Cubism 二进制，可被开源/官方解析器读取。**

### 8. 模型规模统计（来自 `.moc3` 与 `.cdi3.json`）

| 指标 | 值 |
|---|---|
| ArtMesh 数 | 139 |
| Part 数 | 32（`.cdi3.json` 记 76 条 Part 记录，含参考/水印节点） |
| Parameter 数 | 166（`.cdi3.json` 记 174） |
| ParameterGroup 数 | 10 |
| 物理设置 | 26 组（PhysicsSettingCount），输入 69，输出 118，顶点 111，FPS 60 |

`大肥鱼.cdi3.json` 的 10 个 ParameterGroup：`按键-表情`、`按键-服装`、`五官组`、`九轴组`、`头发WL`、`头物理`、`衣裙物理`、`饰品物理`、`手臂`、`尾巴`。

`大肥鱼.physics3.json`：`Version: 3`，26 组物理设置，覆盖头发 / 头 / 衣裙 / 饰品 / 手臂 / 尾巴。

值得注意的 Part 名（`cdi3.json`）：`Part46 水印.png`、`Part45 水印2.png`、`PartSketch0 [ 参考图 ]`、`Part10 表情参考(可删除)`、`Part77 嘴巴.psd`。即模型内**残留了作者的水印图层和草图参考层**，并预留了 `Paramcol_3` 参数供「13 水印消除」表情把水印淡出。

### 9. 15 个表情的动作目标（全部是「单参数 Add」开关式表情）

每个 `.exp3.json` 结构一致：`Type: "Live2D Expression"`、`IsModelCustomizationExpression: false`、单条 `Parameters` 项、`Blend: "Add"`。

| 文件名 | 目标参数 | 值 |
|---|---|---|
| 01 口型切换 | PARAM_EXP1 | +1.0 |
| 02 3嘴 | PARAM_EXP2 | +1.0 |
| 02 龇牙 | PARAM_EXP2 | −1.0 |
| 03 脸黑 | PARAM_EXP3 | +1.0 |
| 04 脸红 | PARAM_EXP4 | +1.0 |
| 05 O O | PARAM_EXP5 | +1.0 |
| 05 》《 | PARAM_EXP5 | −1.0 |
| 06 X X | PARAM_EXP6 | +1.0 |
| 07 流口水 | PARAM_EXP7 | +1.0 |
| 08 O嘴 | PARAM_EXP8 | +1.0 |
| 08 担心嘴 | PARAM_EXP7 | −1.0 |
| 08 栗子嘴 | PARAM_EXP8 | −1.0 |
| 11 尾巴消失 | Paramcol_1 | +2.0 |
| 12 耳朵消失 | Paramcol_2 | +1.0 |
| 13 水印消除 | Paramcol_3 | +1.0 |

---

## 二、推断（基于事实的分析结论，非直接观测）

### 1. 版本判定：Cubism 5，而非 Cubism 2

moc3 第 5 字节 = 5，对照 Cubism Core 的 `moc3FileFormatVersion` 枚举：

| 枚举值 | 含义 | 对应编辑器版本 |
|---|---|---|
| 0 | CSM_MOC_VERSION_UNKNOWN | — |
| 1 | CSM_MOC_VERSION_30 | 3.0.00 – 3.2.07 |
| 2 | CSM_MOC_VERSION_33 | 3.3.00 – 3.3.03 |
| 3 | CSM_MOC_VERSION_40 | 4.0.00 – 4.1.05 |
| 4 | CSM_MOC_VERSION_42 | 4.2.00 – 4.2.02 |
| **5** | **CSM_MOC_VERSION_50** | **5.0.00 及以上** |

**推断：该模型由 Cubism 5.x 编辑器（或 VTS 内置的 Cubism 5 Core）导出，需要 Cubism 5 世代的 Core 才能加载。** 用 Cubism 4.x 的 Core 打开会因版本过高被拒绝（典型报错 `Inconsistent MOC3.` / moc version 不匹配）。

另一佐证：`大肥鱼.vtube.json` 记录 VTS 版本 1.35.10、保存时间 2026-10-03，属于较新版本 VTS，其内置 Core 已是 Cubism 5 世代。

### 2. 能否直接跑在 Android 上：**技术可行，但不能「拿包即跑」，需适配**

**可以的部分：**
- 资产本身**未加密、无壳、结构标准**，任何实现了 moc3 v5 的解析器都能读。
- 格式属 Cubism 3+ 家族，走 Cubism 3/4/5 现代管线，**不需要** Cubism 2 兼容层。这与「Cubism 2 需要旧运行时（pixi-live2d-display 的 cubism2 分支）」的情形**无关**——这个包根本不是那种情况。

**必须处理的问题：**
1. **贴图 8192×8192，原尺寸显存 256 MiB**，中低端 Android 机型（尤其 minSdk 30 覆盖的老设备）几乎必然 OOM 或触发纹理上限（GL_MAX_TEXTURE_SIZE 常见 4096/8192）。必须降采样：降到 4096 只需 64 MiB，2048 只需 16 MiB。由于有效像素包围盒只有 5569×5982（约 68% 边长），降到 4096 的视觉损失可控。
2. **没有动作文件**。`model3.json` 无 `Motions` 键，包内无任何 `.motion3.json`。这意味着：**没有待机动画、没有入场动作、没有可播放的 Idle**。运行时只能靠程序驱动参数（眨眼、口型、物理摆动）+ 15 个表情开关。想要「活起来」的观感，只能靠 physics3 的被动摆动 + 代码层参数动画，或者外部补动作文件。
3. **`EyeBlink` / `LipSync` 参数组是空数组**。模型作者没在 `model3.json` 里登记眨眼与口型的参数 id，虽然 `cdi3.json` 里存在 `ParamEyeLOpen` / `ParamEyeROpen` / `ParamMouthOpenY`（说明参数本身在模型里），但运行时必须**自己指定**这些 id 才能做眨眼和口型，不能依赖配置自动发现。
4. **表情需手工挂载**。15 个 `.exp3.json` 放在 `EXP/` 子目录，`model3.json` 的 `FileReferences` 里**没有 `Expressions` 列表**，需在加载端自行枚举目录并注册。
5. **水印层残留**。`Part46 水印.png` / `Part45 水印2.png` 在模型内，默认可见。要么加载时给 `Paramcol_3` 置 1（等价于「13 水印消除」表情），要么接受带水印渲染。**从合规角度，建议保留水印**，见下条。

### 3. 许可结论：**风险高，不可直接商用/再分发**

分层看：

**（a）Live2D Cubism SDK 本身的许可（技术侧）：**
Cubism Core 是闭源专有组件，按 Live2D Proprietary Software License Agreement 分发，只能从官方 SDK 包获取。官方 SDK 许可页关键句原文：

> "License agreement required for business entities publishing a content using Live2D SDK. Individuals and Small-Scale Enterprises are exempted from the license and payment (except Expandable Application)."

> "License only required upon releasing your content. Not during trial or development."

> "when users are ready to release content created using the Cubism SDK, it is necessary to enter into an SDK Release License Agreement (officially known as the Publication License Agreement) and make the required payment. Individuals and small-scale businesses are exempt from the license agreement and payment (excluding Expandable Application)."

> "License agreement must to be completed at least one month prior to the release."

即：**开发/试验阶段不需要许可，正式发布才需要**；个人与小规模事业者免许可免费（"Expandable Application" 除外，指「具有显著可扩展性的服务或内容」，需事先审批并签特殊出版许可）。VTuber 直播业务若年收入超过 2000 万日元需另行签约。

**（b）模型资产本身的 IP（内容侧，风险更高）：**
- 包内**没有任何许可文本**，因此**没有任何授权依据**可以援引。
- `"WorkshopSharingForbidden": true` 是作者在 VTS 里主动勾选的「禁止 Workshop 分享」标记——**推断：作者不希望该模型被再分发**。把它打包进一个 App 分发，方向与之相反。
- 目录名 `大肥鱼-B站` 表明模型来源是 Bilibili（很可能是某位作者的公开作品或素材分享）。**B 站作品默认版权归原作者，不因公开可下载而进入公有领域。**

**综合结论：SDK 层面可控（个人/小规模发布可免许可），但模型资产层面没有任何授权凭据，且存在作者明示的禁止分享标记。用于内部原型/自用 demo 可以；对外发布（尤其带水印消除）风险不可接受，必须联系原作者取得书面授权。** 若只是验证渲染管线，建议用 Live2D 官方示例模型（如 Hiyori / Mao，官方明确允许）替代。

### 4. Android 技术路线推断

结合当前工程（`app/build.gradle.kts`：minSdk 30、Compose、无任何 Live2D/GL 依赖）：

| 路线 | 可行性 | 说明 |
|---|---|---|
| **Live2D Cubism SDK for Native（C++）+ JNI + GLES** | ✅ 最优 | 官方 Android 支持最完整，直接吃 moc3 v5；需接 CMake/NDK，工程改动最大 |
| Live2D Cubism SDK for Java | ✅ 可行 | 纯 JVM，接入简单，性能略逊于 Native |
| WebView + pixi-live2d-display（cubism4 分支） | ⚠️ 有坑 | 其内置 Core 为 Cubism 4 世代，**很可能拒绝 moc3 v5**；需替换为 Cubism 5 的 `live2dcubismcore.js` 才稳。工程改动最小（只加 WebView + assets），适合快速验证 |
| pixi-live2d-display 的 cubism2 分支 | ❌ 不适用 | 本包无 `.moc` / `.model.json`，是 Cubism 5，与 cubism2 分支无关 |

**推荐路径：** 原型阶段先用 WebView + pixi-live2d-display，但必须替换 Core 为 Cubism 5 版本并预先降采样贴图到 2048 或 4096；若要长期产品化，切 Cubism Native SDK。

### 5. 需要用到的适配清单（给实现阶段的输入）

1. 贴图降采样：8192 → 4096（+64 MiB）或 2048（+16 MiB），并同步改 `model3.json` 的 `Textures` 路径。
2. 手工注册表达式：扫 `EXP/*.exp3.json`，填进加载配置（原 `model3.json` 无 `Expressions` 键）。
3. 手工绑定眨眼/口型参数：`ParamEyeLOpen`、`ParamEyeROpen`、`ParamAngleX/Y/Z`、`ParamMouthOpenY`（原 `Groups` 为空）。
4. 无动作文件 → 需代码驱动 idle，或自建 `.motion3.json`。
5. 水印层处理：合规做法是保留；若确需隐藏，置 `Paramcol_3 = 1.0`。
6. 校验 moc3 时注意：Cubism Core 会跑 `csmHasMocConsistency()`，本文件通过魔数与偏移表检查，结构正常，预期校验通过。

### 6. 同工作区旁证

`docs/research/l2dprobe/` 下已存在同一模型的另一份解包副本，并带有降采样测试产物：`tex_1024.png`（409,243 B）、`tex_2048.png`（1,148,017 B）、`tex_4096.png`（3,367,206 B）。**推断：此前已有一次贴图降采样可行性探测**，与本次「8192 过大需降采样」的结论一致，可直接复用这些产物作为渲染输入。

---

## 三、一句话结论

**1 个模型（`大肥鱼-B站`），Cubism 5 格式（`.model3.json` v3 + `.moc3` 文件版本 5），总 12.66 MiB，未加密可解析；但无任何动作文件、无 `.can3`/PSD、无许可文本且带作者「禁止 Workshop 分享」标记——技术上能上 Android（需降采样 8192 贴图 + 换 Cubism 5 Core），法律上不能对外发布。**
