# 免 Root 充电监测与协议识别的技术可行性边界

本文回答两个问题：**不 Root 到底能做到什么**，以及**各品牌私有协议识别会遇到哪些硬性限制**。
文中所有「不可读」结论都有明确的机制来源；第 6 节给出在一台真实机型（vivo 机型 / Android 16）
上的实测证据。

---

## 1. 结论速览

| 需求 | 免 Root 可行性 | 实现方式 | 现实精度 |
| --- | --- | --- | --- |
| 实时功率 | ✅ 可行 | `V_batt × I_batt` | 电池侧功率，与适配器输出差 5–12% |
| 实时电流 | ✅ 基本可行 | `BATTERY_PROPERTY_CURRENT_NOW` | 少数机型返回 0 / 单位为 mA，需运行时校准 |
| 实时电压 | ✅ 可行 | `EXTRA_VOLTAGE` | 电池端电压，**不是** VBUS（5/9/20 V） |
| 时间序列记录 | ✅ 可行 | 前台服务 + 1 Hz 定时 + SQLite | 熄屏可连续，受 OEM 冻结影响 |
| 趋势图表 | ✅ 可行 | 自绘 Compose Canvas | — |
| 有线 / 无线区分 | ✅ 可行 | `EXTRA_PLUGGED`（AC / USB / WIRELESS / DOCK） | 可靠 |
| 公有协议识别 | ⚠️ 仅能推断 | 功率台阶 / 电压平台 / 机型先验 | 协议族级别，版本不可知 |
| 厂商私有协议识别 | ⚠️ 仅能推断 | 档位 + 电荷泵特征 + 机型 | 档位区间，非协商结果 |
| 读取真实 VBUS / PDO | ❌ 不可行 | 需 sysfs / UCSI / Root | — |
| 读取私有握手结果 | ❌ 不可行 | 需充电 IC 节点（SELinux 拒绝） | — |
| 读取充电器型号 | ❌ 不可行 | 需 USB Host 模式枚举 | — |
| 无线充电协议细节 | ❌ 不可行 | 需 Qi 接收器固件 / I²C | — |

一句话总结：**免 Root 能拿到「结果侧」的高质量数据，拿不到「协商侧」的任何信息。**
所有协议识别本质上都是基于结果特征的概率推断。

---

## 2. 免 Root 能拿到的信号

`BatteryManager` 与 `ACTION_BATTERY_CHANGED` 是唯一稳定、公开、无需特权的数据源。

| 信号 | 来源 | 单位 / 取值 | 可用性 |
| --- | --- | --- | --- |
| 电池电压 | `EXTRA_VOLTAGE` | mV，典型 3.3–4.5 V（双电芯串联可到 8–9 V） | 几乎所有机型 |
| 电池电流 | `BATTERY_PROPERTY_CURRENT_NOW` | µA，充电为正（个别机型相反） | 多数机型；少数恒 0 或误用 mA |
| 平均电流 | `BATTERY_PROPERTY_CURRENT_AVERAGE` | µA | 部分机型 |
| 库仑计 | `charge_counter` | µAh | 多数机型，可用于校准电流单位 |
| 电量 | `EXTRA_LEVEL` / `EXTRA_SCALE` | % | 全部 |
| 温度 | `EXTRA_TEMPERATURE` | 0.1 °C | 全部 |
| 充电状态 | `EXTRA_STATUS` | 充电 / 放电 / 满 / 未充电 | 全部 |
| 插电类型 | `EXTRA_PLUGGED` | AC / USB / WIRELESS / DOCK | 全部 |
| 协商上限电压/电流 | `max_charging_voltage` / `max_charging_current` | µV / µA，Android 14+ AOSP 字段 | **经常为 0**（厂商未填充） |
| 充电状态扩展 | `charging_status` | 枚举，Android 14+ | 视厂商 |
| USB-C 端口诊断 | `UsbManager.getPorts()` / `UsbPort.complianceWarnings` | 端口状态、兼容性告警码 | 需要 `MANAGE_USB` 系统权限，普通应用通常拿不到 |
| 电池健康 / 技术 | `EXTRA_HEALTH` / `EXTRA_TECHNOLOGY` | 枚举 / 字符串 | 全部 |

### 2.1 电流的两个坑

1. **单位**：文档写的是 µA，但部分 OEM 内核把 mA 直接填进来，导致读数小 1000 倍。
   本应用用库仑计做在线校准：对 `I·dt` 积分，与 `charge_counter` 的增量比较，
   比例落在 300–4000 之间时判定为 mA 并永久保存系数。
2. **符号**：多数机型充电时为正，放电为负；个别机型相反。应用只在「系统已声明在充电」
   且电流为负时翻转符号，避免把放电误判成充电。

### 2.2 电压的语义

`EXTRA_VOLTAGE` 是**电芯端电压**，随 SOC 在 3.3–4.5 V 之间缓慢变化。
它不随充电器档位跳变（9 V / 20 V 是 VBUS，应用看不到）。
因此不能用它判断「现在是 9 V 档还是 20 V 档」，只能借助功率与电流反推输入电压等级。

---

## 3. 免 Root 拿不到的信号（以及为什么）

这些数据都在内核 charger 驱动、PMIC 寄存器或 USB PD 控制器里，通过
`/sys/class/power_supply/*`、`/proc`、I²C 暴露；Android framework 没有为它们提供公开 API，
SELinux 也禁止 `untrusted_app` 域读取。

| 信号 | 典型节点 | 价值 |
| --- | --- | --- |
| VBUS 电压 / 电流 | `/sys/class/power_supply/usb/voltage_now`、`current_now` | 直接得到适配器输出功率 |
| 充电类型 | `.../usb/charge_type`、`real_type` | 直接读出 SDP/CDP/DCP/PD/私有协议枚举 |
| PD 状态 | `.../usb/pd_active`、`typec_cc_orientation`、UCSI 节点 | 判断 PD 是否激活、CC 方向 |
| 私有闪充节点 | `vfcs-fchg/power`、`vfcs-cp-0/1`（vivo）、`wls_rx/power` | 厂商私有协议档位与无线接收功率 |
| PD PDO 列表 | UCSI `get_pdos`、PD 控制器寄存器 | 精确契约（5V/9V/…/PPS 档） |
| 充电器 VID/PID | USB Host 枚举 | 识别适配器型号 |
| 无线线圈参数 | Qi 接收芯片 I²C / 厂商节点 | 频率、耦合、异物检测 |

> 注意：`UsbManager` 只有在手机作为 **USB Host**（OTG）时才能枚举对端设备。
> 充电时手机是 **USB Device**，从原理上就拿不到适配器的描述符。

---

## 4. 功率的语义与误差

```
P_batt = V_batt × I_batt           // 应用显示的主数值
P_input ≈ P_batt / η               // 「估算输入」，η 可配置，默认 0.92
```

误差来源：

- **充电 IC / 电荷泵效率**：双电芯电荷泵 90–95%，单电芯降压直充 85–90%，无线更低（75–85%）。
- **系统负载分流**：亮屏、5G、游戏时，部分输入电流直接供给系统，不进入电池。
- **线损与适配器余量**：标称 120 W 的适配器在电池侧通常只能测到 100–112 W。
- **峰值持续时间**：120 W 一般只在低 SOC、低温、亮屏/息屏特定策略下维持 1–5 分钟，
  随后阶梯下降到 60 W、40 W、20 W。这也是「熄屏后功率提升阶段」必须完整记录的原因。

因此界面把主数值标注为「电池侧」，另给一个「估算输入」。**不要**把电池侧功率直接当作
适配器标称功率做对比，否则会得出「充电器虚标」的错误结论。

另一个已知不确定性：部分双电芯机型（2S 电池包）上报的 `EXTRA_VOLTAGE` / `CURRENT_NOW`
可能是「单节电压 + 整包电流」或「整包电压 + 整包电流」，取决于电量计实现。前者会让
`V × I` 只有真实功率的一半。判断方法：用本机 `max_charging_voltage`（若非 0）或外置功率计
对照；CSV 导出的原始 `voltage_mV` / `current_uA` 字段可用于离线校正。应用的「估算输入」
基于效率系数，不能修正这种 2 倍量级的口径差异。

---

## 5. 协议识别：能做到什么程度

### 5.1 可用的判别特征

| 特征 | 物理含义 | 区分能力 |
| --- | --- | --- |
| 峰值功率档位 | 适配器能力 + 手机策略 | 强（33 / 67 / 120 W 级可粗分） |
| 功率台阶数量与幅度 | 固定 PDO 切换 / 私有握手 | 中强（PD 固定档 vs PPS） |
| 电池电流 >4 A 且功率 >35 W | 双电芯 + 电荷泵 | 强（私有百瓦级 vs 普通 PD） |
| 上报的协商电压上限 | 系统已知的输入电压等级 | 强，但多数机型填 0 |
| 平滑爬升 vs 阶跃 | PPS 恒流爬升 vs 固定档 | 中 |
| 机型品牌 / 型号 | 该品牌常用协议族 | 强先验 |
| 用户标注的充电器 | 直接给定档位 | 最强，但依赖人工 |

### 5.2 应用采用的推断流程

1. 取当前会话最近 15 分钟窗口（最多 900 点）。
2. 提取特征：峰值/均值功率、峰值电流、电压范围、>5 W 台阶次数、电荷泵形态、上报电压等级。
3. 用品牌先验过滤：其他品牌的私有协议直接排除（物理上不可能协商成功）。
4. 对每个候选按「档位距离 + 电荷泵形态 + 电压等级 + 协议族特征」打分。
5. 归一化为百分比，并按样本量与时长折算置信度（30 秒的数据不会显示 90% 可信）。
6. 输出前 3 名 + 判定依据 + 未能读取的信号清单。

### 5.3 明确的不可知项

- 协议版本号（例如 PD 3.0 还是 3.1、SCP 的哪一代）。
- 具体 PDO/PPS 档位与电流限值。
- 充电器是否原装、型号、固件。
- 线缆的 E-Marker 能力（5A 线 vs 3A 线）。
- 私有协议的握手是否真的成功（只能从功率结果反推）。

### 5.4 现实准确率预期（工程估计）

以下不是实验室数据，而是基于特征可分性的估计，需要用众包数据集校准：

| 场景 | 预期 |
| --- | --- |
| 品牌 + 功率档位（低 SOC 峰值段） | 80% 左右可落到正确档位区间 |
| 同品牌相邻档位（67 W vs 90 W） | 峰值差 >15 W 时可分，否则不可靠 |
| 协议族（PD / PPS / 私有） | 多数情况下可给出正确族别 |
| 中高 SOC 恒压段（>70%） | 基本不可区分，只能给出「充电中」 |
| 无线 5–15 W | 可区分 Qi BPP / EPP |
| 无线 >15 W | 可判定「私有无线快充」，档位误差大 |

---

## 6. 四个品牌私有协议的具体限制

### 6.1 小米 / 红米（HyperCharge、MI Turbo Charge）

- **协商机制**：充电器与 PMIC 之间完成，部分档位基于 USB PD 扩展（20 V 电压 + 私有电流能力），
  部分走 D+/D- 私有电平；120 W 机型普遍是双电芯 + 电荷泵。
- **免 Root 能看到的**：电池侧峰值（33 W 机型约 27–33 W，67 W 约 55–65 W，120 W 约 95–112 W）、
  6 A 级电池电流、明显的功率台阶。
- **限制**：无法区分「67 W 原装」与「90 W 适配器降档运行」；无法判断是否触发了 120 W 的
  特定条件（温度、息屏、原装线）；恒压段与 67 W 曲线重合。
- **典型误判**：用 120 W 充电器给 67 W 机型充电时，峰值只有 60 W 左右，会被判为 67 W 档——
  这在「手机实际接受的功率」层面是正确的，但用户会以为识别错了充电器。

### 6.2 vivo / iQOO（FlashCharge、双芯闪充）

- **协商机制**：独立闪充 IC + 适配器 MCU 私有握手；实测机型存在 `vfcs-fchg`、`vfcs-cp-0/1`
  等专属节点，说明协议栈完全在厂商私有驱动内。
- **免 Root 能看到的**：44 / 66 / 80 / 120 / 200 W 档位的功率形态，双电芯大电流特征。
- **限制**：上述节点对普通应用（甚至 `adb shell`）不可读；无法得知当前是 11 V 还是 20 V 输入；
  充电器与手机的品牌绑定（非原装通常回落 PD/PPS），回落过程只能从功率骤降间接观察。
- **典型误判**：非原装但支持 PPS 的充电器可能跑出接近 44 W 的功率，被误判为 vivo 44 W 档。
  这类情况下「协议族」判断是错的，只有功率档位是对的。

### 6.3 魅族（mCharge、Super mCharge）

- **协商机制**：早期 mCharge 接近 QC 类 D+/D- 编码（12 V 档），Super mCharge 转向
  PPS/私有高压直充；机型存量小，公开资料与可复现数据都少。
- **免 Root 能看到的**：24 / 40 / 65 / 80 W 级功率档位。
- **限制**：魅族机型的充电曲线样本少，知识库先验的置信度天然低于小米/vivo；
  同一机型在不同固件上的策略差异较大；与通用 PPS 充电器的曲线几乎无法区分。
- **典型误判**：把「魅族手机 + 第三方 PPS 充电器」判为 Super mCharge。

### 6.4 华为 / 荣耀（SCP、FCP）

- **协商机制**：FCP 用 D+/D- 电压编码实现 9 V/2 A；SCP 通过 D+/D- 私有握手进入
  4.5 V/5 A（22.5 W）等低压大电流模式，后续世代引入 10 V/4 A、11 V/6 A + 电荷泵。
- **免 Root 能看到的**：18 W 固定档的台阶特征、22.5 W 低压大电流（电池电流大但电压低）、
  40 / 66 / 88 / 100 W 的电荷泵形态。
- **限制**：D+/D- 状态完全不可见，无法区分 FCP 与普通 9 V PD；SCP 与其他低压直充方案
  （VOOC 类）在电池侧表现相似，只能靠品牌先验区分；荣耀与华为共用协议族但档位命名不同。
- **典型误判**：华为 22.5 W SCP 与 OPPO VOOC 20 W 在电池侧都是「低电压 + 4–5 A」，
  换品牌后特征几乎一致，必须依赖机型。

### 6.5 其他品牌

| 品牌 | 协议族 | 免 Root 识别要点 | 主要限制 |
| --- | --- | --- | --- |
| OPPO / 一加 / realme | VOOC / SuperVOOC | 5 V 直充特征明显，65–240 W 档位跨度大 | 与 SCP 低压直充混淆；240 W 需专用线 |
| 荣耀 | SCP 同源 | 同华为 | 档位命名差异 |
| 三星 | AFC / PD PPS | 15 / 25 / 45 W 三档清晰 | 45 W 需 PPS 充电器，第三方常降到 25 W |
| Google Pixel | PD / PPS | 曲线平滑，PPS 特征典型 | 与通用 PPS 无法区分 |
| Apple | PD 固定档 | 20 / 30 / 96 W 台阶清晰 | 与普通 PD 充电器无差异 |
| 摩托罗拉 / 华硕 / 索尼 | PD / QC | 公有协议为主 | 只能给族别 |

---

## 7. 无线充电场景

**能判定**：`EXTRA_PLUGGED == 4`（`BATTERY_PLUGGED_WIRELESS`），可与有线明确区分；
能记录电池侧功率曲线；能按功率区分 Qi BPP（5 W）、EPP（7.5–15 W）与私有快充（>15 W）。

**不能判定**：

- 线圈频率、占空比、耦合系数、线圈对齐程度（这些在 Qi 接收芯片里）。
- Qi 版本（BPP/EPP/Qi2）与认证状态。
- 无线充电底座型号、异物检测（FOD）状态。
- 无线充电路径的效率（线圈到电池的效率通常 75–85%，远低于有线），
  所以「估算输入」在无线场景误差更大。
- **反向无线充电**（手机给耳机/手表供电）：此时 `plugged == 0`，电流为负，
  与普通放电在 API 层完全一致，只能给出「可能在反向供电」的低置信度提示，不应作为结论。

---

## 8. 熄屏完整性的实现边界

需求中的「熄屏后功率提升阶段数据完整」是最容易翻车的部分，原因不在 API，而在调度：

| 机制 | 作用 | 边界 |
| --- | --- | --- |
| 前台服务（`specialUse`） | 避免进程被普通后台限制回收 | Android 14+ 必须有类型与权限；Play 上架需说明用途 |
| `PARTIAL_WAKE_LOCK` | 熄屏后保持 CPU 运行，定时器不被挂起 | 耗电；仅充电时持有，10 分钟续期一次 |
| 定时采样（非广播） | 广播投递频率不可控（可能几十秒一次） | 1 Hz 读取是轻量 binder 调用，开销可接受 |
| 电池优化白名单 | 避免被 Doze / App Standby 限制 | 需用户手动确认 |
| 厂商自启动 / 后台高耗电白名单 | 避免被 vivo / 小米 / OPPO 的省电策略冻结 | **无法通过代码绕过**，只能引导用户设置 |
| 看门狗闹钟 | 服务被杀后拉起 | Android 12+ 后台启动前台服务受限，不保证成功 |
| 断点标记 | 采集中断时写入 gap 标记（导出 CSV 中可见） | 只能「如实记录缺失」，无法补齐数据 |

补充事实：**充电时系统不会进入 Doze**（Doze 要求拔掉充电器、息屏、静止），
所以「充电中熄屏」的主要风险不是 Doze，而是 OEM 的进程冻结策略与温控降频。

---

## 9. 真机实测证据

设备：**一台 vivo 机型，Android 16（SDK 36）**，测试时正在有线充电（33%）。

### 9.1 `dumpsys battery` 输出（节选）

```
AC powered: true
USB powered: false
Wireless powered: false
Max charging current: 0
Max charging voltage: 0
Charge counter: 1683759
status: 2
level: 33
scale: 100
voltage: 3778
temperature: 370
technology: Li-ion
```

两点结论：

1. `max_charging_current` / `max_charging_voltage` 字段存在，但厂商**恒填 0**，
   所以不能把它当作可靠的协商电压来源（应用把它标为 DEGRADED）。
2. 系统只给出电池电压 3778 mV，没有任何 VBUS 信息。

### 9.2 `/sys/class/power_supply` 节点列表

```
battery  cms  display  fg_common  fuelsummary  mcu
ucsi-source-psy-soc:qcom,pmic_glink:qcom,ucsi1
usb  vfcs-cp-0  vfcs-cp-1  vfcs-fchg  vfcs-pi-i2c
wireless  wls  wls_rx
```

`vfcs-*` 是 vivo 私有闪充栈，`wls_rx` 是无线接收，`ucsi-*` 是 USB Type-C 连接器驱动。
节点确实存在——但存在不等于可读：

```
$ adb shell cat /sys/class/power_supply/battery/voltage_now
cat: /sys/class/power_supply/battery/voltage_now: Permission denied

$ adb shell cat /sys/class/power_supply/usb/current_now
cat: /sys/class/power_supply/usb/current_now: Permission denied

$ adb shell ls /sys/class/power_supply/battery/
ls: /sys/class/power_supply/battery/: Permission denied
```

注意：`adb shell` 运行在 `shell` 域，权限高于普通应用的 `untrusted_app` 域。
**shell 都被拒绝，普通应用更不可能读到**。这直接否定了「用 Shizuku 拿 shell 权限就能读」
这条常见思路（至少在同类 ROM 上不成立）。

`vfcs-fchg` 目录本身可列（含 `power`、`model_name`），但读取内容同样受 SELinux 约束，
不同 ROM 表现可能不同——应用的「可观测性自检」页会逐项实测并如实显示 BLOCKED / UNSUPPORTED。

---

### 9.3 第二台实测机：vivo 旗舰机型（Android 16 / SDK 36，型号已脱敏）

用应用内的诊断探针（`diag.txt`）枚举了普通应用能看到的全部信号，结果如下。

**可读的电池广播字段**（充电中，98–100%）：

```
voltage=4307  current_now=18000  temperature=305  level=98  charge_counter=1634000
status=2  plugged=1  health=2  technology=Li-poly  present=true
charging_speed=1  adapter_power=0  invalid_charger=0
reverse_wireless_chg_state=0  wls_otg_status=0  board_temp_status=0
android.os.extra.CYCLE_COUNT=****  android.os.extra.CHARGING_STATUS=0
soc_decimal=0  engine=0  last_mode_flag=0  window_duration=30000
```

其中 `charging_speed`、`adapter_power`、`invalid_charger`、`reverse_wireless_chg_state`、
`wls_otg_status`、`cycle_count` 是 vivo 私有字段，**这是本机型上真正的信号增量**，应用已接入：

| 字段 | 观测值 | 用途 / 说明 |
| --- | --- | --- |
| `charging_speed` | 1 | 厂商充电档位/状态枚举；语义需按机型标定 |
| `adapter_power` | 0 | 适配器上报功率原始值；高功率段是否非 0 待验证 |
| `invalid_charger` | 0 | 1 表示充电器未通过厂商校验 → 私有协议大概率未握手 |
| `reverse_wireless_chg_state` | 0 | 反向无线充电状态，比「电流为负」可靠 |
| `wls_otg_status` | 0 | 无线 OTG 状态 |
| `android.os.extra.CYCLE_COUNT` | 已脱敏 | 电池循环次数（AOSP API 34+） |

**`BatteryManager` 属性**：`getIntProperty` 的 1（库仑计）、2（电流）、4（电量）、6（状态）可用；
3（平均电流）、5（能量计数）不支持；`getLongProperty` 同样可用。
遍历 0–20 未发现更多厂商自定义 property id。

**仍不可读的部分**（普通应用域）：

```
UsbManager.getPorts() -> SecurityException: Access denied, requires: android.permission.MANAGE_USB
/sys/class/power_supply/battery/voltage_now -> EACCES
/sys/class/power_supply/usb/charge_type     -> EACCES
File("/sys/class/power_supply").list()      -> null      （目录都列不出来）
dumpsys battery (Runtime.exec)              -> Can't find service: battery
```

**应用侧闭环验证**：数据库写入正常（单次会话 640 个采样点），最新一行
`mv=4327 ua=218000 soc=99 v_speed=1 v_invalid=0 v_adapter=0 cycles=****` 与界面显示一致；
能量积分按 `mW·ms / 3.6e9 = Wh` 计算，11 分钟 0.72 W 均值对应 0.13 Wh，量纲自洽。

> 调试备注：vivo 会拦截 `adb install`（`INSTALL_FAILED_ABORTED: User rejected permissions`），
> 需亮屏解锁 + 允许「USB 安装」，并使用 `adb install -i com.android.vending` 指定来源。

## 10. 提高准确度的可行路径（按性价比排序）

| 方案 | 效果 | 代价 | 可行性 |
| --- | --- | --- | --- |
| 用户标注充电器型号 | 直接给定档位，准确率最高 | 人工一次 | ✅ 已实现 |
| 众包功率指纹库 | 用真实曲线校准各机型档位 | 需要数据积累与隐私设计 | ✅ 可扩展（CSV 已含原始字段） |
| 低 SOC 段定向记录 | 峰值特征最明显 | 使用习惯 | ✅ 已内置提示 |
| 读取 `max_charging_voltage` | 直接得到输入电压等级 | 依赖 OEM 填充 | ⚠️ 多数为 0 |
| 厂商私有 battery 字段 | vivo 可读 `charging_speed` / `invalid_charger` / `adapter_power` | 需逐机型标定语义 | ✅ 已接入并展示 |
| `UsbPort.complianceWarnings` | 判断 BC1.2 / 输入受限 | 需 `MANAGE_USB` | ❌ 普通应用不可用 |
| Shizuku（shell 权限） | 理论上可读部分 sysfs | 需用户激活 | ❌ 实测 shell 被 SELinux 拒绝 |
| Root | 可读 `usb/voltage_now`、`charge_type`、`vfcs-*` | 失去保修、门槛高 | ⚠️ 与「免 Root」目标冲突 |
| 外置 USB PD 分析仪 | 可读 VBUS/CC/PDO/协议报文，最准确 | 需要硬件（百元级） | ✅ 推荐给重度用户 |
| 厂商合作 / 系统签名 | 可读全部私有节点 | 商业门槛 | ⚠️ 不适用于个人开发 |

---

## 11. 合规与风险提示

- **前台服务类型**：使用 `specialUse` 并声明子类型说明；上架 Google Play 需在控制台填写用途，
  国内应用商店对「常驻后台」审核更严，建议侧载分发。
- **电池安全**：本应用只读，不参与充电控制（不写 sysfs、不发充电指令），不存在过充/过热风险。
- **数据隐私**：应用未声明 `INTERNET` 权限，数据仅存本机；CSV 导出由用户主动触发。
- **结论表述**：所有协议结果都标注「推断」并附可信度与依据，避免把概率结果包装成确定事实。

---

## 12. 代码与结论的对应关系

| 结论 | 代码位置 |
| --- | --- |
| 只能取电池侧电压/电流 | `core/BatterySampler.kt` |
| 电流单位/符号可能异常 | `core/CurrentCalibrator.kt` |
| 熄屏完整性 | `monitor/MonitorService.kt`（前台服务 + 唤醒锁 + gap 标记） |
| 断点续接 | `monitor/SessionTracker.kt` + `SampleStore.queryOpenSession()` |
| 协议只能推断 | `protocol/ProtocolEngine.kt` |
| 各品牌档位先验 | `protocol/ProtocolKnowledgeBase.kt` |
| 哪些信号不可读 | `protocol/CapabilityProbe.kt`（运行时实测并展示） |
| 无线只能判类型与档位 | `ProtocolModels.Transport` + `PlugType.WIRELESS` |

---

## 13. 复现实验

```bash
# 电池服务原始状态（对比应用读数）
adb shell dumpsys battery

# 电源节点是否存在
adb shell ls /sys/class/power_supply/

# 验证 SELinux 是否拦截（预期 Permission denied）
adb shell cat /sys/class/power_supply/usb/voltage_now
adb shell cat /sys/class/power_supply/battery/current_now

# 查看当前 SELinux 域（shell 为 u:r:shell:s0，应用为 u:r:untrusted_app:s0）
adb shell id
adb shell getenforce
```

将应用读数与 `dumpsys battery` 的 `voltage` / `Max charging current` 对照，即可验证采样链路；
注意 `dumpsys` 中的 `voltage` 与应用显示应完全一致（同一数据源），
若不一致说明读取的是不同字段。
