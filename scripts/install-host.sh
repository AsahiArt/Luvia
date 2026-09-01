#!/bin/sh
# Install luvia-host from a GitHub release. Verifies SHA-256 before install.
# Usage: ./scripts/install-host.sh [version]
#   version: tag such as v0.1.0, or "latest" (default).
# Environment:
#   LUVIA_REPO     owner/name (default: AsahiArt/Luvia)
#   PREFIX         install prefix (default: /usr/local if writable, else ~/.local)
#   LUVIA_HOST_URL override archive URL (skips GitHub layout; checksum still required)

set -eu

die() {
    printf 'install-host: %s\n' "$*" >&2
    exit 1
}

need_cmd() {
    command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"
}

need_cmd uname
need_cmd mktemp
need_cmd tar

if command -v curl >/dev/null 2>&1; then
    download() {
        curl -fsSL "$1" -o "$2"
    }
elif command -v wget >/dev/null 2>&1; then
    download() {
        wget -q -O "$2" "$1"
    }
else
    die "need curl or wget"
fi

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    else
        die "need sha256sum or shasum"
    fi
}

os=$(uname -s)
arch=$(uname -m)
case "$os" in
    Darwin)
        asset="luvia-host-universal-apple-darwin.tar.gz"
        ;;
    Linux)
        case "$arch" in
            x86_64 | amd64)
                asset="luvia-host-x86_64-unknown-linux-gnu.tar.gz"
                ;;
            aarch64 | arm64)
                asset="luvia-host-aarch64-unknown-linux-gnu.tar.gz"
                ;;
            *)
                die "unsupported Linux architecture: $arch"
                ;;
        esac
        ;;
    *)
        die "unsupported OS: $os (luvia-host is Unix-only)"
        ;;
esac

repo="${LUVIA_REPO:-AsahiArt/Luvia}"
version="${1:-latest}"
prefix="${PREFIX:-}"
if [ -z "$prefix" ]; then
    if [ -w /usr/local/bin ] 2>/dev/null || [ "$(id -u)" -eq 0 ]; then
        prefix=/usr/local
    else
        prefix="${HOME}/.local"
    fi
fi
bindir="${prefix}/bin"

workdir=$(mktemp -d)
trap 'rm -rf "$workdir"' EXIT INT HUP TERM

archive="$workdir/$asset"
sums="$workdir/SHA256SUMS.txt"

if [ -n "${LUVIA_HOST_URL:-}" ]; then
    url="$LUVIA_HOST_URL"
    sums_url="${LUVIA_HOST_SUMS_URL:-}"
    [ -n "$sums_url" ] || die "LUVIA_HOST_URL set but LUVIA_HOST_SUMS_URL is empty; refusing to install an unverified binary"
else
    if [ "$version" = "latest" ]; then
        base="https://github.com/${repo}/releases/latest/download"
    else
        base="https://github.com/${repo}/releases/download/${version}"
    fi
    url="${base}/${asset}"
    sums_url="${base}/SHA256SUMS.txt"
fi

printf 'install-host: downloading %s\n' "$url" >&2
download "$url" "$archive" || die "failed to download $url"
download "$sums_url" "$sums" || die "failed to download checksums from $sums_url"

expected=$(awk -v f="$asset" '$2 == f || $2 == ("*" f) || $2 == ("./" f) { print $1; found=1 } END { if (!found) exit 1 }' "$sums") \
    || die "no checksum for $asset in $sums_url; refusing to install"

actual=$(sha256_of "$archive")
if [ "$expected" != "$actual" ]; then
    die "checksum mismatch for $asset
  expected: $expected
  actual:   $actual
refusing to install"
fi

printf 'install-host: checksum ok (%s)\n' "$actual" >&2

tar -C "$workdir" -xzf "$archive" || die "failed to extract $asset"
bin="$workdir/luvia-host"
[ -f "$bin" ] || die "archive did not contain luvia-host"
chmod 755 "$bin"

mkdir -p "$bindir" || die "cannot create $bindir"
dest="$bindir/luvia-host"
if [ -w "$bindir" ]; then
    cp "$bin" "$dest"
else
    need_cmd sudo
    sudo cp "$bin" "$dest"
    sudo chmod 755 "$dest"
fi

printf 'install-host: installed %s\n' "$dest" >&2
"$dest" --version
