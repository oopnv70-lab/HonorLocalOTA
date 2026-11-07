# HonorLocalOTA 项目现状总览

最后更新：2026-11-06
目标应用：`com.hihonor.ouc`（荣耀「软件更新」）18.0.3.230 / versionCode 205
设备：HONOR AAK-AN00，MagicOS 11 / Android 17 (SDK 37)，构建号 `AAK-AN00 11.0.0.110(SP7C00E110R102P2)`
仓库：`oopnv70-lab/HonorLocalOTA`

---

## 一、目标

让 OUC 的「本地更新」可用：

1. 能选包并装上
2. 不弹"会格式化设备"的免责声明
3. 名字不与原版冲突

---

## 二、架构定案（用户已拍板）

```
装模块（LSPosed 勾选 com.hihonor.ouc）
  -> 三点菜单「本地更新」（原版项，条件已满足，自己出现）
  -> hook kh.l.K(ctx) 不 proceed
  -> 同一 OUC 进程内 addContentView 盖一层全屏 FrameLayout（PanelView）
  -> 面板 SAF 选包
  -> 普通 java.io.FileOutputStream 写 /data/local_ota
  -> 反射 kh.l.L(ctx)
```

面板"本身没有其他高级能力"，只做**选文件 / 写文件 / 转告**。

**严禁再引入**：跨进程 Intent extra、exported Activity、自定义菜单 id、改菜单 List、su、chcon、400ms 延迟。

### 演进过程（为什么是这个形态）

| 版本 | 做法 | 作废原因 |
|---|---|---|
| v1 | 独立 APK 的 `OtaActivity` 搬包 -> 带 `EXTRA_START` 拉起 `MainEntranceActivity` -> hook `onCreate`/`onNewIntent` 认 extra 去反射 `L()` | 架构错位。hook 本来就跑在 OUC 进程里，进程内直接画 UI 即可，跨进程纯属自造包袱 |
| v2 | 改菜单 List 插入自定义项 | 真正的开关 `local_ota_for_developer` 从没打开；原版项在条件满足后自己会出 |
| v3（当前） | hook `K()` 不 proceed + 同进程全屏面板 + 反射 `L()` | — |

---

## 三、三个前置条件（本机已全部满足）

`kh.l.C(Context)`（`kh/l.smali:2879`）三项全过才显示菜单项：

| # | 条件 | 本机值 |
|---|---|---|
| 1 | `SystemPropertiesEx.get("msc.update.type","")` == `"1"` | `1` ✅ |
| 2 | `A()`：`Process.myUid()` -> `getUserId(uid)` == `0`（主用户，非账号） | true ✅ |
| 3 | `Settings$Secure.getString(cr,"local_ota_for_developer")` == `"1"` | `1` ✅（原为 `0`，已 `settings put`） |

外加 `/data/local_ota` 存在，`drwxrwxrwx system:cache u:object_r:local_ota_file:s0`。

> **教训**：不要去绕过判定条件，要先把条件凑齐。绕过得到的是假象。

---

## 四、不需要 root 的根据

`/system_ext/etc/selinux/system_ext_sepolicy.cil`：

```
15450: (allow platform_app_all local_ota_file (dir (ioctl read write create getattr setattr lock unlink rename open add_name remove_name reparent search rmdir)))
15453: (allow platform_app_all local_ota_file (file (ioctl read write create getattr setattr lock append map unlink rename open watch watch_reads)))
20242: (allow update_engine local_ota_file (file (read getattr map unlink open)))
```

`/system_ext/etc/selinux/system_ext_file_contexts:189`：

```
/data/local_ota(/.*)?    u:object_r:local_ota_file:s0
```

OUC 域 `u:r:platform_app_36:s0:c512,c768` 属于 `platform_app_all`，**有完整写权限，新建文件标签自动继承，owner 不必是 system:cache**。

实测 `su -Z u:r:platform_app_36:s0 -c 'echo testdata > /data/local_ota/_ztest.bin'` -> exit=0，`ls -lZ` 得 `u:object_r:local_ota_file:s0`。

---

## 五、为什么必须 SAF

| 事实 | 回读 |
|---|---|
| OUC 读不了 `/sdcard` | `Permission denied` |
| `MANAGE_EXTERNAL_STORAGE` 未授予 | `appops` -> `No operations` |
| `update_engine` allow 表无 `fuse` | `/sdcard` 上的包永远读不到 |
| OUC 进程内无法再次提权 | `su 10132 -c 'su -c id'` -> `su: inaccessible or not found` |

所以：包必须在 `/data/local_ota`，而 OUC 读不到 `/sdcard`，只能用 SAF 让用户手动授一个文件。

---

## 六、降级 / 恢复出厂（只读调研结论）

详见 `/sdcard/ouc_re/RESULT_降级可行性调研.md`。

**结论：可以降级，不需要恢复出厂。**

1. **`POWERWASH=0`**：`kh/l.smali:492` 的 `F()` 写死 header 为 `POWERWASH=0`，OUC 从不请求擦除数据。这条经 `y() -> h[] -> b/a.c() -> IUpdateEngine.applyMultiPayloads()` 直送 `update_engine`。
2. **那句"会格式化用户数据"只是文案**：`res/values-zh-rCN/strings.xml:609` 的 `local_update_warrant_conent`，由 `kh.l.R(ctx)` 弹，与实际执行路径无关。
3. **`userdata` 不在 OTA 范围**：`ro.product.ab_ota_partitions = product_h,system,system_ext,vbmeta_system`，`/data` 完全不参与，VAB 走用户空间快照。
4. **唯一风险是 SPL 降级**：本机 `ro.build.version.security_patch = 2026-09-01`，`ro.ota.allow_downgrade` 未设置。目标包 SPL 更老时进 SPL 降级分支；`ro.boot.flash.locked=1` + `verifiedbootstate=green` 组合下，按 `/system/bin/update_engine` 字符串 `...it probably has a locked bootlaoder. Since a locked bootloader will reject SPL downgrade no matter what, we will reject this OTA.` 判定为**拒绝**，不是先擦后装。
5. **OUC 侧无降级拦截**：全量 grep `downgrade` 只命中 DB/Provider 类，`POWERWASH` 只以 `0` 出现一次。裁决权全在 `update_engine`。

### 重要更正

`L()` 里那个 `if-ge v1, v2` **不是版本比较，是电量百分比比较**：

- `x9/b.J0()` = 字段 `h`，唯一写入点 `W4(I)`，全工程 3 处调用全是 `level * 100 / scale`
- `r9/w1.l2()` 返回电量下限：`S2()`(23414) / 常量 `5` / `d3()`(39446)，兜底 30 或 20

---

## 七、代码结构

`app/src/main/java/io/github/honorlocalota/`

| 文件 | 职责 |
|---|---|
| `Const.java` | `DST_DIR="/data/local_ota"`、`NEEDLE="update_full_"`、`MIN_FREE_BYTES=0x780000000L`、`PKG_OUC="com.hihonor.ouc"` |
| `PanelView.java` | `extends FrameLayout`；`show()` = `findViewById(android.R.id.content).addView(this, MATCH_PARENT, MATCH_PARENT)` + `setTranslationZ(100f)`；`onPick()` = `ACTION_OPEN_DOCUMENT` + `CATEGORY_OPENABLE` + `type="*/*"`；`onActivityResult()` 里 `takePersistableUriPermission` + `OpenableColumns.DISPLAY_NAME`；`copyIn()` 256KB 流式拷贝 + `normalize()` 补名；`invokeInstall()` 反射 `kh.l.u()` -> `getDeclaredMethod("L", Context.class)` |
| `OtaHook.java` | `hook(kh.l.K)` 不 proceed 改挂面板；`hook(Activity#onActivityResult)` 按 `chain.getThisObject() == p.hostActivity()` 转发 |

`AndroidManifest.xml`：**零权限、零 Activity**。
`proguard-rules.pro`：不 keep 任何 Activity，`PanelView` 故意不 keep。

### 关键靶点

| 靶点 | 位置 | 说明 |
|---|---|---|
| `kh.l.K(Context)` | `kh/l.smali:3011` | 免责弹窗的入口，`R()` 就在其体内 |
| `kh.l.L(Context)` | `kh/l.smali:743` | **private**，安装出口；`w` 字段是 `applyPayload` 的唯一来源 |
| `Activity#onActivityResult` | 框架基类 | `MainEntranceActivity` 与父类 `BaseActivity` 均未重写（各命中 0 次） |

---

## 八、当前状态

| 项 | 状态 |
|---|---|
| 三个前置条件 | ✅ 已凑齐并实测 |
| 不需 root 的 SELinux 根据 | ✅ 已逐条定位到行 |
| 代码（3 文件 + Manifest + strings + proguard） | ✅ 已写完 |
| 静态自检（check3/check4/check5 + brace.py） | ✅ 全绿，修掉 5 个真实问题 |
| 推送 | ✅ `ab894f25`（parent `0219c042`，tree `adbe4b1a`） |
| `ab894f25` Actions 构建 | ❓ **未确认** |
| 真机验证链路 | ⬜ 待构建产物下载后 |
| 降级可行性调研 | ✅ 已完成（只读） |

---

## 九、未验证（不得当成事实）

- hook `kh.l.K` 在 libxposed 下是否真能生效
- `addContentView` 是否与 OUC 自身 View 层级冲突
- `hook(Activity#onActivityResult)` 挂基类是否被 ART 内联绕过
- SAF 的 `content://` 在 OUC 进程内 `openInputStream` 是否真能读
- `FileOutputStream` 写已存在文件是否仍继承标签（已用 `dst.delete()` 先删规避）
- 反射 `L()` 后 `S()` 被 `kh/l$b` 线程与 `LocalUpdateVabInstallingActivity` 各调一次是否重复安装
- 目标包 `post-security-patch-level`（决定降级是否被 SPL 分支拦下）

---

## 十、相关文档

| 文件 | 内容 |
|---|---|
| `/sdcard/ouc_re/KNOWLEDGE_本地更新机制.md` | 机制与架构手册（代码链、SELinux、方法索引、环境坑） |
| `/sdcard/ouc_re/RESULT_降级可行性调研.md` | 降级 / 恢复出厂只读调研全文 + 证据 + 命令清单 |
| `/sdcard/ouc_re/RESULT_本地更新开关恢复.md` | 更早一轮的开关恢复记录 |