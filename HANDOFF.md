# 任务交接文档（Handoff）

> 写给接手本项目继续开发的 agent。**先读 `android/skill/SKILL.md`**（索引）+ `android/skill/docs/contracts/`（13 条契约：模块边界 / MVI / i18n 规范 / 验证基线）——优先级高于本文档。
> 本文档起笔于 2026-09-04（上游 HEAD = `0b9faa5`，v4.9.6）；2026-10-26 已合并上游 `73c022e` 并复核。

## 一、仓库与环境

- 仓库：`TheSixPasserby/HeartRateMonitor-composeui`（fork 自 `XiaochangXu/HeartRateMonitor-composeui`，MIT，署名已保留原作者）
- 双端：Android（Kotlin + Compose 多模块，16 模块）+ Windows（WinUI 3，本段会话未改逻辑只改了链接/文案）
- **本机没有 Android 构建环境**：全部编译验证走 GitHub Actions（`release-android.yml`，workflow_dispatch 手动触发，输入版本号如 `v4.9.6` → 自动发 Release 传 APK）
- 签名 Secrets 已在 fork 仓库配好（`KEYSTORE_BASE64/KEYSTORE_PASSWORD/KEY_PASSWORD/KEY_ALIAS`），材料来自仓库内 `android/.key/`（项目规范明确允许密钥入库）
- keystore 实为 PKCS12 格式（文件名 .jks 无碍，AGP 自动识别）；密码为仓库内公开的占位密码——**介意签名身份就换自己生成的 keystore**
- 本机 GitHub CLI 路径需自行确认（上一段会话记录为 `C:\Program Files\GitHub CLI\gh.exe`，当前机器上已不可用）；认证令牌可从 Windows 凭据管理器取：
  ```powershell
  $resp = "protocol=https`nhost=github.com`n`n" | git credential fill
  $env:GH_TOKEN = (($resp -split "`n" | Where-Object { $_ -like 'password=*' }) -replace '^password=','')
  ```

## 二、本段会话已完成的工作（5 个提交）

| 提交 | 内容 |
|---|---|
| `7d551ac` | 心率历史导出 CSV：详情页单会话（SAF CreateDocument）、列表多选批量（OpenDocumentTree 每会话一文件）；列 `timestamp_ms,time,elapsed_seconds,heart_rate`，CRLF + Locale.US；`HistoryCsvExporter`（:data:repository）+ 4 单测；**隐藏调试入口：历史页标题「历史记录」长按生成 2 分钟仿真数据**（`HistoryRepository.insertDebugSession`） |
| `b0df5f6` | 关于页迁到 fork：检查更新 Gitee API → GitHub API（跳过 draft）；仓库链接换 fork；维护者卡 TheSixPasserby + 原作者 XiaochangXu 鸣谢卡（点击跳上游）；Windows 端同步 |
| `21eb812` + `3c13347` | 发布 v4.9.4（版本 55→56）+ LICENSE/license.txt/README 双署名 |
| `f2a8a6c` + `8487c7a` | 记录三档模式：`history_recording_mode` = manual（默认）/auto/off，旧布尔键仅迁移读取；连接即建会话仅限 AUTO；首页播放/停止记录胶囊按钮 + 计时器（`RecordControl.kt`）；功能设置页三档选择单 sheet（列表↔性能警告切换）；控制面 `startRecording()/stopRecording()`（BleConnectionManager + Handler + BleService 委托） |
| `0b9faa5` | 手动开始时图表 resetChartSession（0 秒重绘+极值清零）；记录秒级网格前向填充 `gapFillGrid`（1.5s~10s 缺口按秒补前值；>10s 不伪造）；测试数据 15min/2s → 2min/1s；发布 v4.9.6 |

注意：`CHANGELOG` 指 `android/app/src/main/res/raw/changelog.md`（随版本更新）；发新版 = 升 `versionCode/versionName` → push → 跑 workflow_dispatch。

## 二½、跟随上游（2026-10-26）

- 上游已把导航从 Navigation3 单 Activity 迁移为**多 Activity 混合架构**（`Destination.kt` + `ui/page/*Activity` + `BaseComposeActivity`，删掉 `AppNavHost/AppNavKey/NavGuard`），并把 `android/SKILL.md` 拆成 `android/skill/SKILL.md` + `docs/contracts/*`（13 条）。本 fork 已整体接受这套结构。
- 合并基线：`upstream/main` = `73c022e`（上游 versionCode 57 / v4.9.5）。合并提交 `47c46df`，**唯一冲突**是 `android/gradle/libs.versions.toml` 的 `datastore`/`documentfile` 相邻行：取上游 `datastore 1.3.0`，保留 fork 的 `documentfile 1.1.0`（CSV 导出 SAF 依赖）。
- 顺带跟上的上游依赖：kotlin 2.4.20→2.4.21、kotlinxSerialization 1.11.0→1.12.0、gradle-wrapper 9.8.0→9.8.1（datastore 1.3.0）。
- fork 侧改动全部保留：三档记录模式、`RecordControl` + 计时器、`HistoryCsvExporter` + 4 单测、beta 流水线、Gitee→GitHub 更新检查、双署名。CSV 导出是 fork 独立实现（上游同期另写了 `HistoryExporter`，两者不冲突：fork 的 `HistoryExporter.kt` 已被 `HistoryCsvExporter` 取代并删除）。
- 后续再跟上游：`git fetch upstream main && git merge upstream/main`（remote 已配好）。注意上游改动了 `BleConnectionHandler/HeartRateRepository` 等 SSOT 附近代码，**合并后必须重读 `contracts/13-heart-rate-ssot.md` 并做真机 BLE 回归**。

## 三、关键约束与坑（新增代码必须遵守）

1. **SKILL 契约**（`android/skill/docs/contracts/` 13 条）：Entity 不出 data 层；设置读写一律 `SettingsRepository`（新键登记 SettingsKeys + AppSettings.DEFAULTS + from()）；控制面走 `BleConnectionManager` 接口（**BleConnectionHandler 和 BleService 都要实现/委托，漏了 BleService 会编译不过**——本会话 CI 抓到过）；feature VM 一律 MVI 三元组；UiState 集合用 Immutable 系列
2. **i18n**：字符串新增必须在 feature 模块全部 24 个 res 目录同步（键名一致性可用脚本校验，参考 git 历史里的 PowerShell 脚本路径习惯）；数字相关格式化固定 `Locale.US`，字符串占位符用 `%N$s`；新语言目录要加 `app/build.gradle.kts` 的 `localeFilters`
3. **多语言批量写入**：PowerShell 5.1 脚本必须先转 UTF-8 BOM 再执行（`[IO.File]::ReadAllText/WriteAllText`，写回用无 BOM）
4. **CI 单测**：`:data:settings` 的 SettingsRepositoryTest 在 CI 慢盘上有文档化的环境性竞态（写后立读对账），因此 release 流水线**不跑**单测；别在未经用户确认下把它加回去
5. **发布流水线防呆**：缺 `KEYSTORE_BASE64` 会在 1 分钟内明确失败；发版 tag 必须与应用 `versionName` 对应，否则「检查更新」版本比较失真
6. 心率数据流 SSOT = `HeartRateRepository`（断开/蓝牙关/服务重建都要清 `recordingStartTime`）

## 四、待办 / 悬而未决

- **真机回归**：手动记录三档切换、计时器、导出 CSV、空会话丢弃——用户承诺在 v4.9.6 真机验证，未经实测
- **RR interval 落库/导出**（用户表达过兴趣，未拍板）：部分设备心率包带 RR 离散点，现只存整秒 bpm，可做逐拍分析；需要时问用户要不要
- `android/app/src/main/baselineProfiles/baseline-prof.txt` 含已重构函数的过期签名条目，无害；下次跑 `:baselineprofile` 生成会刷新
- 若设备实际是 0.5Hz 广播而用户想要"真·每秒一新值"，客户端无解，需引导换设备/开高频广播模式（已在会话中向用户说明）

## 五、快速验证链路（无本地环境的例行流程）

```powershell
git push origin main
& "C:\Program Files\GitHub CLI\gh.exe" workflow run release-android.yml --repo TheSixPasserby/HeartRateMonitor-composeui -f version=vX.Y.Z
$runs = # gh run list / api 轮询 actions/runs
# 失败时拿日志：gh run view <id> --log-failed | Select-String 'e: |FAILED'
```
