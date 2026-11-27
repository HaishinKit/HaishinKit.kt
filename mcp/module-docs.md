# Module mcp

HaishinKit MCP is an optional Android API 24+ library exposing application-approved
composition tools through MCP Streamable HTTP. It uses the official MCP Kotlin SDK
0.8.4 and Ktor Netty 3.2.3. Neither dependency is added to the core media library. Netty uses API 24 socket
and concurrent-collection APIs; only this optional module requires API 24. The
core library retains API 23 support.

## Lifecycle and capture

Create one server per listening port. Explicitly start and stop it from an owned
coroutine scope; failures are propagated to the caller. The library does not create
an Android Service, start cameras, request permissions, log credentials, or own a
MediaMixer. Applications may include this module in release builds.

```kotlin
val server = HaishinKitMcpServer()
// The application obtains camera permission and starts its own mixer.
server.attachScreen(mixer.screen)
try {
    server.start() // suspend; opens 127.0.0.1:8080/mcp off the UI thread
    kotlinx.coroutines.awaitCancellation() // keep this single owner's endpoint active
} finally {
    // Detach before stopping/disposal of this screen or mixer.
    server.detachScreen(mixer.screen)
    server.stop() // suspend; idempotent, also runs from a cancelled coroutine
}
```

Put the call to `start()` itself inside the `try`: the server has an independent
lifetime, so cancellation during blocking startup or while its successful result
is returning to the caller can report cancellation after the endpoint has opened.
`stop()` performs cleanup even from a cancelled coroutine. The example assumes
one lifecycle owner; coordinate shared owners in an application-owned controller.

Keep the server and mixer in an appropriate application-owned controller rather
than allocating them repeatedly during Compose recomposition. `stop()` retains the
attached Screen for a later restart; always detach when that Screen is no longer
approved for capture. Do not dispose a Screen while it is still attached. Separate
server instances have independent capture state and credentials.

The built-in `capture_frame` tool accepts optional integer `maxWidth` (32–1920,
default 1280) and `quality` (1–100, default 85). It returns an MCP `image/jpeg`
image block and structured metadata: `width`, `height`, `renderer`, and
`readbackCompletedAtMillis`. `renderer` is the Screen implementation's simple class
name, for diagnostics only; applications must not depend on a particular value.
The whole composition is returned, including ScreenObjects and excluding preview
controls/cropping. It is the currently available composition, not a guaranteed
camera exposure after the request. The timestamp marks readback completion.

Readback requests are serialized per server and have a three-second asynchronous
wait/queue timeout. Scaling and JPEG compression run off the UI/rendering threads.
Synchronous GPU readback and compression cannot be forcibly interrupted. Detached
or cancelled captures discard results and release owned Bitmaps, including late
callbacks. Capture failures are tool results with `isError: true`. A Screen's own
readPixels implementation must deliver exactly one callback or throw on initiation.

`hello_android` remains available for connectivity checks and returns
`Hello from HaishinKit on Android!`.

## Application-owned tools

The constructor's `configureTools` lambda uses the official SDK `Server` API:

```kotlin
val server = HaishinKitMcpServer {
    addTool(name = "get_scene_name", description = "Read the current scene name") {
        // Example: use your own controller and its synchronization policy.
        CallToolResult(content = listOf(TextContent("Main scene")))
    }
}
```

Import `CallToolResult` and `TextContent` from
`io.modelcontextprotocol.kotlin.sdk.types`. The lambda is invoked for each stateless
request; register handlers there without mutating application state. Perform the
operation inside the handler. The built-in names `hello_android` and `capture_frame`
are reserved. Register unique additional names and use SDK schemas/annotations.

Future MediaMixer and ScreenObject editing tools can be registered here without
modifying the server transport. The application decides which operations to expose,
validates arguments/authorization, and dispatches mutations to the thread required
by its controller (for example `withContext(Dispatchers.Main)`). The server provides
no unrestricted object lookup, camera start, or automatic editing permission.

## Authentication and connection

The only listening address is IPv4 loopback. Use adb port forwarding for local
clients; direct LAN/browser connections and TLS termination are outside this API.

```sh
adb forward tcp:8080 tcp:8080
```

Set `Authorization: Bearer <server.configuration.bearerToken>` on every MCP request.
Securely share the instance-specific token through your application's UI or trusted
configuration; the library never logs it. The sample's debug adapter logs its token
for developer convenience. A custom `McpServerConfiguration` can change the port or
supply a token (32–256 URL-safe ASCII characters). A configuration object does not
print its credential through `toString()`.

Unexpected Host headers and all Origin headers are rejected. If forwarding to a
different local port, send a Host header matching the configured device port.
The SDK uses stateless JSON responses; no session ID is required. Keep the endpoint
opt-in and stop/detach it when the user revokes access. Token authentication covers
custom tools too; per-tool user consent remains an application responsibility.

## Android packaging and distribution

The AAR contributes INTERNET permission and Netty/R8 consumer rules. It does not
add camera/microphone permissions beyond those already in the core dependency.
Consuming APKs must exclude `META-INF/INDEX.LIST` and merge
`META-INF/io.netty.versions.properties` in `android.packaging.resources`, as the
sample does. These Gradle packaging choices cannot be delivered via consumer rules.

The release AAR and sources JAR are published through this repository's existing
Maven/JitPack multi-module build as artifact `mcp`. This is a new module: older
release tags do not contain it. Validate the intended release tag before using
`com.github.HaishinKit.HaishinKit~kt:mcp:<version>`; keep core and MCP versions equal.
