# README overview

`overview.png` is the shared hero image for the English and Chinese READMEs. It presents a large central Agent window with four smaller interfaces overlapping its outer edges.

The composition is rendered directly from `overview.html` at a viewport of 2000 × 1160 CSS pixels with a device scale factor of 2, producing a 4000 × 2320 PNG. Web and VS Code sources are captured at the same 2× pixel density; the TUI source already exceeds its displayed pixel dimensions. None of the sources need to be upscaled in the final composition.

All windows stay horizontal, with consistent corner rounding and shadows. The source screenshots are scaled and layered without redrawing their text, code or interface elements. README previews shrink the whole overview, so inspect the full-resolution image or individual source captures when reading small UI text.

Source captures used on 2026-11-16:

| Source | Content |
| --- | --- |
| `overview-sources/agent-zh-CN.png` | Current Web Agent conversation in Danque theme, with file and shell tool-call cards |
| `overview-sources/settings-zh-CN.png` | Current Web appearance settings in Vitesse theme |
| `overview-sources/image-zh-CN.png` | Current personal conversation with an image result in dark theme |
| `overview-sources/tui.png` | Native TUI rendering fixture in dark theme |
| `overview-sources/vscode.png` | Actual VS Code development host with the Harnex extension and an isolated sample project |

These screens use sample conversations and model state. They are presentation examples, not records of live model executions or benchmark results.

Open `overview.html` to inspect or adjust the layout. Keep its relative `overview-sources/` directory beside it.
