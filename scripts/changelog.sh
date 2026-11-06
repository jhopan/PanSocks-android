#!/usr/bin/env bash
# Hasilkan bagian "Yang baru" untuk catatan rilis dari riwayat commit.
#
# Dipakai workflow rilis lewat $GITHUB_ENV, tapi sengaja dibuat sebagai skrip
# terpisah supaya bisa diuji di lokal tanpa push tag:
#
#   bash scripts/changelog.sh v1.0.0 v1.0.1
#   bash scripts/changelog.sh "" v1.0.1        # rilis pertama
#
# Commit dikelompokkan berdasarkan prefix Conventional Commits. Prefix yang
# tidak dikenal tetap ditampilkan di "Lainnya" - commit tidak boleh hilang
# hanya karena penulisnya lupa menulis prefix.
set -euo pipefail

PREV="${1:-}"
CURR="${2:-}"

# Batas jumlah baris supaya catatan rilis tidak jadi dinding teks.
MAX_PER_GROUP="${MAX_PER_GROUP:-8}"

# Tag sebelumnya mungkin tidak ada (rilis pertama, tag dihapus, atau checkout
# tidak membawa tag). Tanpa cek ini, `git log <tag-tidak-ada>..` hanya gagal
# diam-diam dan hasilnya "tidak ada perubahan" - menyesatkan, karena riwayatnya
# justru ada. Kalau tag tidak bisa dipastikan, jatuh ke seluruh riwayat.
prev_ok=""
if [ -n "$PREV" ] && git rev-parse --verify --quiet "refs/tags/${PREV}" >/dev/null 2>&1; then
  prev_ok="$PREV"
fi

if [ -n "$prev_ok" ] && [ -n "$CURR" ]; then
  RANGE="${prev_ok}..${CURR}"
else
  if [ -n "$PREV" ] && [ -z "$prev_ok" ]; then
    echo "catatan: tag ${PREV} tidak ditemukan - memakai seluruh riwayat" >&2
  fi
  # Rilis pertama (atau tag sebelumnya tidak diketahui): seluruh riwayat.
  RANGE="${CURR:-HEAD}"
fi

# --no-merges: commit merge tidak informatif untuk pembaca catatan rilis.
LOG=$(git log "$RANGE" --no-merges --pretty=format:'%s' 2>/dev/null || true)

if [ -z "$LOG" ]; then
  echo "Tidak ada perubahan tercatat pada rilis ini."
  exit 0
fi

# Kumpulkan per kelompok. Format internal: satu baris per commit, diawali tag
# kelompok supaya urutannya stabil saat digabung di akhir.
COLLECTED=$(printf '%s\n' "$LOG" | awk '
  function group(line,   l) {
    l = tolower(line)
    if (l ~ /^(feat|feature)(\(|:)/)           return "BARU"
    if (l ~ /^(perf|optimize|refactor)(\(|:)/) return "BARU"
    if (l ~ /^(fix|bugfix|hotfix)(\(|:)/)      return "PERBAIKAN"
    if (l ~ /^(docs|doc)(\(|:)/)               return "DOKUMENTASI"
    if (l ~ /^(ci|build|chore|test)(\(|:)/)    return "INTERNAL"
    return "LAIN"
  }
  {
    line = $0
    gsub(/^[ \t]+|[ \t]+$/, "", line)
    if (line == "") next
    # Buang prefix conventional-commit dari yang ditampilkan - pembaca tidak
    # perlu melihat "fix(android):", cukup isinya.
    sub(/^[a-zA-Z]+(\([^)]*\))?!?:[ \t]*/, "", line)
    if (line == "") next
    print group($0) "\t" line
  }
')

emit_group() {
  local key="$1" judul="$2"
  local lines
  lines=$(printf '%s\n' "$COLLECTED" | awk -F'\t' -v k="$key" '$1==k {print "- " $2}')
  [ -z "$lines" ] && return 0

  local total shown
  total=$(printf '%s\n' "$lines" | wc -l | tr -d ' ')
  shown=$(printf '%s\n' "$lines" | head -n "$MAX_PER_GROUP")
  printf '**%s**\n\n%s\n' "$judul" "$shown"
  if [ "$total" -gt "$MAX_PER_GROUP" ]; then
    printf '\n_...dan %d perubahan lain._\n' "$((total - MAX_PER_GROUP))"
  fi
  printf '\n'
}

emit_group "BARU"        "Yang baru"
emit_group "PERBAIKAN"   "Perbaikan"
emit_group "DOKUMENTASI" "Dokumentasi"
emit_group "INTERNAL"    "Internal"
emit_group "LAIN"        "Lainnya"
