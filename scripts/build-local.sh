#!/usr/bin/env bash
# Build lokal PanSocks di Windows (Cygwin / Git-Bash) maupun Linux/macOS.
#
# Kenapa tidak langsung ./gradlew?
# Di Cygwin/Git-Bash, skrip gradlew memanipulasi JAVA_HOME dan CLASSPATH dengan
# asumsi path POSIX. Hasilnya kelas wrapper tidak ketemu:
#   Error: Could not find or load main class org.gradle.wrapper.GradleWrapperMain
# Memanggil GradleWrapperMain lewat `java -cp` melewati seluruh terjemahan path
# itu, jadi cara ini jalan di semua shell tanpa mengubah gradlew-nya.
#
# Pemakaian:
#   bash scripts/build-local.sh              # assembleDebug
#   bash scripts/build-local.sh release      # assembleRelease (butuh keystore)
#   bash scripts/build-local.sh lint         # lint saja
set -euo pipefail

cd "$(dirname "$0")/.."

TASK="${1:-debug}"
case "$TASK" in
  debug)   GRADLE_TASKS=":app:assembleDebug" ;;
  release) GRADLE_TASKS=":app:assembleRelease" ;;
  lint)    GRADLE_TASKS=":app:lint" ;;
  *)       GRADLE_TASKS="$TASK" ;;   # izinkan task Gradle apa pun
esac

# JAVA_HOME: pakai yang sudah di-set, kalau tidak ada cari JDK 17/21.
if [ -z "${JAVA_HOME:-}" ]; then
  for cand in "/c/Program Files/Java/jdk-21" "/c/Program Files/Java/jdk-17" \
              "/usr/lib/jvm/java-17-openjdk-amd64" "/usr/lib/jvm/java-21-openjdk-amd64"; do
    if [ -d "$cand" ]; then
      if command -v cygpath >/dev/null 2>&1; then
        export JAVA_HOME="$(cygpath -w "$cand")"
      else
        export JAVA_HOME="$cand"
      fi
      break
    fi
  done
fi
echo "JAVA_HOME=${JAVA_HOME:-<unset>}"

# Core (libbox.aar) tidak di-track di git - ambil kalau belum ada.
if [ ! -f app/libs/libbox.aar ]; then
  echo "libbox.aar belum ada, mengunduh dari rilis \"core\"..."
  bash scripts/fetch-core.sh
fi

# Release butuh keystore. Ambil dari variabel lingkungan yang sudah di-export
# pemanggil; kalau tidak ada, Gradle jatuh ke kunci debug dan build tetap jalan
# (hanya tidak layak dirilis).
if [ "$TASK" = "release" ] && [ -z "${KEYSTORE_FILE:-}" ]; then
  echo "PERINGATAN: KEYSTORE_FILE kosong - APK rilis akan ditandatangani kunci debug."
fi

shift || true   # buang argumen mode, sisanya diteruskan apa adanya
exec java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain \
  $GRADLE_TASKS --console=plain "$@"
