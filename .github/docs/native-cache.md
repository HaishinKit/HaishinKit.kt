# Native compiler cache

Android CI and Release use ccache for the C and C++ compilers invoked by CMake,
including Vulkan/SPIRV-Tools, RTC/libdatachannel, SRT, and host native tests.
The existing Gradle dependency cache remains separate.

- Unchanged compilation inputs reuse cached object files. Source/header changes,
  compiler flags, ABI, build mode, or compiler contents produce a different result
  key and compile again. Compiler checks use content, not timestamps.
- CMake configuration, dependency fetching, linking, packaging, and tests still run.
  This does not skip a module based solely on a Git diff or reuse a complete APK.
- Cold/evicted caches build normally. No correctness-related ccache `sloppiness`
  settings are enabled. Compiler storage is limited to 2 GiB per cache entry.
- Each workflow reports cache hits and misses in its job summary. Measure the
  second build against the cold build before claiming a particular speedup.

## Sharing between pull requests

GitHub scopes caches to a branch or PR. PRs can read `main` caches, but one PR's
cache is not shared with another PR. `Warm native compiler cache` therefore builds
Debug and Release native libraries on `main` when native inputs or build settings
change. Kotlin/UI-only changes do not trigger this extra workflow. It can also be
run manually on `main` after cache eviction.

The storage key fingerprints tracked native sources, dependency pins/patches,
submodule gitlinks, and build settings. A source change can restore the previous
compiler cache as a starting point; ccache independently validates each compile
and rebuilds changed inputs. Native test sources are also fingerprinted. Runner
OS, architecture, and image are separate cache namespaces. PR and tag runs retain
GitHub's default cache isolation and never write into `main`'s scope.

Only compiler output is cached. `.cxx` trees, absolute-path CMake build directories,
signing material, sample captures, APKs, and test reports are not included.

## References

- [ccache input validation](https://ccache.dev/manual/latest.html#_how_ccache_works)
- [CMake compiler launcher environment](https://cmake.org/cmake/help/latest/envvar/CMAKE_LANG_COMPILER_LAUNCHER.html)
- [GitHub cache scope](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching#restrictions-for-accessing-a-cache)
