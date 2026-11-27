#!/data/data/com.termux/files/usr/bin/bash
# Installer for already-authorized devices; no host keys or credentials generated silently.
set -Eeuo pipefail
umask 077
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
command -v pkg >/dev/null || { echo "Termux required" >&2; exit 2; }
pkg install -y openssh android-tools python git
DEST="$HOME/.local/share/jolufasoft-remote"
mkdir -p "$DEST" "$HOME/.local/bin"
BASE="https://raw.githubusercontent.com/joselofarias-byte/Nightzuku/feature/remote-assistance-prototype/remote-assistance"
for file in agent.sh controller.sh check.sh; do
  curl --fail --location --retry 3 --proto '=https' --tlsv1.2 "$BASE/$file" -o "$DEST/$file"
  bash -n "$DEST/$file"
  chmod 700 "$DEST/$file"
done
for file in agent controller check; do
  ln -sfn "$DEST/$file.sh" "$HOME/.local/bin/jremote-$file"
done
echo "Installed: $HOME/.local/bin/jremote-{agent,controller,check}"
echo "No keys, ports, permissions or SSH accounts changed."
