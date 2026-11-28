#!/usr/bin/env bash
set -euo pipefail

# The legacy JitPack image provides Python 3.5, which cannot run the
# pinned SPIRV-Tools generators. Keep this build-only runtime reproducible.
python_archive="$(mktemp)"
python_directory="$(mktemp -d)"
trap 'rm -f "$python_archive"; rm -rf "$python_directory"' EXIT
curl --fail --location --retry 3 \
  'https://github.com/astral-sh/python-build-standalone/releases/download/20261124/cpython-3.12.16%2B20261124-x86_64-unknown-linux-gnu-install_only_stripped.tar.gz' \
  --output "$python_archive"
printf '%s  %s\n' \
  '7e1adaea26c959d13aa1120be1ae62a1ea1bb52adcb382679fffe9217e22adff' \
  "$python_archive" | sha256sum --check --status
tar -xzf "$python_archive" -C "$python_directory"
export PATH="$python_directory/python/bin:$PATH"
export Python3_ROOT_DIR="$python_directory/python"
python3 --version

# Publishing builds the release variants of every library without compiling
# the sample applications or the unused debug native variants.
./gradlew publishToMavenLocal \
  -Pgroup="${GROUP:?JitPack GROUP is required}" \
  -Pversion="${VERSION:?JitPack VERSION is required}" \
  --console=plain
