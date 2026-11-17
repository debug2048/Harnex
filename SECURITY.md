# Security policy / 安全政策

## Reporting a vulnerability

Use [GitHub private vulnerability reporting](https://github.com/debug2048/Harnex/security/advisories/new) when the **Report a vulnerability** button is available. This channel becomes available after the repository is public and maintainers enable private reporting. An ordinary Issue is not a private security report.

If the private form is unavailable, open an Issue titled **Security contact request**, asking only for a private reporting channel. Do not include the vulnerability, affected endpoint, exploit, credentials or confidential data; wait for the maintainer to establish a private channel before sharing details.

In the private report, include the affected version and platform, impact, prerequisites, minimal reproduction and a sanitized proof of concept. Test only systems and data you are authorized to use. If a credential has been exposed, revoke it through the provider; do not include the active credential in the report.

Security fixes are prioritized for the latest released version. Older versions may require an upgrade; no response-time SLA or guaranteed backport is currently offered. Maintainers will coordinate a fix and disclosure through the private report.

## 安全漏洞报告

仓库公开并启用相应功能后，请使用 [GitHub 私密漏洞报告](https://github.com/debug2048/Harnex/security/advisories/new)（Security → Advisories → Report a vulnerability）。普通 Issue 不是私密渠道。

若私密入口不可用，请只提交标题为 **Security contact request** 的 Issue，请求私密联系方式，不附漏洞细节、受影响地址、利用代码、凭据或机密数据。待维护者建立私密渠道后，再提供版本、平台、影响范围、前置条件、最小复现和脱敏证明。

只在获得授权的环境和数据上验证。已泄露的凭据应在供应商处撤销，不要把仍有效的凭据写进报告。安全修复优先面向最新发行版，旧版可能需要升级；目前不承诺响应时限或所有旧版的回补。
