#!/usr/bin/env bash
# Write a resolved toolchain version into this plugin's manifests.
#
#   scripts/stamp-version.sh 26.8
#   scripts/stamp-version.sh 26.8 --check    # verify, change nothing
#
# JetBrains Marketplace reads `plugin.xml` long before any Saule code runs, so
# the version there cannot be resolved at build time the way the Rust crates
# resolve theirs — it has to be written down, in three places that must agree.
# IntelliJ takes the version verbatim, so it is `<year>.<build>`.
#
# `--check` is what CI runs on a pull request: it turns "somebody edited one
# manifest and forgot the other" into a failed job instead of a Marketplace
# listing that disagrees with `saule --version`.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

version="${1:-}"
mode="${2:-write}"

if [ -z "$version" ]; then
    echo "usage: $(basename "$0") <version> [--check]" >&2
    echo "       e.g. $(basename "$0") 26.8" >&2
    exit 2
fi

if ! printf '%s' "$version" | grep -Eq '^[0-9]+\.[0-9]+$'; then
    echo "error: \`$version\` is not a <year>.<build> version (e.g. 26.8)" >&2
    exit 1
fi

status=0

# Replace `pattern` with `replacement` in `file`, or in --check mode report
# whether the expected text is already there.
#
# `expected` is a plain substring searched for verbatim in check mode. It is
# a separate argument rather than reusing `replacement` because a replacement
# may carry regex escapes — `\n` to anchor to the start of a line, say — which
# a literal search would look for as a backslash followed by an `n`.
stamp() {
    local file="$1" label="$2" pattern="$3" replacement="$4" expected="${5:-$4}"
    local path="$ROOT/$file"

    if [ ! -f "$path" ]; then
        echo "error: $file not found" >&2
        status=1
        return
    fi

    if [ "$mode" = "--check" ]; then
        # Passed through the environment, not argv: under `-n` perl treats
        # every argument as a filename to read.
        if EXPECTED="$expected" perl -0777 -ne \
            'exit(index($_, $ENV{EXPECTED}) >= 0 ? 0 : 1)' "$path"; then
            echo "  ok      $file ($label)"
        else
            echo "  STALE   $file ($label) — expected $label $version" >&2
            status=1
        fi
        return
    fi

    perl -0777 -pi -e "s{$pattern}{$replacement}g" "$path"
    echo "  stamped $file ($label)"
}

if [ "$mode" = "--check" ]; then
    echo "Checking plugin manifests against $version…"
else
    echo "Stamping $version into plugin manifests…"
fi

stamp gradle.properties "plugin version" \
    'pluginVersion = [0-9][^\s]*' "pluginVersion = $version"

stamp src/main/resources/META-INF/plugin.xml "plugin.xml version" \
    '<version>[0-9][^<]*</version>' "<version>$version</version>"

# The new-project wizard writes `min_saule_version` into the saule.config it
# scaffolds, so it has to know the toolchain version too.
stamp src/main/kotlin/com/saule/lang/project/SauleProjectScaffolder.kt \
    "scaffolded min_saule_version" \
    'const val SAULE_VERSION = "[0-9][^"]*"' "const val SAULE_VERSION = \"$version\""

if [ "$status" -ne 0 ] && [ "$mode" = "--check" ]; then
    echo >&2
    echo "Run: scripts/stamp-version.sh $version" >&2
fi

exit "$status"
