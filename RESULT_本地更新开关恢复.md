# 荣耀 WIN RT 本地更新开关恢复记录

设备：AAK-AN00　系统 11.0.0.110(SP7C00E110R102P2)
目标应用：com.hihonor.ouc（软件更新）18.0.3.230
Root：KernelSU，域 u:r:ksu:s0，SELinux Enforcing
结果：**已恢复**，菜单中"本地更新"可显示

---

## 一、判定逻辑（代码级）

入口：`kh/l.smali` 的 `C(Landroid/content/Context;)Z`（方法起始约 2879 行）

```
isSupportLocalUpdate():
  条件1  msc.update.type == "1"
         失败日志：Local upgrades are not allowed on the current device
  条件2  A() == true   →  Process.myUid() 所属 userId 必须为 0
         失败日志：When the device is not using the account
  条件3  Settings.Secure["local_ota_for_developer"] == "1"
         失败日志：Local upgrade switch not turned on
  三者全满足 → return true
```

调用链：

```
LocalUpdateVabInstallingActivity
  └─ kh/l.S(Context)         启动流程
       ├─ kh/l.T(Context)    发 Intent 启动 LocalUpdateService
       │    action = com.hihonor.ouc.vab.intent.action.LOCAL_UPDATE_INSTALL
       ├─ kh/l.C(Context)    三道判定
       └─ kh/l.z()           是否已处于本地更新态（用 kh/p.n() 返回 8 或 9 判定）
```

相关常量（`kh/l` 构造函数中初始化）：

| 字段 | 值 |
|---|---|
| `a` | `msc.update.type` |
| `c` | `local_ota_for_developer` |
| `d` | `.*update_full_.*\.zip`（包名匹配正则） |
| `e` | `/data/local_ota/` |

---

## 二、关键结论：开关没有写入口

`local_ota_for_developer` 在整个 APK 内**只有两处引用**：

- `kh/l.smali:112` — 构造函数赋给字段
- `kh/l.smali:2968` — `C()` 里读取

**没有任何代码写这个键。** 因此不存在"打开某个界面开关让该键变 1"的 UI 路径，正式版是把写入端删掉了、判定端留着。这就是"正式版看不到"的真正原因，不是简单隐藏。

---

## 三、三个判定的物理位置（决定为什么不需要碰分区）

| 判定 | 位置 | 重启后 | AVB/dm-verity |
|---|---|---|---|
| `msc.update.type` | 属性区内存 | **丢失** | 不涉及 |
| `local_ota_for_developer` | /data/system/users/0/settings_secure.xml | 保留 | 不校验 |
| `/data/local_ota` 目录 | /data 分区 | 保留 | 不校验 |

分区实测：

```
/data   → f2fs (rw, seclabel, ...)          可写，无 verity
/vendor → erofs (ro, seclabel, ...)         只读，受 AVB 保护
ro.boot.veritymode = enforcing
```

**三样全在 /data 与内存里**，因此不需要解锁 BL、不需要修改分区、不需要挂载任何东西。

---

## 四、init.rc 的原始定义

`/system/etc/init/hw/init.rc` 第 1209-1211 行：

```
on post-fs-data && property:msc.update.type=1
    mkdir /data/local_ota 0777 system cache
    restorecon /data/local_ota
```

注意这是「事件 + 属性」的组合触发器，**只在 post-fs-data 那一刻求值一次**。若属性设置过晚，init 不会补建目录。

`msc.update.type` 全系统有三处消费者：
- `com.hihonor.ouc`（本功能判定）
- `/system/bin/gsid`（GSI 安装）
- `/system/bin/update_engine`（OTA 引擎）

---

## 五、已创建的 KSU 模块

路径：`/data/adb/modules/honor_local_ota/`

```
module.prop          306B
post-fs-data.sh      703B  0755
service.sh           766B  0755
```

**不含任何挂载逻辑，system/ 目录为空。**

`post-fs-data.sh`：
- `resetprop -n msc.update.type 1`
- 补建 `/data/local_ota`，`chown system:cache`、`chmod 0777`、`restorecon`

`service.sh`：
- 等 `sys.boot_completed=1` 再等 5 秒，再次 `resetprop -n`
- `settings put secure local_ota_for_developer 1`
- 目录若被清理则重建

### 写入时踩到的实际坑

普通 `resetprop` 会失败：

```
resetprop: Failed to set msc.update.type: __system_property_set failed: -1
```

原因：它走 property_service 通道，值相同时被拒绝。改用 `resetprop -n`（跳过 service，直接 mmap 写属性区）后正常。

未采用 `--delete` 再写的方式，因为删除瞬间属性为空，`gsid` / `update_engine` 可能读到空值。

---

## 六、当前实测状态

```
msc.update.type          = 1
local_ota_for_developer  = 1
/data/local_ota          → u:object_r:local_ota_file:s0   (system:cache, 0777)
```

`restorecon` 将其标记为 `local_ota_file` —— 这是系统专门为该特性定义的 SELinux 类型，反向印证此目录属于一个正式特性而非临时目录。

界面验证：OUC「当前版本」菜单中「本地更新」已可见。

---

## 七、安装阶段的注意事项

`/system/bin/update_engine` 内的相关字符串：

```
parameter of LOCAL_OTA is true / false
this is not local ota updating.
local ota update, will skip online auth.        ← 跳过服务器签名校验
local ota update, will reboot resetuser in 10s  ← 会 resetuser
set wipe_data_factory_reset:local ota
Payloads are not in pairs when local ota updating, please check!
```

要点：

1. **跳过在线授权** —— 不需要等官方服务器放行，这是本地更新最有价值的特性。
2. **会 resetuser / wipe_data_factory_reset** —— 按设计清数据，OUC 里的提示文案也写明"会格式化用户数据并自动重启设备"。
3. 包名必须匹配 `/data/local_ota/` 下的 `.*update_full_.*\.zip`。

**`/data/update/HnOUC/936642/update_base.zip` 不符合该命名规则**，放进去会在扫描阶段被过滤，不要用它试。

---

## 八、回退方式

删掉 `/data/adb/modules/honor_local_ota` 后重启：

- `msc.update.type` 自动恢复为空（属性本就不持久）
- `local_ota_for_developer` 需手动清：`settings delete secure local_ota_for_developer`
- `/data/local_ota` 可手动删除

无任何分区改动，回退干净。
