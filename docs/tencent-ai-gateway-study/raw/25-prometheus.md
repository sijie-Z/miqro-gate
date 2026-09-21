# 135027 (doc 135027)


操作场景本文介绍如何使用 Prometheus 获取 AI 网关 的监控数据，当前支持直接关联腾讯云 Prometheus。关联 Prometheus 实例后，网关 将自动上报监控数据到已关联的 Prometheus。前置条件已购买 AI 网关实例，详情请参见 操作文档。已拥有腾讯云 Prometheus 实例。关联腾讯云 Prometheus1. 登录 微服务平台控制台 ，在左侧导航栏单击 AI 网关 > 实例列表。2. 在实例列表页面，单击需要配置的网关实例的“ID”，进入该网关实例的基本信息页面。3. 单击左侧菜单成本管理模块中的数据观测。4. 切换到 Prometheus 页签，单击关联 Prometheus 实例。5. 选择您要关联的腾讯云 Prometheus 实例（仅支持关联与实例在同一 VPC 下的 Prometheus 实例）。6. 单击确定，完成关联。注意事项开启 Prometheus 插件对于网关的数据流性能有影响，建议只为需要监控的特定的 API(Route) 开启 Prometheus 插件。在腾讯云上购买云监控 Prometheus 将产生费用，需要您自行承担。

---

# 135027 (doc 135027)

操作场景本文介绍如何使用 Prometheus 获取 AI 网关 的监控数据，当前支持直接关联腾讯云 Prometheus。关联 Prometheus 实例后，网关 将自动上报监控数据到已关联的 Prometheus。前置条件已购买 AI 网关实例，详情请参见 操作文档。已拥有腾讯云 Prometheus 实例。关联腾讯云 Prometheus1. 登录 微服务平台控制台 ，在左侧导航栏单击 AI 网关 > 实例列表。2. 在实例列表页面，单击需要配置的网关实例的“ID”，进入该网关实例的基本信息页面。3. 单击左侧菜单成本管理模块中的数据观测。4. 切换到 Prometheus 页签，单击关联 Prometheus 实例。5. 选择您要关联的腾讯云 Prometheus 实例（仅支持关联与实例在同一 VPC 下的 Prometheus 实例）。6. 单击确定，完成关联。注意事项开启 Prometheus 插件对于网关的数据流性能有影响，建议只为需要监控的特定的 API(Route) 开启 Prometheus 插件。在腾讯云上购买云监控 Prometheus 将产生费用，需要您自行承担。