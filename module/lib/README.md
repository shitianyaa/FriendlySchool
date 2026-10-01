# 编译期依赖

`api-102.jar` 原样取自 Maven Central 发布的 `io.github.libxposed:api:102.0.0` AAR 内的 `classes.jar`。构建脚本仅将其用于 javac 的 classpath 和 d8 的 `--lib`，不将 API 类打入模块 dex。

- 上游项目：https://github.com/libxposed/api
- 原始制品：https://repo.maven.apache.org/maven2/io/github/libxposed/api/102.0.0/api-102.0.0.aar
- SHA-256：`a515dd7a53cd7a47c05e101dff77d61acb3091a97b20a885b9ea3494412db985`
- 许可证：Apache License 2.0（发布 POM 的声明）；全文见 [LICENSE-libxposed.txt](LICENSE-libxposed.txt)。本项目的 MIT 许可证不覆盖该依赖。

2026-10-01 从 Maven Central 重新下载 AAR 比对，`classes.jar` 与本地编译桩的 SHA-256 一致。
