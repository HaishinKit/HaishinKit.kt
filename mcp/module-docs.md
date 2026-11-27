# Module mcp

HaishinKit MCP is an optional Android API 26+ library exposing application-approved
composition tools through MCP Streamable HTTP. It uses the official MCP Kotlin SDK
0.8.4 and Ktor Netty 3.2.3. Neither dependency is added to the core media library. Netty uses API 26 method-handle APIs during initialization; only this optional
module requires API 26. The
core library retains API 23 support.

## Lifecycle and capture

Create one server per listening port. Explicitly start and stop it from an owned
coroutine scope; failures are propagated to the caller. The library does not create
an Android Service, start cameras, request permissions, log credentials, or own a
MediaMixer. Applications may include this module in release builds.

```kotlin
val server = McpServer()
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

Schedule `attachScreen` / `detachScreen` and disposal coordination on an independent
lifecycle/controller thread, such as the main thread in the sample. Never call them
inline from a Screen rendering/readback callback or on a worker that synchronous
`readPixels` is waiting for. In particular, post a requested change out of Vulkan's
`onEnterFrame` callback and return before the controller performs it; otherwise
capture and the render worker can deadlock. The timeout cannot break that lock cycle.

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
Each callback transfers ownership of a distinct caller-owned Bitmap to MCP, which
recycles it after use or cancellation. Custom Screens must not pass a Bitmap still
used for display or retain/reuse it after the callback. The built-in renderers
already allocate a separate Bitmap for each readback.

`hello_android` remains available for connectivity checks and returns
`Hello from HaishinKit on Android!`.

## Application-owned tools

The constructor's `configureTools` lambda uses the official SDK `Server` API:

```kotlin
val server = McpServer {
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


## Android AI chat sample

The Android sample includes **Publish → AI chat** in debug and release builds.
Keep Publish open, grant camera access, then choose **Continue with ChatGPT**.
Complete approval in the system browser and return to HaishinKit. The model picker
loads the selected account's current model catalog. Ask, for example, “What product
is in the camera view?” No streaming destination or active broadcast is needed.

This is an on-device, open-source demonstration of
[Sign in with ChatGPT](https://developers.openai.com/siwc/token-sharing-open-source),
subject to OpenAI's current account eligibility and preview availability. It uses
ChatGPT plan usage rather than a separately configured API key. Commercial/hosted
integrations must check OpenAI's applicable onboarding requirements instead of
reusing this sample's local registration flow.

### Authentication and storage

`ChatGptAuth` starts a temporary IPv4 loopback callback listener before opening
the system browser. Each attempt has fresh state, nonce and S256 PKCE values.
The issued client ID is saved before code exchange, then reused for that account
and workspace. ID-token signature, issuer, audience, expiry and nonce are checked
against OpenAI's OIDC metadata/JWKS before an account becomes active. Account and
workspace registrations are separate choices even if their email labels match.
The suffix shown in the account picker distinguishes their issued client IDs.

Credentials and a stable installation host ID are stored as one AES-GCM encrypted
atomic record in Android's no-backup directory; the key remains in AndroidKeyStore.
Refreshes are serialized and rotated credentials are saved atomically. Sign out
revokes the refresh token when reachable and clears local tokens. If remote
revocation cannot be confirmed, the UI says so and links to ChatGPT usage settings.
No client secret, shared Codex registration, credential logging, or credential
export is used. Interrupted or expired browser sign-in can be retried with the
saved registration. Process death during a pending sign-in requires a new attempt.

### Local MCP and image approval

`LocalMcpBridge` connects the official MCP Kotlin client to an application-owned
`McpServer` at `127.0.0.1:8081`. Its random bearer token stays on the device and is
never included in an OpenAI request. The existing debug endpoint uses another port.
Only `hello_android` and `capture_frame` are exposed to the model. The sample maps
these MCP schemas into a function namespace and executes requested tools locally;
it does not expose the local endpoint as a hosted OpenAI MCP connector.

A capture request displays a confirmation **before** reading camera pixels.
Declining returns a refusal to the model without reading or sending an image.
Approval captures a single JPEG of the composition and sends it to OpenAI with the
next model request. Images may include people, text and private information visible
in the scene. This is still-image analysis, not continuous audio/video transmission.

Chat history and approved images remain in memory for at most six completed turns.
Later requests in the same chat may resend these images as context. **New chat**,
account changes and successful sign-out clear them from future requests. These
controls do not recall data already sent to OpenAI. Closing the sheet or backgrounding
the app cancels inference and closes its MCP connection; reopening the sheet can
resume the in-memory chat. Browser authentication is allowed to finish while the
browser is in front. The Screen is detached before its mixer stops or is disposed.

Requests use streaming Responses with `store: false`, explicit context and encrypted
reasoning continuity. A response is committed only after `response.completed`;
interrupted streams, incomplete responses and usage-limit failures remain failures.
Model changes require a new chat. The sample limits tool rounds and does not retry
inference automatically. See OpenAI's
[preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)
for supported inputs and tools. `store: false` is an API conversation-storage setting,
not a promise of zero retention or a replacement for OpenAI's data policies.

### Validation

Host tests exercise PKCE, signed identity checks, callback rejection, streamed
terminal events, tool allowlisting, declined capture and image/context handling:

```sh
./gradlew :app:testDebugUnitTest :app:testUiTestUnitTest
```

Device tests use a synthetic blue composition, with no camera or OpenAI account:

```sh
./gradlew :app:connectedUiTestAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.haishinkit.app.chatgpt.LocalMcpBridgeTest,com.haishinkit.app.chatgpt.AiChatContentTest,com.haishinkit.app.chatgpt.ChatGptAuthTest
```

A real account is still required to verify browser authorization, refresh/revocation,
account-specific model availability, usage limits and live visual answers end to end.
For a manual check, sign in, ask about the composition, decline the first image request,
then ask again and approve. Check **New chat**, background/resume and **Sign out** too.
Never put real tokens, callback URLs, camera images or account details into test logs.
