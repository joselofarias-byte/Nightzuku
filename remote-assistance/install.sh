#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
umask 077

command -v pkg >/dev/null || { echo "Ejecuta este instalador desde Termux." >&2; exit 2; }
pkg install -y openssh android-tools python coreutils

source_dir="$(cd -- "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
destination="$HOME/.local/share/jolufasoft-remote"
bin_dir="$HOME/.local/bin"
files=(agent.sh controller.sh check.sh ssh-common.sh)
for file in "${files[@]}"; do
    bash -n "$source_dir/$file"
done
mkdir -p "$destination" "$bin_dir"
install -m 700 "${files[@]/#/$source_dir/}" "$destination/"
for name in agent controller check; do
    ln -sfn "$destination/$name.sh" "$bin_dir/jremote-$name"
done
printf '%s\n' "Instalados en $bin_dir: jremote-agent, jremote-controller y jremote-check."
