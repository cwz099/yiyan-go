# 本地终局引擎

KataGo 1.18.1（Windows x64 / Eigen CPU），无需显卡驱动、账号或 API Key。

运行 `powershell -ExecutionPolicy Bypass -File .\install-katago.ps1` 下载官方程序和 b18c384 模型。引擎压缩包使用 GitHub 发布的 SHA-256 校验；模型由 KataGo 官方训练站提供。Windows 打包脚本将整个 `engine/katago` 目录一起打包。

- 程序与许可证：https://github.com/lightvector/KataGo/releases/tag/v1.18.1
- 模型：https://katagotraining.org/networks/
- 接口：https://github.com/lightvector/KataGo/blob/v1.18.1/docs/GTP_Extensions.md

程序调用 GTP `final_status_list dead` 判断死子，再按中国面积规则计数；同时用 `final_score` 复核。两者不一致时保留待结算状态，不把局面估值冒充已确认结果。
