# 体测助手（Android）

面向中小学各年级体育教师的单机体测数据录入 App：导入全校 Excel 名单 → 选班后按项目把 1～6 名同学编成一组、整组点名录入并实时保存 → 按已测班级导出**格式与原始表完全一致**的 Excel → 手机开启局域网 HTTP 服务，电脑同网只读下载完整备份包，App 内可导入恢复。

原生 Android + Kotlin + Jetpack Compose + Room，**全部通过 Gradle 命令行构建**，不需要 Android Studio。

## 一、环境准备（一次即可）

本机需 PowerShell。AGP 8.5.2 / Gradle 8.7 要求 JDK 17（本机自带的 JDK 25 不可用），且需要 Android SDK。脚本会把工具全部装到工作区 `.tools/`，**不修改系统环境变量、不安装 Android Studio**：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\setup-env.ps1
```

脚本会：下载 Temurin JDK 17 与 Android `cmdline-tools` → `sdkmanager` 安装 `platform-tools`、`platforms;android-35`、`build-tools;35.0.0` → 下载 Gradle 8.7 发行版并生成 `gradle-wrapper.jar` → 写入 `local.properties` 与 `gradle.properties` 中的 `org.gradle.java.home`。

> 下载约 600MB，首次执行需要几分钟；已下载的文件会自动跳过，可安全重跑。国内网络若 Adoptium 超时，脚本会自动切换华为镜像。

## 二、命令行构建与安装

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build-apk.ps1
powershell -ExecutionPolicy Bypass -File scripts\build-apk.ps1 -Install
```

- 产物：`app\build\outputs\apk\debug\app-debug.apk`
- `build-apk.ps1` 同时执行 `testDebugUnitTest`，其中 `XlsxCodecTest` 会在 `app\build\` 生成 `export-preview.xlsx`，用于校验导出保真度。
- 也可手动运行（`build-apk.ps1` 已自动设置这些环境变量）：
  ```powershell
  $env:JAVA_HOME = "$PWD\.tools\jdk17"
  $env:GRADLE_USER_HOME = "$PWD\.tools\gradle-home"
  .\gradlew.bat assembleDebug testDebugUnitTest --no-daemon --console=plain
  .\.tools\android-sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
  ```
  `setup-env.ps1` 会把 Gradle 8.7 发行版预置到工作区缓存，因此 Wrapper 无需联网下载发行版（部分网络下 JDK 对发行版 CDN 的证书链校验会失败）。Maven/Google 依赖仓库仍由 Gradle 正常在线拉取。

## 三、教师使用流程

1. **首页「数据」**：点「本地 Excel 导入」用系统文件选择器选 `.xlsx`；或填入 `http(s)://...` 表格地址点「下载并导入」。首次体验可点「载入内置示例表」。
2. 「选班」页把班级按年级分组，点小按钮单选（班级名称里的「2026级」会按当前学年换算成「1年级」，如 2026 级在 2026-2027 学年显示为 1 年级，跨学年自动顺延；原表数据与导出内容不变）。选好后点「进入测试」。
3. 「测试」页显示当前班级每个项目的测试情况：已测人数 / 班级人数、进度条与状态（未开始/进行中/已完成）。默认只列本年级的默认项目，需要时可打开「显示全部项目」。
4. 点某个项目先看**待测名单**（只列还没测的学生）。体育课无纸化，先选「每组叫出几名同学」：候选 1 / 2 / 3 / 4 / 6，另有 −、+ 微调，最多 6 人；选择会记住，下次进入仍是这个人数，**默认 1 人**保持原来的单人录入体验。待测名单页会把第一组姓名放大成「请先叫出这一组」点名条，并在名单网格里高亮，方便老师先把这几名同学单独叫出队伍。
5. 点「开始测试」后，一屏列出本组每位同学的姓名卡片与各自成绩输入框，**谁测完就填谁**，不要求按组内顺序；顶部点名条可点姓名定位到对应卡片，卡片显示「已填 / 未填」。底部统一「保存本组并下一组」，另有「上一组」「清空本组」（二次确认）与退出保存。越界成绩会汇总列出「某同学 某项目」后一次确认。每次输入即时落库；**留空即视为未测**：本项目仍然未完成，该生下次还会出现在待测名单里；身高/体重需要两项都填写才算已测。本轮录完会显示完成情况，可「继续测试未测学生」或返回项目列表。项目之间互不影响，可以一节课只测一个项目、一个班的一部分人。
6. 「中心」页勾选本次上课测过的班级 → 「导出 Excel」→ 选择保存位置。导出在后台线程完成，界面显示「导出中…」；导出表以原始 xlsx 为底稿，仅保留所选班级行并回填成绩，其余列、表头批注、单元格格式、列宽全部原样保留；未测项目留空。导出完成后可「分享最近导出的表」。
7. 需要备份时点「生成备份包」，再开启「局域网备份服务」；电脑连同一 WiFi，浏览器打开 App 显示的地址并输入 4 位访问码，即可查看并下载备份包。换机或误删时，在 App 内「导入备份包恢复」选择该 zip 一键恢复（恢复前自动留回滚快照）。

## 四、模板保真导出的原理与校验

原始 xlsx 的字节被当作不可变模板存入 App 私有目录。导出时逐个 zip 部件**字节级原样复制**，只重写 `xl/worksheets/sheet1.xml` 的 `<sheetData>`：保留表头行、过滤并重编号学生行、追加成绩单元格，同时更新 `dimension`。表头批注（`xl/comments1.xml`）、VML 锚点、样式、sharedStrings、列宽、其它信息列都不改动。

导出全程放在 `Dispatchers.IO`，不占用主线程。行列映射（`学籍号 → 原始行号`、`项目名 → 列号`）直接取导入时已落库的 Room 数据，不再二次解析整张表；除工作表外的 zip 部件从模板直接流式复制。

工作表改写是**真流式**：按 8KB 字符块扫描，任何时刻只物化「很小的前缀」「当前一行」与「后缀片段」，逐行重编号、回填成绩后立即写进输出 zip；不再把整张工作表读成 `String`、不再拼完整新表字符串，生产路径的 `ExportResult.sheetXml` 恒为空串（只有供 JVM 单测与离线校验用的兼容重载会用 `StringWriter` 捕获完整内容）。`dimension` 依赖最终行号，因此在可重复打开的同一 zip 输入流上做两遍轻量顺序扫描：第一遍只统计命中行数，第二遍才写出。峰值内存与工作表体积解耦。

体积预算按设备自适应：工作表默认上限 8MB，导出前再用 `ActivityManager.memoryClass` 与 `getMemoryInfo().availMem` 取「上限 / heap 的 1/8 / 可用内存的 1/8」最小值（保底 2MB）下传；打开工作表前先按声明大小拒绝，读取时再累计实际字节，做到「零大分配即失败」。模板压缩后仍限 24MB。生成文件、写到用户选定位置、生成分享 URI 三步分离：分享 URI 失败不会把已成功的导出判为失败，生成中断会删除半成品文件。导出开始/结束用 `TiceExport` tag 记录模板体积、工作表声明体积、预算、产出体积与耗时，不含姓名或学籍号，便于在模拟器上对照 `am_pss` / `dumpsys meminfo`。

解析端同样是流式的：用 `XmlPullParser` 顺序读取 `sharedStrings.xml`、`comments1.xml` 与 sheet 的 `sheetData`，不构建 DOM，内存只与学生行数线性相关。实测 123 KB、解压后 515 KB 工作表、1516 名学生的上报表可正常导入（此前 DOM 解析会因内存被打满被系统杀进程）。导入与备份恢复读取 zip 时也按分块累计解压体积，超过 128MB 提前报错，避免 `readBytes()` 扩容时的瞬时双倍内存。

- 数值成绩写 `<c r="J2"><v>165.5</v></c>`
- `50米×8往返跑` 这类 `2′20″` 文本成绩写 `t="inlineStr"`，因此不改 `sharedStrings.xml`
- 已存在的成绩单元格沿用原样式 `s=...`

校验命令（Python 3 + `openpyxl`，只读不改写）：

```powershell
python scripts\verify_export.py baseline data-example.xlsx
python scripts\verify_export.py verify app\build\export-preview.xlsx data-example.xlsx --expect-ids DEMO-STU-0001
```

基线结论：示例表共 13 个 OOXML 部件；表头批注全部位于第 1 行（A1–Q1）、VML 锚点也在第 1 行，因此行过滤与重编号不会影响批注。项目规则示例：肺活量 500~9999、50米跑 5.0~20.0 可小数、坐位体前屈 -30~40 可小数、一分钟跳绳 0~300 整数、一分钟仰卧起坐 0~99 整数。

## 五、备份服务说明

- 端口从 `8080` 起自动探测到 `8092`；前台服务通知显示手机 IP、端口与 4 位访问码。
- 网页为**只读**：`GET /` 快照列表、`GET /backup/latest` 下载、`GET /health` 健康检查；不支持 POST/PUT，浏览器不能上传或改数据。
- 备份包内容为 `meta.json`（版本、条目数、SHA-256 校验和）、`data.json`（学生/成绩/项目规则/元数据）、`template.xlsx`（原始模板字节）。恢复时先校验版本与校验和，再在单个 Room 事务中覆盖，失败自动回滚。
- 数据包含学生姓名、学籍号、家庭住址等敏感信息：仅在可信校内 WiFi 开启，用完关闭服务，备份文件请妥善保管。
- `scripts\backup-page-preview.html` 是备份网页的静态副本，可用浏览器直接预览视觉与只读结构。

## 六、常见问题

| 现象 | 处理 |
| --- | --- |
| Gradle 报 `Unsupported class file major version` / JDK 不兼容 | 先跑 `setup-env.ps1`，确认 `gradle.properties` 中 `org.gradle.java.home` 指向 `.tools/jdk17` |
| `SDK location not found` | 确认 `local.properties` 存在且 `sdk.dir` 指向 `.tools/android-sdk` |
| 下载 Gradle 发行版报 `PKIX path building failed` | 环境脚本已把发行版预置到 `.tools/gradle-home` 缓存；用 `build-apk.ps1` 构建，或手动设置 `GRADLE_USER_HOME` 指向该目录 |
| 导入提示「表头缺少学籍号」 | 本 App 按中文表头识别列，需保留原始上报表表头（含「学籍号」） |
| 导入 `.xls` 提示旧格式 | 旧版二进制 xls 不支持，请在 Excel/WPS 中「另存为 .xlsx」后再导入 |
| 导出后批注/格式丢失 | 不要用第三方工具重建工作簿；本实现基于模板字节改写，可用 `verify_export.py` 复核 |
| 导出 Excel 时无任何提示直接回到桌面 | 蓝叠日志定位的根因是 `lowmemorykiller` 用 SIGKILL(9) 杀掉前台进程（`am_proc_died` 的 oom_adj=0、`proc died without state saved`、crash 缓冲区为空），**不是 Java 异常**，所以 try/catch、Snackbar 与切 IO 都兜不住。现已改为真流式单行缓冲 + 设备内存预算；同一份 123KB 模板 / 515KB 工作表在蓝叠上实测两次导出 331ms / 219ms 完成，进程全程存活且无 LMK 记录 |
| 导出提示「表格过大，请一次只勾选一个班分批导出」 | 工作表解压后超过设备内存预算时提前拒绝，不留下半成品；按提示分批导出，或精简原始模板后重新导入 |
| 导出提示「保存位置不可写 / 没有保存权限」 | 改存到系统「下载」目录后重试，也可导出后直接到该目录打开 xlsx |
| 点「分享最近导出的表」无反应或闪退 | 已补齐 URI 读授权与组件可用性检查；没有可分享应用时会提示到「下载」目录直接打开 |
| 每组人数下次进入变了 | 「每组叫出几名同学」存在本地 `meta`，导入新表不会重置；只有「导入备份包恢复」会按备份包内容恢复（无设置则回到默认 1 人） |
| 网络导入失败 | 仅支持 `http/https` 直接指向 `.xlsx` 文件的地址，需手机可访问该地址且文件 ≤ 24MB |
| 电脑打不开备份页 | 确认手机与电脑在同一 WiFi、服务开关已开、地址与访问码一致、手机未开启 AP 隔离 |

## 七、数据隐私与上传到公开仓库

### 仓库里的示例数据全部是虚构的

`data-example.xlsx`、`app/src/main/assets/data-example.xlsx`、`scripts/preview-backup.zip` 里的学生信息已全部改写为演示用虚构值：学籍号 `DEMO-STU-0001` / `DEMO-STU-0002`、姓名「示例学生甲 / 示例学生乙」、住址「示例省示例市示例区示例路…」，出生日期为通用示例日期，文档属性的作者也改为「体测助手示例数据」。**仓库不含任何真实学生的姓名、学籍号、住址或出生日期**，可以安全推送到公开仓库。

### App 不会把数据上传到互联网

- 唯一的 HTTP 客户端 `XlsxDownloader` 只做 **GET 下载**（教师手填的 `http(s)` 表格地址），没有 POST/PUT、表单提交、埋点、崩溃上报或分析 SDK；依赖只有 Compose / Room / OkHttp 等。
- 导出表通过系统文件选择器（`ACTION_CREATE_DOCUMENT`）写到**教师主动选择**的位置；「分享最近导出的表」是用户主动触发的 `ACTION_SEND`。
- 日志只有 `TiceExport` 的体积、预算与耗时，`BackupService` 只记服务状态，均不记录姓名、学籍号、住址或工作表内容。
- `android:allowBackup="false"`，阻止通过 adb backup 导出应用私有数据。

**唯一的对外数据通道是局域网备份服务**：手机在 `0.0.0.0:8080~8092` 开启只读 HTTP 服务，同 WiFi 的电脑输入 4 位访问码后可下载**完整备份包（含全部学生与成绩）**。这是有意功能，请只在可信的校内网络临时开启、用完立即关闭，不要长期运行。

### 推送到 GitHub 前的检查清单

1. 确认 `.gitignore` 生效：`.tools/`（约 3.5GB 本机工具链）、`.gradle/`、`app/build/`、`local.properties`、`.codebuddy/`，以及运行期导出的成绩表与备份包都不入库；可用 `git status --ignored` 复核。
2. **不要**把真实的全校上报表、导出的成绩表、`tice-backup-*.zip` 或任何含学生信息的文件复制进本仓库再提交。真实数据只存在于手机应用私有目录，或教师自己选择的保存位置。
3. `gradle.properties` 里的 `org.gradle.java.home` 由 `setup-env.ps1` 按本机绝对路径生成，含本机用户名且对他人无效，**提交前请保持删除状态**；构建请用 `scripts/build-apk.ps1`（自动设置 `JAVA_HOME`）或自行设置环境变量。每次运行 `setup-env.ps1` 该行都会重新出现，提交前记得检查。
4. 推送前跑 `git status` 与 `git diff --cached`，确认暂存区没有真实数据文件。若曾把含真实信息的文件提交进历史，仅删除文件不够，必须先清理 git 历史再公开仓库。

## 八、发布新版本（GitHub Actions）

`.github/workflows/release.yml` 只由**版本标签**触发；普通的 push、Pull Request、定时任务都不会构建或发布：

```powershell
git tag v1.0.1
git push origin v1.0.1
```

工作流在 `ubuntu-latest` 上依次：安装 JDK 17 与 Android SDK 35 / Build Tools 35.0.0 → 执行 `assembleDebug testDebugUnitTest`（单测不通过即中止）→ 生成 `tice-app-<tag>.apk` 与 `SHA256SUMS.txt` → 用内置 `GITHUB_TOKEN` 创建同名 GitHub Release，并自动附上 `--generate-notes` 生成的更新日志。标签里带 `-`（如 `v1.1.0-rc1`）会自动标记为预发布。只申请 `contents: write` 权限，无需配置额外 secrets。

发版前请确认：

1. 更新 `app/build.gradle.kts` 的 `versionCode`（每次递增）与 `versionName`；标签只用于命名 Release，不会自动写进 APK。
2. 按第七节清单复核隐私与 `.gitignore`，确认没有真实学生数据或本机路径被提交。
3. 本地先跑 `scripts\build-apk.ps1`，确认构建与 6 个单测通过后再打标签。

**产物说明**：工程尚未配置签名密钥，Release 附的是 debug 签名、可直接安装的 APK，适合校内自行分发。若要发布正式签名包，需在仓库 Secrets 放入 keystore 与口令、在 `app/build.gradle.kts` 增加 `signingConfig`，并把工作流的 `assembleDebug` 改为 `assembleRelease`；不要把未签名的 `app-release-unsigned.apk` 直接分发。

> 如果希望改成「在 GitHub 网页点 Publish release 之后才构建」，把 `on:` 改为 `release: types: [published]`，并改用 `gh release upload` 把产物上传到已存在的 Release。

## 九、目录

```
settings.gradle.kts / build.gradle.kts / gradle.properties / gradle/libs.versions.toml
scripts/setup-env.ps1        # 命令行准备 JDK17 + Android SDK + Gradle Wrapper
scripts/build-apk.ps1        # 命令行构建 + 单元测试
scripts/verify_export.py    # 模板部件盘点与导出保真校验
app/src/main/java/com/example/tice/
  xlsx/        # OOXML 解析与模板保真导出（纯 JVM，可命令行单测）
  data/db/     # Room 学生、成绩、项目规则、元数据
  data/repo/   # 导入、选班测试、导出编排
  backup/      # 备份包、只读 HTTP 服务、前台服务
  ui/          # Compose 五个页面、主题、导航
app/src/test/  # XlsxCodecTest 命令行单元测试
```
