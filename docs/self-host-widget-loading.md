# 本地自部署小程序加载问题

本文记录本地自部署源码运行时，小程序发布包资源加载失败的问题定位和修复方式。

这个问题不是社区版功能限制，也不是权限管理问题。它属于本地开发拓扑下的静态资源访问路径问题。

## 问题现象

在本地直接运行前端 `http://127.0.0.1:3000` 后，进入仪表盘并新建官方小程序 `Chart`，页面出现 Next.js Runtime Error：

```text
SyntaxError: Unexpected token '<'
```

页面当时显示：

```text
widgetPackageName: Chart
widgetPackageId: wpkCKtqGTjzM7
```

后续修复过程中，错误从 Runtime Error 变成小程序卡片内的 `LoadError`，说明脚本路径已经不再返回 HTML，但仍没有从正确的对象存储地址加载成功。

## 根因

小程序发布包的 `release_code_bundle` 在数据库中是相对对象存储路径：

```text
space/2023/11/14/fddc19c169eb468690b11cfc3787f2a4
```

原加载逻辑直接把该值交给 `loadjs`，浏览器会按当前页面路径解析成：

```text
http://127.0.0.1:3000/workbench/space/2023/11/14/fddc19c169eb468690b11cfc3787f2a4
```

这个地址由 Next.js 前端服务处理，返回的是 HTML 页面，不是 JavaScript，所以浏览器解析脚本时遇到 HTML 起始符 `<`，触发：

```text
SyntaxError: Unexpected token '<'
```

进一步把路径拼成 `/assets/space/...` 后，在完整 docker compose 网关模式下通常应由 gateway 转发到对象存储；但本地源码直跑前端时，`127.0.0.1:3000` 没有该转发规则：

```text
http://127.0.0.1:3000/assets/space/...  -> 404
http://127.0.0.1:9000/assets/space/...  -> 200 text/javascript
```

因此本地直跑前端需要把发布版小程序包加载地址指向本地 MinIO。

## 相关代码节点

主要修复文件：

```text
packages/datasheet/src/widget-stage/main/widget/widget_loader.tsx
```

关键函数：

```text
getReleaseBundleUrl
getLocalSelfHostReleaseBundleUrl
getLoadErrorCode
```

相关配置：

```text
packages/datasheet/.env
  QNY1=/assets/
  IS_SELFHOST=true

docker-compose.dataenv.yaml
  minio:
    ports:
      - "9000:9000"

.env
  ASSETS_BUCKET=assets
  ASSETS_URL=assets
  AWS_ENDPOINT=http://minio:9000
```

## 解决方式

修复策略保持范围尽量小：

1. 发布版小程序包不再直接使用 `releaseCodeBundle` 的相对路径。
2. 普通环境继续使用 `integrateCdnHost(releaseCodeBundle)` 拼接资源地址。
3. 仅在以下条件同时满足时，将地址切到本地 MinIO：
   - `process.env.NODE_ENV === 'development'`
   - `env.IS_SELFHOST === true`
   - `env.QNY1` 是 `/assets/` 这类相对路径
   - 当前前端运行在 `127.0.0.1` 或 `localhost`
4. 满足本地直跑条件时，发布包脚本地址拼为：

```text
http://127.0.0.1:9000/assets/space/2023/11/14/fddc19c169eb468690b11cfc3787f2a4
```

同时，发布版小程序加载失败时，卡片内增加错误码反馈：

```text
Error: LoadError
Error: PackageIdNotMatch
```

这样前端不会只显示泛化错误，也不会直接弹出 Next.js Runtime Error 遮罩。

## 验证记录

本地验证环境：

```text
前端: http://127.0.0.1:3000
MinIO: http://127.0.0.1:9000
仪表盘: /workbench/dsbYzBvckCdgfrA3q1
小程序: Chart / wpkCKtqGTjzM7
```

资源响应验证：

```text
http://127.0.0.1:3000/assets/space/2023/11/14/fddc19c169eb468690b11cfc3787f2a4
  -> 404

http://127.0.0.1:9000/assets/space/2023/11/14/fddc19c169eb468690b11cfc3787f2a4
  -> 200 text/javascript
```

代码检查：

```bash
pnpm --filter @apitable/datasheet check
git diff --check
```

浏览器验证：

- 页面不再出现 `Unhandled Runtime Error`。
- 页面不再出现 `SyntaxError: Unexpected token '<'`。
- 小程序卡片不再显示 `运行小程序时出错`。
- Chart 小程序成功渲染为图表。
- 实际脚本加载地址为 `http://127.0.0.1:9000/assets/...`。

## 小程序语言不匹配记录

### 问题现象

Chart 小程序已经可以正常加载后，展开配置面板时出现主应用为中文、小程序配置项为英文的混用：

```text
Chart settings
Function settings
Select a view as data source
Column Chart
Show empty values
```

同一页面中的工作台、仪表盘、左侧导航等主应用文案仍然是中文。

### 根因

展开态小程序走非 iframe 渲染路径：

```text
packages/datasheet/src/pc/components/widget/widget_panel/widget_item/widget_block_main.tsx
```

该路径原来直接使用 `@apitable/widget-sdk` 的 `getLanguage()`：

```text
packages/widget-sdk/src/utils/language.ts
packages/widget-sdk/src/utils/i18n.ts
```

widget-sdk 在没有显式 `window.__initialization_data__.lang` 时，会根据 `IS_APITABLE` 回退默认语言。本地自部署源码运行时 `.env` 中 `IS_APITABLE=true`，因此 Chart 发布包内部调用 `t()` 时回退到英文。

iframe 小程序路径会通过 URL query 传入 `lang` 并写入 `window.__initialization_data__.lang`，但展开态非 iframe 路径没有同步这个值，所以出现主应用中文、小程序英文。

### 解决方式

修复文件：

```text
packages/datasheet/src/pc/components/widget/widget_panel/widget_item/widget_block_main.tsx
```

处理方式：

1. 非 iframe 小程序路径改用 `@apitable/core` 的 `getLanguage()` 获取主应用语言。
2. 渲染 `WidgetProvider` 前同步写入：

```text
window.__initialization_data__.lang = locale
```

3. `WidgetProvider` 的 `locale` 使用同一个 `locale`，保证小程序 Provider 和 widget-sdk 全局语言一致。

### 验证记录

验证页面：

```text
http://127.0.0.1:3000/workbench/dsbYzBvckCdgfrA3q1/wdtyxioerwAettL4yx
```

验证结果：

- Chart 小程序正常渲染图表。
- 展开配置面板后显示中文：`图表配置`、`功能配置`、`选择图表类型`、`柱状图`、`统计记录总数`。
- 页面没有出现 Next.js `Unhandled Runtime Error`。
- 开发态控制台仍有项目原有 hydration、antd deprecated、React `javascript:void(0)` warning，和本次语言问题无关。

## 后续注意

该修复针对本地源码直跑前端的自部署开发场景。完整 docker compose + gateway 部署中，`/assets/...` 通常应由网关转发，不一定需要切到 `9000`。

如果后续本地 MinIO 暴露端口不再是 `9000`，需要同步调整 `getLocalSelfHostReleaseBundleUrl`，或者新增一个显式前端环境变量来配置本地对象存储公开地址。
