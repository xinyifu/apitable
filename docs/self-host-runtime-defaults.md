# 本地自部署运行默认值

本文记录本地自部署 fork 中和运行时默认行为相关的修改，避免和社区版限制、权限管理、小程序资源加载问题混在一起。

## 默认语言改为中文

本地自部署 fork 仅针对自部署场景，默认运行语言改为中文。

### 修改位置

前端默认语言：

```text
packages/datasheet/.env
  SYSTEM_CONFIGURATION_DEFAULT_LANGUAGE=zh_CN
```

`packages/datasheet/utils/get_initial_props.ts` 中的映射表使用 `zh_CN -> zh-CN`，因此 `.env` 中保留下划线格式。

widget-sdk 默认语言兜底：

```text
packages/widget-sdk/src/utils/language.ts
  defaultLang = LangType.ZhCN

packages/widget-sdk/src/utils/i18n.ts
  defaultLang = 'zh_CN'
```

这层兜底用于发布版小程序或其他 widget-sdk 调用路径没有显式 `window.__initialization_data__.lang` 时，避免继续因为 `IS_APITABLE=true` 回退到英文。

room-server 默认语言：

```text
packages/room-server/src/app.environment.ts
  defaultLanguage = process.env.DEFAULT_LANGUAGE || 'zh-CN'
```

这里使用运行时语言格式 `zh-CN`。

### 覆盖关系

该修改只调整无显式配置时的默认值。以下来源仍会覆盖默认语言：

- 用户已经保存的语言偏好。
- 浏览器侧 `localStorage` 中的 `client-lang`。
- 登录后后端返回的 `userInfo.locale`。
- 部署环境显式设置的 `DEFAULT_LANGUAGE` 或 `SYSTEM_CONFIGURATION_DEFAULT_LANGUAGE`。

### 相关的小程序语言修复

发布版小程序还有独立的 widget-sdk 语言读取逻辑。展开态非 iframe 小程序需要显式同步主应用语言到：

```text
window.__initialization_data__.lang
```

该部分记录在：

```text
docs/self-host-widget-loading.md
```
