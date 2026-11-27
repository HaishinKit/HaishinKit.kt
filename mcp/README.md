# 🤖 HaishinKit MCP for Android

[![Android CI](https://github.com/HaishinKit/HaishinKit.kt/actions/workflows/android.yml/badge.svg?branch=main)](https://github.com/HaishinKit/HaishinKit.kt/actions/workflows/android.yml)
[![API 26+](https://img.shields.io/badge/Android-API%2026%2B-3DDC84)](#-installation)
[![BSD-3-Clause](https://img.shields.io/badge/License-BSD--3--Clause-blue.svg)](../LICENSE.md)

Optional MCP Streamable HTTP server for Android compositions and application-owned tools.

## ✨ Features

- Authenticated, opt-in loopback server using the official MCP Kotlin SDK and Ktor.
- JPEG capture of an explicitly attached Screen, including its ScreenObjects.
- An extension point for application-approved read and edit tools.

## 📦 Installation

This module is new and has no published release tag yet. After it lands on `main`,
use the development reference below with JitPack; replace it with a published tag
containing `mcp` for production. Keep all HaishinKit modules on the same version.

```kotlin
// settings.gradle.kts: repositories { google(); mavenCentral(); maven { url = uri("https://jitpack.io") } }
dependencies {
    implementation("com.github.HaishinKit.HaishinKit~kt:mcp:main-SNAPSHOT")
}
```

For this source checkout, use `implementation(project(":mcp"))`.

## 📚 Documentation

Lifecycle, authentication, custom tools and APK packaging are described in the
[Dokka module guide](module-docs.md), included in generated API documentation.
The [public reference](https://docs.haishinkit.com/kt/latest/) tracks published documentation
and will contain this module after the next documentation release.

## 🎬 Examples

The [Android sample](../app/README.md) opts into this library in debug builds.
Applications can choose to include it in release builds and supply their own lifecycle.
