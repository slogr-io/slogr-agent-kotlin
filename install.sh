#!/bin/sh
# Slogr Agent — universal shell installer
# Usage: curl -fsSL https://releases.slogr.io/install.sh | sh
# Or:    SLOGR_API_KEY=sk_live_... curl -fsSL https://releases.slogr.io/install.sh | sh
set -e

SLOGR_VERSION="${SLOGR_VERSION:-1.0.4}"
RELEASES_BASE="https://github.com/slogr-io/slogr-agent-kotlin/releases/download/v${SLOGR_VERSION}"

# ── Helpers ──────────────────────────────────────────────────────────────────

info()  { printf '\033[0;32m[slogr] %s\033[0m\n' "$*"; }
warn()  { printf '\033[0;33m[slogr] WARNING: %s\033[0m\n' "$*"; }
error() { printf '\033[0;31m[slogr] ERROR: %s\033[0m\n' "$*" >&2; exit 1; }

need_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    error "Required command '$1' not found. Please install it and retry."
  fi
}

# ── OS and architecture detection ────────────────────────────────────────────

detect_os() {
  case "$(uname -s)" in
    Linux*)  echo "linux"  ;;
    Darwin*) echo "macos"  ;;
    *)       error "Unsupported OS: $(uname -s). Only Linux and macOS are supported." ;;
  esac
}

detect_arch() {
  case "$(uname -m)" in
    x86_64|amd64)  echo "amd64"  ;;
    aarch64|arm64) echo "arm64"  ;;
    *)             error "Unsupported architecture: $(uname -m)." ;;
  esac
}

# ── Package manager detection ─────────────────────────────────────────────────

detect_pkg_manager() {
  if command -v brew >/dev/null 2>&1; then
    echo "brew"
  elif command -v apt-get >/dev/null 2>&1; then
    echo "apt"
  elif command -v yum >/dev/null 2>&1 || command -v dnf >/dev/null 2>&1; then
    echo "yum"
  else
    echo "none"
  fi
}

# ── Install via package manager ───────────────────────────────────────────────

install_brew() {
  info "Installing via Homebrew..."
  brew tap slogr/tap
  brew install slogr-agent
}

install_apt() {
  info "Installing via apt..."
  need_cmd curl
  # Add Packagecloud apt repository
  curl -fsSL "https://packagecloud.io/slogr/agent/gpgkey" | sudo apt-key add -
  echo "deb https://packagecloud.io/slogr/agent/ubuntu/ focal main" \
    | sudo tee /etc/apt/sources.list.d/slogr-agent.list
  sudo apt-get update -qq
  sudo apt-get install -y slogr-agent
}

install_yum() {
  info "Installing via yum/dnf..."
  PKG_MGR="yum"
  command -v dnf >/dev/null 2>&1 && PKG_MGR="dnf"
  # Add Packagecloud yum repository
  curl -fsSL "https://packagecloud.io/install/repositories/slogr/agent/script.rpm.sh" | sudo bash
  sudo "$PKG_MGR" install -y slogr-agent
}

# ── Direct download fallback ──────────────────────────────────────────────────

install_direct() {
  OS="$1"
  ARCH="$2"
  need_cmd curl
  need_cmd tar

  TMP="$(mktemp -d)"
  trap 'rm -rf "$TMP"' EXIT

  if [ "$OS" = "linux" ]; then
    TARBALL="slogr-agent-${SLOGR_VERSION}-linux-${ARCH}.tar.gz"
    INSTALL_BIN="/usr/local/bin/slogr-agent"
    INSTALL_LIB="/usr/local/lib/slogr"
  else
    TARBALL="slogr-agent-${SLOGR_VERSION}-macos-${ARCH}.tar.gz"
    INSTALL_BIN="/usr/local/bin/slogr-agent"
    INSTALL_LIB="/usr/local/lib/slogr"
  fi

  info "Downloading $TARBALL..."
  curl -fsSL "${RELEASES_BASE}/${TARBALL}" -o "${TMP}/${TARBALL}"

  info "Extracting..."
  tar -xzf "${TMP}/${TARBALL}" -C "$TMP"

  info "Installing to $INSTALL_BIN..."
  sudo mkdir -p "$INSTALL_LIB"
  sudo cp "${TMP}/slogr-agent" "$INSTALL_BIN"
  sudo chmod 755 "$INSTALL_BIN"

  if [ -f "${TMP}/libslogr_native.so" ]; then
    sudo cp "${TMP}/libslogr_native.so" "$INSTALL_LIB/"
  elif [ -f "${TMP}/libslogr_native.dylib" ]; then
    sudo cp "${TMP}/libslogr_native.dylib" "$INSTALL_LIB/"
  fi

  # Default config
  sudo mkdir -p /etc/slogr
  sudo mkdir -p /var/lib/slogr
  [ -f /etc/slogr/agent.yaml ] || sudo touch /etc/slogr/agent.yaml
}

# ── Service registration ──────────────────────────────────────────────────────

register_systemd() {
  if command -v systemctl >/dev/null 2>&1 && [ -d /run/systemd ]; then
    info "Registering systemd service..."
    sudo slogr-agent service install 2>/dev/null || true
    sudo systemctl daemon-reload
    sudo systemctl enable slogr-agent
    sudo systemctl start slogr-agent
    info "Service started. Check status: systemctl status slogr-agent"
  fi
}

register_launchd() {
  PLIST_PATH="/Library/LaunchDaemons/io.slogr.agent.plist"
  if [ ! -f "$PLIST_PATH" ]; then
    info "Registering launchd daemon..."
    sudo slogr-agent service install 2>/dev/null || true
    sudo launchctl load -w "$PLIST_PATH" 2>/dev/null || true
    info "Service started via launchd."
  fi
}

# ── Main ──────────────────────────────────────────────────────────────────────

main() {
  OS=$(detect_os)
  ARCH=$(detect_arch)
  PKG=$(detect_pkg_manager)

  info "Detected: OS=$OS ARCH=$ARCH package_manager=$PKG"

  case "$PKG" in
    brew) install_brew ;;
    apt)  install_apt  ;;
    yum)  install_yum  ;;
    none) install_direct "$OS" "$ARCH" ;;
  esac

  # Connect if API key is set
  if [ -n "$SLOGR_API_KEY" ]; then
    info "Connecting to Slogr..."
    slogr-agent connect --api-key "$SLOGR_API_KEY"
  else
    warn "SLOGR_API_KEY not set. Run: slogr-agent connect --api-key <key>"
  fi

  # Register as system service
  case "$OS" in
    linux) register_systemd ;;
    macos) register_launchd ;;
  esac

  info "Slogr Agent ${SLOGR_VERSION} installed successfully."
  slogr-agent version
}

main "$@"
