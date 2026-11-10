# 衣姿工坊 · 安卓版（PoseWear App）

把桌面版 `C:\Users\excel\WorkBuddy\Claw\PoseWearStudio` 移植到 Android 的原生 App。
手机只负责选图、拼提示词、调 API、保存结果；**模型与推理仍然跑在电脑的 ComfyUI 上**。

工程骨架参考同目录下的 `novel-studio-app`：原生 Kotlin、单 `Activity` + `TabLayout` + 常驻 `Fragment`（hide/show 不销毁视图）、`object Store` 全局状态 + `StateFlow`、原生 `HttpURLConnection`。
本项目在它基础上补齐了参考项目没有的两块能力：**图片/本地目录浏览** 和 **真正的折叠屏宽屏双栏布局**。

---

## 一、与桌面版的功能对照

| 桌面版（PyQt6） | 安卓版 |
| --- | --- |
| 左侧 3 个 tab：人物照片 / 服装 / 姿态 | 3 个 tab：1.人物照片 / 2.服装库 / 3.姿态库 |
| tab 后固定「保持原图背景」 | 4.生成 页顶部的固定开关条 |
| 右侧 tab「生成」 | 4.生成：比例、提示词、结果、开始/中断、查看大图、另存为 |
| 右侧 tab「分辨率与参数」 | **5.模型与参数** TAB（独立页，见下节） |
| 生成参数里的 3 个模型下拉 | 5.模型与参数 页的 基模 / Text Encoder / VAE 下拉，清单与桌面逐字一致 |
| `config.json`（项目内） | **6.设置**（服务地址与素材库目录）+ **5.模型与参数**（其余），都存本机 `SharedPreferences`，启动自动装载 |
| `服装库/`、`姿态库/` 项目内目录 | 手机本地存储目录（手填绝对路径或系统目录选择器），图片不打包进 APK |
| `output/` + `os.startfile` + `另存为` | 应用专属 `Pictures/PoseWear/` + `ACTION_VIEW` 大图 + SAF「另存为」 |
| `comfy_url = http://127.0.0.1:8188` | 6.设置 页填电脑的局域网 IP + 端口 |

### 6 个 TAB（窄屏）

1. **人物照片** —— 从手机相册/文件选择正面照片，统一转存为应用私有目录下的 PNG，下次启动仍在；带成年人肖像免责声明。
2. **服装库** —— 网格浏览手机本地服装图，分类下拉 + 搜索 + 分页（每页 60）；顶部两个**等宽对齐**的互斥按钮「保持原服装」（即不换装）「全裸 · 无服装」。
3. **姿态库** —— 同上，系统 / 分类 / 角度**三级级联筛选** + 搜索；选项「保持原姿势」（即不换姿势）。选择「系统」后「分类」只列出该系统下的分类、「角度」只列出该系统下可用的角度；失效的旧选择自动回到「全部…」。
4. **生成** —— 9 种输出比例、**随素材自动重建**且可临时手改的提示词、保持原图背景、结果预览、固定底栏「开始生成 / 中断当前任务」，以及查看大图、另存为。比例下方备注只显示最终宽高（如 `1280:1920`），与桌面版一致。
5. **模型与参数** —— 见下节，本次新增。
6. **设置** —— 见下节，本次瘦身为只管服务地址与素材库目录。

### 「5.模型与参数」TAB（不常改的配置）

| 分组 | 内容 |
| --- | --- |
| 生成分辨率 | **7 档**下拉：`极限：9.4MP(4K)` / `超清：7.2MP` / `高清：5.3MP` / `标准：2.4MP` / `快速：1.5MP` / `低显存：1MP` / `低显存：0.6MP`；下方显示当前比例对应尺寸 |
| 生成参数 | 采样步数、CFG、种子(0=随机)、参考图边长、生成超时、请求超时 |
| 模型 | 基模（3 项）、Text Encoder（3 项）、VAE（1 项，唯一值）三个下拉；清单外的自定义名字会作为额外项保留 |
| 联动说明 | 页面内直接列出三组推荐组合 |
| 操作 | 「保存模型与参数（下次启动自动装载）」「恢复本页默认（不影响 ComfyUI 地址与素材库目录）」 |

**模型清单**（`Models.kt`，与桌面版 `config.py` 的 `UNET_MODELS / CLIP_MODELS / VAE_MODELS` 逐字一致；下拉显示纯文件名，传给 ComfyUI 时 text encoder 自动带 `qwen_image_2.1\` 子目录前缀）：

| 类别 | 可选值 |
| --- | --- |
| 基模 | `qwen-image-2.1-UC-int8_convrot.safetensors`、`qwen-image-2.1-UC-Q4_K_M.gguf`、`qwen-image-2.1-turbo-Q4_K_M.gguf` |
| Text Encoder | `qwen3-vl-8b-heretic-1.3.0-int8convrot.safetensors`、`qwen3vl_8b_int8_convrot.safetensors`、`qwen3vl_8b_w4a8.safetensors` |
| VAE | `qwen_image_2.1_vae_bf16.safetensors`（唯一值） |

**切换基模自动套用推荐组合**（`Models.DEFAULTS`，与桌面版 `UNET_DEFAULTS` 逐字一致）：

| 基模 | 生成分辨率 | Text Encoder 默认 | 步数 |
| --- | --- | --- | --- |
| `qwen-image-2.1-UC-int8_convrot.safetensors` | `标准：2.4MP` | heretic int8convrot | 25 |
| `qwen-image-2.1-UC-Q4_K_M.gguf` | `低显存：1MP` | `qwen3vl_8b_int8_convrot` | 25 |
| `qwen-image-2.1-turbo-Q4_K_M.gguf` | `低显存：0.6MP` | `qwen3vl_8b_w4a8` | 8 |

> 只在**用户切换基模那一刻**套用一次，之后手工改的 encoder / 步数 / 档位不会被覆盖；清单外的自定义基模不联动。
> 这一点与桌面版略有差异：桌面版在启动时也会按当前基模套用一次（会覆盖 `config.json` 的 `megapixels`）；
> 安卓版为保证「保存本机、下次自动装载」的承诺，启动时不覆盖已保存的值。

`*.gguf` 基模会自动把工作流节点 `1` 从 `UNETLoader`（带 `weight_dtype`）换成插件节点 **`UnetLoaderGGUF`**（只收 `unet_name`），其余 11 个节点完全一致；此时电脑端必须安装 [ComfyUI-GGUF](https://github.com/city96/ComfyUI-GGUF)。

### 「6.设置」TAB

| 分组 | 内容 |
| --- | --- |
| 远程 ComfyUI 服务 | IP 地址 / 端口（也可直接填完整 `http://` URL）、「测试连接」（`GET /system_stats`，显示显卡与可用显存） |
| 手机本地素材库目录 | 服装库目录、姿态库目录（**手填绝对路径** 或点「选…目录」用系统 `ACTION_OPEN_DOCUMENT_TREE` 授权）、「重新扫描素材库」、「存储权限」 |
| 操作 | 「保存设置（下次启动自动装载）」「恢复本页默认（不影响模型与参数）」 |

**自动装载与同步**：`App.onCreate()` 调用 `Settings.load()` 从 `SharedPreferences` 读出全部字段填进 `Store.cfg`；`MainActivity.autoLoad()` 自动扫描已配置的素材库目录，恢复人物照片、选中的服装/姿态、比例、保持背景状态与上一次的生成结果。
两个配置页在**切走时自动保存**、切回来时从 `Store.cfg` 重新填充；每次保存 / 恢复默认 / 基模联动都会 `Store.bumpConfig()`，生成页订阅 `configVersion` 后立即刷新分辨率备注。两页的「恢复默认」都只恢复本页字段，互不干扰。

---

## 二、折叠屏 / 宽屏适配

参考项目只做了 `values-w900dp` / `values-w1240dp` 的尺寸微调，本项目做了实质的布局重构：

- **`layout/activity_main.xml`（折叠态 / 手机）**：顶部 `TabLayout`（可滚动）+ 单内容容器 + 底部状态栏，6 个 tab。
- **`layout-sw600dp/activity_main.xml`（展开态 / 平板）**：**左右双栏**。左栏是 5 个 tab（人物/服装/姿态/模型与参数/设置，`tabMode="scrollable"` 避免长标题被挤压），右栏是**常驻的「生成控制台」**（比例、提示词、结果、开始/中断始终可见）。展开后一边挑素材、一边看提示词和出图，不需要来回切 tab。
- 断点资源：`values-sw600dp`（双栏 + 4 列网格）、`values-sw840dp`（5 列 + 加大间距/字号）、`values-sw1240dp`（6 列，针对 vivo X Fold3 Pro 一类 2480px 展开屏）。
- 网格列数 `R.integer.grid_columns`、比例按钮的 `GridLayout.columnCount`、各处 padding/字号都随断点变化。
- `AndroidManifest` 的 `configChanges="...|screenSize|smallestScreenSize|screenLayout|density"` + Fragment 常驻，**折叠/展开不重启 Activity、不中断生成、不丢输入内容**；edge-to-edge insets 手动处理系统栏与软键盘。

---

## 三、准备材料

### 1. 手机端素材库（服装库、姿态库存在手机本地存储）

把桌面版项目里的两个文件夹整个拷到手机，例如：

```
/storage/emulated/0/PoseWear/服装库/     ← 含 _metadata.csv 与 春装/ 夏装/ … 子目录
/storage/emulated/0/PoseWear/姿态库/     ← 含 _metadata.csv 与 全身单人-标准系/ 等子目录
```

- 推荐在「6.设置」点「选服装库目录 / 选姿态库目录」用系统目录选择器授权（会申请持久化 URI 权限，重启后仍有效）。
- 也可以给 App 开「所有文件访问」后直接填绝对路径。
- `_metadata.csv` 可选但**强烈建议保留**：有它才能读出服装关键词、姿态名与角度（会写进提示词）；没有 CSV 时 App 会退化为按文件名/父目录名浏览。
- 支持 PNG / JPG / WEBP / BMP，递归扫描 3 层目录。

### 2. 电脑端 ComfyUI

```
ComfyUI ≥ v0.37.0（本机 0.42.5）
启动参数： main.py --listen 0.0.0.0 --port 8188
```

手机与电脑必须在同一局域网。**默认 safetensors 基模不需要任何第三方插件**；选 `*.gguf` 基模时需要 [ComfyUI-GGUF](https://github.com/city96/ComfyUI-GGUF)。不需要导入工作流文件——12 个节点由 `Workflow.kt` 每次动态生成（API 格式）。

必需模型（放在电脑 ComfyUI 的 `models/` 下，与桌面版 `config.py` / `config.json` 一致；按所选基模与 encoder 组合备齐即可）：

| 用途 | 文件 | 目录 |
| --- | --- | --- |
| 扩散模型 · 默认 | `qwen-image-2.1-UC-int8_convrot.safetensors` | `models/diffusion_models/` |
| 扩散模型 · GGUF | `qwen-image-2.1-UC-Q4_K_M.gguf`、`qwen-image-2.1-turbo-Q4_K_M.gguf` | `models/diffusion_models/`（需 GGUF 插件） |
| 文本编码器（3 选 1） | `qwen3-vl-8b-heretic-1.3.0-int8convrot.safetensors`、`qwen3vl_8b_int8_convrot.safetensors`、`qwen3vl_8b_w4a8.safetensors` | `models/text_encoders/qwen_image_2.1/` |
| VAE（**必须 2.1 版**） | `qwen_image_2.1_vae_bf16.safetensors` | `models/vae/` |

官方版本见 `https://huggingface.co/Comfy-Org/Qwen-Image-2.1`（镜像 `hf-mirror.com`）。换成官方模型或自定义权重时，在「5.模型与参数」里选即可；清单外的文件名仍可从旧设置里保留，但需在电脑端确实存在。

> 注意：部分社区的 Turbo GGUF 文件头部缺 `general.architecture` 元数据，会让 `UnetLoaderGGUF` 报
> `Unknown model architecture!`。桌面版项目里提供了 `fix_gguf_metadata.py` 做头部修复，详见桌面版 README 第 8.1 节。

### 3. 工作流（与桌面版 `workflow.py.build()` 逐字一致）

```
UNETLoader / UnetLoaderGGUF(1) → QwenImage21Cache(2) ───┐
CLIPLoader(3) ────────────────┐                        │
VAELoader(4) ─────────┬───────┤                        │
LoadImage(10/11/12) ──┤       │                        │
                       ▼       ▼                        ▼
              TextEncodeQwenImage21(20)      KSampler(30) ← EmptyLatentImage(21)
                       │  positive/negative        │
                       └───────────────────────────┘
                                                  ▼
                                    VAEDecode(31) → SaveImage(32)
```

- 参考图顺序固定 **人物 → 服装（可选）→ 姿态（可选）**，只上传实际存在的图。
- `images.image_N` 与提示词里的 `<imageN>` 一一对应；`Prompt.validatePrompt()` 会在提交前拦截越界引用。
- 分辨率公式 `w = a×32×u, h = b×32×u`（严格比例、32 倍数），9 种比例 × **7 档**像素；`低显存：1MP` / `低显存：0.6MP` 在 2:3 下分别是 `832:1248` / `640:960`，面向 6 GB 显存的电脑。
- 显存不足时按 `tierSteps()` 自动降档重试；「中断」会同时置取消标志并 `POST /interrupt`。
- 客户端一律 `Proxy.NO_PROXY` 直连（等价于桌面版 `requests.Session(trust_env=False)`），避免系统代理拦截局域网 HTTP。

---

## 四、构建

与 `novel-studio-app` 一样是标准 Gradle 工程（Groovy DSL），无 gradlew。需要 **JDK 17 + Gradle 8.9 + Android SDK（platform 35 / build-tools 34）**。

**已构建好的安装包**：项目根目录 `PoseWearApp-debug.apk`（约 6 MB，debug 变体，已用 v2 签名，可直接安装；当前版本号见下方「八、版本记录」）。
同内容也在 `app/build/outputs/apk/debug/app-debug.apk`。

```bash
# 安装到手机（手机需开启 USB 调试）
adb install -r PoseWearApp-debug.apk
```

### 重新构建（本机已备好便携工具链）

```bash
cd C:/Users/excel/WorkBuddy/Claw/pose-wear-app

# 每次重新打包前先递增版本号（versionCode +1 / 修订号 +1）
python bump_version.py

export JAVA_HOME='C:\AI\android-toolchain\jdk\jdk-17'
export PATH="/c/AI/android-toolchain/jdk/jdk-17/bin:$PATH"
# 若 Gradle 需要走本机代理才能下载 Maven 依赖：
export GRADLE_OPTS="-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=5065 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=5065"
"/c/AI/android-toolchain/gradle-8.9/bin/gradle.bat" assembleDebug --no-daemon
```

工具链位置：`C:\AI\android-toolchain\`（`jdk/jdk-17`、`gradle-8.9`、`sdk`）。本机原来的 `nas-browser/toolchain` 已不存在，这套是本项目首次构建时下载的便携版，不污染系统环境。

也可以直接用 **Android Studio** 打开本目录（它自带 JDK 与 SDK），`Build → Generate APK` 即可；`local.properties` 会自动重建。

构建验证结果：`BUILD SUCCESSFUL`；APK 内含 `res/layout-sw600dp-v13/activity_main.xml`（宽屏双栏）、`sw600dp / sw840dp / sw1240dp` 尺寸断点、`night` 夜间配色，`apksigner verify` 通过（v2 签名）。

### 源码结构

```
app/src/main/java/com/example/posewear/
  App.kt             启动自动装载设置
  MainActivity.kt    单 Activity + 常驻 Fragment + 折叠屏双栏容器（窄屏 6 tab / 宽屏 5 tab）
  Store.kt           全局状态与 StateFlow（含 configVersion）
  Settings.kt        Cfg 数据类 + SharedPreferences 读写
  Models.kt          ★ 模型清单（3/3/1）+ 三组推荐联动 + GGUF 判定，与桌面 config.py 逐字一致
  Resolution.kt      9 比例 / 7 档位 / 分辨率公式 / w:h 备注
  Util.kt            表单构造器、toast、dp、AbortFlag
  Catalog.kt         本地目录 / SAF 目录树扫描 + _metadata.csv 解析
  Images.kt          路径/content-uri 统一解码、缩略图磁盘缓存、PNG 上传字节
  ThumbAdapter.kt    RecyclerView 图片网格（协程异步加载）
  Prompt.kt          提示词常量、7 种组合、背景替换、引用校验
  Workflow.kt        ComfyUI API 格式 12 节点工作流（按 .gguf 切 loader）
  ComfyClient.kt     7 个 HTTP 接口 + multipart + 轮询 + 中断
  Generator.kt       上传 → 排队 → 轮询 → 取图 → 落盘 → OOM 降档
  PersonFragment.kt / LibraryFragment.kt / GenerateFragment.kt
  ParamsFragment.kt  ★「5.模型与参数」页：三个下拉 + 7 档位 + 联动 + 自动保存
  SettingsFragment.kt「6.设置」页：服务地址与素材库目录

app/src/main/res/layout/
  fragment_params.xml ★ 新页面；fragment_settings.xml 已瘦身；activity_main.xml / layout-sw600dp/…

项目根目录/
  version.properties   APK 版本唯一来源（app/build.gradle 读取）
  bump_version.py      每次打包前递增版本号
```

---

## 五、使用流程

1. 首次启动确认「仅成年人本人/已授权肖像」提示。
2. 进「6.设置」：填电脑局域网 IP 与端口（默认 `192.168.1.100:8188`），点「测试连接」应显示显卡与显存；指定手机上的服装库、姿态库目录，点「重新扫描素材库」看到数量，点「保存设置」。
3. 进「5.模型与参数」：按电脑显存与已下载的模型选基模（切基模会自动带出 text encoder、步数、分辨率档位），按需微调，点保存或直接切走本页。
4. 「1.人物照片」选一张正面照。
5. 「2.服装库」「3.姿态库」至少选一项（或点保持原服装 / 保持原姿势 / 全裸）；姿态页可按「系统 → 分类 → 角度」逐级收窄筛选。「4.生成」里提示词会随每次选择自动重建，也可临时手改。服装页的「全裸 / 保持原服装」与姿态页的「保持原姿势」各自独立，这三个选项也会随设置保存到本机。
6. 选输出比例，需要时勾「保持原图背景」（提示词末尾整句同步替换），点「开始生成」。进度文字显示在页面顶部与全局状态栏，结果保存在手机 `Pictures/PoseWear/`，可「查看大图」或「另存为」到任意目录。

---

## 六、注意事项

- **合规**：仅可用于成年人本人或已获明确授权的照片，禁止上传未成年人照片；首次启动有强制确认。生成结果只保存在手机本地。
- 照片只上传到你自己在设置页填写的 ComfyUI 地址，`AndroidManifest` 开启 `usesCleartextTraffic` 以支持局域网 http 明文。
- 电脑端防火墙需放行 8188 端口；若手机连的是访客网络/AP 隔离，无法访问电脑。
- 高分辨率档位（9.4 MP）在 24GB 显存的电脑上约需 3 分钟，默认 `标准：2.4MP`；生成超时默认 1200 秒。
- **低显存组合**：6 GB 显存的电脑建议 `qwen-image-2.1-turbo-Q4_K_M.gguf` + `qwen3vl_8b_w4a8` + `低显存：0.6MP` + 8 步；更看重画质可选 `qwen-image-2.1-UC-Q4_K_M.gguf` + `qwen3vl_8b_int8_convrot` + `低显存：1MP` + 25 步。
- **半身构图**：姿态库筛选到「上半身单人」系列，或选中该系列的姿态图时，提示词中的「人物完整入画、手清晰可见、不裁切头脚」自动换成「半身取景，头部与上半身完整入画、不裁切头部与肩膀，腿部不必入画」；全裸模式、勾选「保持原图背景」时同样生效（保持背景时仍保留半身约束）。生成页模式标签会显示「· 半身构图」。
- 模型许可：Qwen-Image 2.1 使用 Qwen Research License Agreement，社区量化权重请自行评估合规风险。
- **选项互斥范围**：互斥只发生在同一页内部（服装页：图片 / 保持原服装 / 全裸；姿态页：图片 / 保持原姿势）。「全裸 + 换姿势」是合法组合，切 tab 不会被清掉。
- **版本号**：每次交付新 APK 前必须运行 `python bump_version.py`，`versionCode` 必须严格大于上一版。设置页底部显示当前版本，可装机后核对。

---

## 七、修复记录（2026-11-09 ~ 11-10）

| 问题 | 修复 |
| --- | --- |
| 服装库两个按钮没对齐 | 两个按钮改为对称 `3dp` 外边距的等宽布局，统一 `inset*/minHeight/maxLines/gravity`；文案缩短为「保持原服装 / 全裸 · 无服装」 |
| 手改提示词后再选服装/姿态，提示词不更新 | 初始化 `setText` 不再误置 `promptDirty`；`selection` 版本号真正变化时无条件重建提示词；首次订阅与切 tab 重放不覆盖已有文本 |
| 姿态库三级子类不联动 | 系统 → 分类 → 角度级联：上级变化即重算下级选项并重置失效选择，网格按三级 AND 过滤 |
| 选「上半身单人」系列仍写着「不裁切头脚」 | `Prompt.kt` 把构图句常量化并新增半身版本；`Store.poseSystem` 记录当前筛选系列；`makePrompt / nudePrompt / applyKeepBackground` 全分支覆盖 |
| 服装页选「全裸」后，去姿态库选一张姿态，回来按钮选中态消失 | 互斥只在本页内生效，`toggleNude()` 加页面守卫 |
| 三个选项卡状态重启后丢失 | `Cfg` / `Settings` 新增三个布尔字段并随 `syncToCfg()` 落盘；`App.onCreate()` 自动装载 |
| 每次出包版本号没递增 | 版本改为 `version.properties` 单一来源，新增 `bump_version.py`，设置页显示当前版本 |
| 桌面版新增了模型下拉 / 7 档分辨率 / 联动，安卓版仍旧 | 新建 `Models.kt` + `ParamsFragment` 与「5.模型与参数」TAB；设置页拆成只管服务与目录；`Resolution.kt` 改 7 档新命名；`Workflow.kt` 按 `.gguf` 切 `UnetLoaderGGUF`；`Store.configVersion` 让生成页刷新备注；全局 tab 编号改为 6 个 |
| 出图袖口空荡、缺手 | 双端 `FRAME_FULL` 改为「人物完整入画、手清晰可见、不裁切头脚」；半身与保持背景分支不加强调 |

---

## 八、版本记录与递增规则

版本号唯一来源是项目根目录的 `version.properties`，`app/build.gradle` 读取它写入 APK；
**不要再直接改 `build.gradle` 里的 versionCode / versionName**。每次准备重新打包交付前执行：

```bash
python bump_version.py              # versionCode +1，versionName 修订号 +1（1.0.2 → 1.0.3）
python bump_version.py --minor      # 次版本递增（1.0.3 → 1.1.0）
python bump_version.py --name 2.0.0
```

`versionCode` 是 Android 判断能否覆盖安装的唯一依据，**必须严格递增**；`versionName` 只是给人看的。
设置页最底部会显示已安装 APK 的 `versionName · versionCode`，装好后可据此确认是否装到新版。

| 版本 | versionCode | 日期 | 内容 |
| --- | --- | --- | --- |
| 1.0.0 | 1 | 2026-11-09 ~ 11-10 | 初版；后续的三个 bug 修复、三级联动与「上半身单人」半身构图当时**未递增版本号**，交付的 APK 都仍是 1 |
| 1.0.1 | 2 | 2026-11-10 | 修复「全裸」按钮选中态被姿态页清除；持久化三个选项卡状态；建立版本递增机制并在设置页显示版本 |
| 1.0.2 | 3 | 2026-11-10 | 按用户手工修改后的源码重新打包；未改动源码逻辑 |
| 1.0.3 | 4 | 2026-11-10 | 同步桌面版模型配置 / 生成参数 / 7 档分辨率 / 基模联动并新建「5.模型与参数」TAB；设置页改为「6.设置」；GGUF loader；全身构图提示词加「手清晰可见」 |
