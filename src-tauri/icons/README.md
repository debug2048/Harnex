# 图标 / Icons

`tauri build` 会把这些图标编译进可执行文件与安装包（`tauri.conf.json` 的 `bundle.icon` 引用）。

## 生成方式

统一图形源为 [`docs/assets/brand/symbol.svg`](../../docs/assets/brand/symbol.svg)。在仓库根目录运行：

```bash
npm run brand:generate
```

该命令同步生成 1024×1024 的 `source.png`、`32x32.png` / `128x128.png` / `128x128@2x.png` / `icon.ico` / `icon.icns` 等，同时更新 Web、VS Code 和快捷方式图标。生成工具和参数见[品牌构建脚本](../../scripts/build/brand.mjs)。

## 说明

- `source.png` 纳入版本控制；本目录其余生成的二进制图标**不入库**（见 `../.gitignore`），按需从它重新生成；
- `node scripts/build/desktop.mjs` 会在目标图标缺失或 `source.png` 更新时调用 Tauri 重新生成；
- 没有图标时 `tauri build` 会失败——`node scripts/build/desktop.mjs --check` 会提前提示。
