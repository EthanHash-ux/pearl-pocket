# PearlFortune 矿工监控与桌面组件

0.8.0 增加 PearlFortune 原生矿工页面和「算力与价格」安卓桌面小组件。既有 HeroMiners 监控和价格组件继续保留。

## 使用

打开 **挖矿 → PearlFortune 我的矿工 → 添加 PearlFortune 矿工**，填写矿机使用的公开 PRL 收款地址和名称。详情显示 1 / 8 / 24 小时矿池估算贡献、接受与拒绝计数、接受率、矿池报告的在线连接，以及每个 worker 的名称、GPU 数量、自报算力和自报时间。

点击 **添加算力与价格小组件**，在系统弹窗确认添加。也可长按桌面，从掌珠钱包的小组件列表选择「PearlFortune · 算力与价格」，配置矿池总览或个人地址。多个组件可绑定不同矿工；点「选择矿工」可重新配置。删除保存的矿工地址不会悄悄改变已经绑定的组件。

组件显示 BigONE PRL/USDT、24 小时涨跌、1 / 8 / 24 小时算力、连接状态和分别对应的更新时间。点击组件进入绑定矿工的原生详情。详情中的「查看 PearlFortune 完整页面」打开官网 `https://pearlfortune.org/#miner=<PRL-address>`，保留完整网页的收益计算、结算、账本和 NOCK 合并挖矿查看功能。

## 数据的含义

- **矿池估算**根据接受贡献计算；读取 `share_statistics.effective_hashrate`。不把缺失值或不完整窗口替换成零，也不回退到可能含义不同的 `hashrate`。
- 沿用响应 `hashrate_str` 的单位维度。支持 H/s 和 MAC/s 的倍率前缀；MAC/s 不换算成 H/s。未识别单位拒绝显示。
- **矿机自报**读取连接接口的 `reported_hashrate`，按照网站连接面板使用 H/s 显示。自报和矿池估算分别显示。
- 接受与拒绝计数使用整数，避免超过 JavaScript 安全整数范围后失真。缺少样本、丢失事件或窗口不完整时，接受率为未知。
- 在线连接是矿池报告的会话数，不等于独立矿机数；连接存在和自报数据是否及时分别呈现。超过 5 分钟的自报显示为旧数据，不据此断言矿机离线。
- 对无挖矿记录的公开测试地址，完整观测到的零贡献可以显示零；不据此推断未知账户余额为零。本次原生页面没有实现矿池提现或账本金额。

## 刷新、边界与隐私

JobScheduler 每 15 分钟尝试获取公开数据；安卓省电、网络及后台限制可延后执行，不保证秒级实时。点击刷新请求一次后台查询；应用前台接收到的 BigONE 行情也会同步到组件。价格和算力独立标注缓存，失败保留最后成功的数据；连接缓存不当成当前在线状态。

每个组件保存自己的公开名称、地址、配置版本及脱敏统计。切换地址、停止后台任务和删除组件时，旧请求不能覆盖新配置。多个组件查询相同地址时复用本轮结果；删除最后一个组件后取消更新任务。

查询会把公开 PRL 地址发给 PearlFortune；不读取私钥、助记词、密码或钱包解锁密钥。矿工监控地址独立于手机签名地址和钱包观察模式。缓存不包含 worker 的远程 IP 或 session ID。桌面会显示价格、矿工名称及状态，适合用户明确选择公开显示的数据。

官网当前在响应中遮盖地址：原生查询校验响应地址等于所请求地址，或等于其首 10 位、`...`、末 8 位的官网格式。遮盖地址不能提供完整的独立身份核验，数据真实性仍依赖该 HTTPS 服务。所有新网络访问限定 `pearlfortune.org` 的 HTTPS GET，禁用自动重定向，不增加签名或广播接口。

组件系统配置入口只接受已分配给该组件提供者的 ID。应用内固定配置后请求系统添加；添加回调使用显式、唯一、一次性的 PendingIntent，允许系统填入新组件 ID，并校验该 ID 的提供者及本地公开配置。普通点击和刷新 PendingIntent 为不可变。

## 核验来源

- [PearlFortune 矿工页面](https://pearlfortune.org/#miner)
- [PearlFortune 网站客户端](https://pearlfortune.org/static/app.js?v=20261005-settlement-header-1)：路由、API、滚动窗口和连接字段。
- [公开配置](https://pearlfortune.org/api/v1/config)、[公开矿池统计](https://pearlfortune.org/api/v1/summary?hours=24)。个人地址接口为 `/api/v1/miners/{address}?hours=24` 及其 `/connections`。
- [Android 组件配置](https://developer.android.com/develop/ui/views/appwidgets/configuration)、[添加到桌面](https://developer.android.com/develop/ui/views/appwidgets/discoverability)、[AppWidgetManager](https://developer.android.com/reference/android/appwidget/AppWidgetManager)。

验证使用模拟器及公开零熵测试向量地址。发布截图使用矿池总览，不含用户挖矿地址。
