#!/usr/bin/env bash
# =============================================================================
# 下载 App 运行所需的大二进制（它们不进 git，见 tools/models.sha256）。
#
#   bash tools/fetch_models.sh                 # 全部：sherpa-onnx AAR + ASR/KWS/VAD 模型
#   bash tools/fetch_models.sh --aar-only      # 只下 AAR（只想编译/跑单测时够用）
#   bash tools/fetch_models.sh --models-only   # 只下语音模型（只想跑真机时）
#   bash tools/fetch_models.sh --force         # 忽略本地已有文件，重新下载
#
# 下载内容（约 110MB）：
#   1. sherpa-onnx AAR   JitPack: com.github.k2-fsa/sherpa-onnx:sherpa-onnx:v2.0.1
#   2. ASR 模型          sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23（zipformer 中文 14M int8）
#   3. KWS 唤醒模型      sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20（chunk-16 int8）
#   4. Silero VAD        silero_vad.onnx
#
# 每个文件下完都按 tools/models.sha256 校验 sha256；校验不过直接失败，不会留下半个文件。
#
# 网络环境变量：
#   GH_PROXY=https://ghfast.top   # GitHub Release 资产走代理前缀（国内常需要）
#   VC_CACHE_DIR=/path/to/cache   # 下载缓存目录，默认 .cache/models（可复用）
#   VAD_MIRROR=https://...        # 覆盖 Silero VAD 的下载地址
#
# 依赖：bash 4+、curl、tar（支持 bz2）、sha256sum 或 shasum。
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANIFEST="$ROOT/tools/models.sha256"
CACHE_DIR="${VC_CACHE_DIR:-$ROOT/.cache/models}"

JITPACK_AAR_URL="https://jitpack.io/com/github/k2-fsa/sherpa-onnx/sherpa-onnx/v2.0.1/sherpa-onnx-v2.0.1.aar"

ASR_ARCHIVE="sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23.tar.bz2"
ASR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$ASR_ARCHIVE"
ASR_DIR="sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23"

KWS_ARCHIVE="sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20.tar.bz2"
KWS_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/$KWS_ARCHIVE"
KWS_DIR="sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20"

VAD_URL="${VAD_MIRROR:-https://hf-mirror.com/deepghs/silero-vad-onnx/resolve/main/silero_vad.onnx}"
VAD_FALLBACK_URL="https://huggingface.co/deepghs/silero-vad-onnx/resolve/main/silero_vad.onnx"
VAD_DEST="app/src/main/assets/vad/silero_vad.onnx"

DO_AAR=1
DO_MODELS=1
FORCE=0

for arg in "$@"; do
  case "$arg" in
    --aar-only)   DO_MODELS=0 ;;
    --models-only) DO_AAR=0 ;;
    --force)      FORCE=1 ;;
    -h|--help)    sed -n '2,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数：$arg（--help 看用法）" >&2; exit 2 ;;
  esac
done

say()  { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
info() { printf '    %s\n' "$*"; }
die()  { printf '\n[失败] %s\n' "$*" >&2; exit 1; }

# ---------- 基础工具 ----------
command -v curl >/dev/null 2>&1 || die "找不到 curl，请先安装（Windows 用 Git for Windows 自带的即可）"
command -v tar  >/dev/null 2>&1 || die "找不到 tar"

if command -v sha256sum >/dev/null 2>&1; then
  SHA256_OF() { sha256sum "$1" | awk '{print $1}'; }
elif command -v shasum >/dev/null 2>&1; then
  SHA256_OF() { shasum -a 256 "$1" | awk '{print $1}'; }
else
  die "找不到 sha256sum / shasum，无法校验下载内容"
fi

# GitHub 资产在部分网络下不可达，GH_PROXY 提供前缀转发（如 https://ghfast.top）。
proxied() {
  if [ -n "${GH_PROXY:-}" ]; then printf '%s/%s' "${GH_PROXY%/}" "$1"; else printf '%s' "$1"; fi
}

expected_sha() { # $1 = 相对仓根的路径
  awk -v p="$1" '!/^#/ && NF >= 2 && $2 == p { print $1; exit }' "$MANIFEST"
}

file_ok() { # 0 = 文件存在且 sha256 与清单一致
  local rel="$1" abs="$ROOT/$1" want have
  want="$(expected_sha "$rel")"
  [ -n "$want" ] || die "tools/models.sha256 里没有 $rel 的校验值"
  [ -f "$abs" ] || return 1
  have="$(SHA256_OF "$abs")"
  if [ "$have" = "$want" ]; then return 0; fi
  info "sha256 不匹配：$rel"
  info "  期望 $want"
  info "  实际 $have"
  return 1
}

download() { # $1 = url, $2 = 输出文件（先写 .part）。失败返回 1，由调用方决定怎么报错。
  local url="$1" out="$2"
  mkdir -p "$(dirname "$out")"
  info "GET $url"
  if ! curl -fL --retry 3 --retry-delay 2 --connect-timeout 20 -o "$out.part" "$url"; then
    rm -f "$out.part"
    info "下载失败"
    return 1
  fi
  mv -f "$out.part" "$out"
}

download_or_die() {
  download "$1" "$2" || die "下载失败：$1
      - 国内网络可试：GH_PROXY=https://ghfast.top bash tools/fetch_models.sh
      - 或手动下载后放到 $CACHE_DIR/ 再重跑"
}

install_verified() { # $1 = 源文件, $2 = 仓内相对路径（校验后原子搬入）
  local src="$1" rel="$2" dest="$ROOT/$2" want have
  want="$(expected_sha "$rel")"
  [ -n "$want" ] || die "tools/models.sha256 里没有 $rel 的校验值"
  have="$(SHA256_OF "$src")"
  if [ "$have" != "$want" ]; then
    die "sha256 校验失败：$rel
     期望 $want
     实际 $have
     下载内容与清单不符（上游可能更新了模型），已中止，未写入任何文件。"
  fi
  mkdir -p "$(dirname "$dest")"
  mv -f "$src" "$dest"
  info "ok $rel"
}

fetch_archive() { # $1 = url, $2 = 缓存文件名；解包结果目录写入全局 UNPACK_DIR
  local url="$1" name="$2"
  local archive="$CACHE_DIR/$name"
  if [ "$FORCE" -eq 0 ] && [ -f "$archive" ]; then
    info "复用缓存 $archive"
  else
    download_or_die "$(proxied "$url")" "$archive"
  fi
  local tmp="$CACHE_DIR/unpack-${name%.tar.bz2}"
  rm -rf "$tmp"; mkdir -p "$tmp"
  tar -xjf "$archive" -C "$tmp" || die "解包失败：$archive（tar 需要 bz2 支持）"
  UNPACK_DIR="$tmp"
}

# =============================================================================
# 1. sherpa-onnx AAR（编译期依赖：没有它 :app:assembleDebug 连配置都过不了）
# =============================================================================
fetch_aar() {
  say "sherpa-onnx AAR（JitPack v2.0.1，48MB）"
  local rel="app/libs/sherpa-onnx.aar"
  if [ "$FORCE" -eq 0 ] && file_ok "$rel"; then
    info "已就绪，跳过"
    return
  fi
  local tmp="$CACHE_DIR/sherpa-onnx-v2.0.1.aar"
  if [ "$FORCE" -eq 0 ] && [ -f "$tmp" ] && [ "$(SHA256_OF "$tmp")" = "$(expected_sha "$rel")" ]; then
    info "复用缓存 $tmp"
  else
    download_or_die "$JITPACK_AAR_URL" "$tmp"
  fi
  install_verified "$tmp" "$rel"
}

# =============================================================================
# 2. ASR 模型（zipformer 中文 14M，int8）—— 从上游 tar.bz2 里只取要用的 4 个文件
# =============================================================================
fetch_asr() {
  say "ASR 模型：$ASR_ARCHIVE"
  local all_ok=0 rel
  for rel in app/src/main/assets/asr/decoder.int8.onnx \
             app/src/main/assets/asr/encoder.int8.onnx \
             app/src/main/assets/asr/joiner.int8.onnx \
             app/src/main/assets/asr/tokens.txt; do
    [ "$FORCE" -eq 0 ] && file_ok "$rel" || all_ok=1
  done
  if [ "$all_ok" -eq 0 ]; then info "已就绪，跳过"; return; fi

  fetch_archive "$ASR_URL" "$ASR_ARCHIVE"
  local src="$UNPACK_DIR/$ASR_DIR"
  [ -d "$src" ] || die "解包后找不到目录 $ASR_DIR"
  install_verified "$src/decoder-epoch-99-avg-1.int8.onnx" "app/src/main/assets/asr/decoder.int8.onnx"
  install_verified "$src/encoder-epoch-99-avg-1.int8.onnx" "app/src/main/assets/asr/encoder.int8.onnx"
  install_verified "$src/joiner-epoch-99-avg-1.int8.onnx"  "app/src/main/assets/asr/joiner.int8.onnx"
  install_verified "$src/tokens.txt"                        "app/src/main/assets/asr/tokens.txt"
}

# =============================================================================
# 3. KWS 唤醒模型（中英混说 3M，chunk-16 int8 三件套 + en.phone + tokens）
# =============================================================================
fetch_kws() {
  say "KWS 唤醒模型：$KWS_ARCHIVE"
  local all_ok=0 rel
  for rel in app/src/main/assets/kws/decoder.onnx \
             app/src/main/assets/kws/encoder.int8.onnx \
             app/src/main/assets/kws/joiner.int8.onnx \
             app/src/main/assets/kws/en.phone \
             app/src/main/assets/kws/tokens.txt; do
    [ "$FORCE" -eq 0 ] && file_ok "$rel" || all_ok=1
  done
  if [ "$all_ok" -eq 0 ]; then info "已就绪，跳过"; return; fi

  fetch_archive "$KWS_URL" "$KWS_ARCHIVE"
  local src="$UNPACK_DIR/$KWS_DIR"
  [ -d "$src" ] || die "解包后找不到目录 $KWS_DIR"
  install_verified "$src/decoder-epoch-13-avg-2-chunk-16-left-64.onnx"      "app/src/main/assets/kws/decoder.onnx"
  install_verified "$src/encoder-epoch-13-avg-2-chunk-16-left-64.int8.onnx" "app/src/main/assets/kws/encoder.int8.onnx"
  install_verified "$src/joiner-epoch-13-avg-2-chunk-16-left-64.int8.onnx"  "app/src/main/assets/kws/joiner.int8.onnx"
  install_verified "$src/en.phone"                                          "app/src/main/assets/kws/en.phone"
  install_verified "$src/tokens.txt"                                        "app/src/main/assets/kws/tokens.txt"
}

# =============================================================================
# 4. Silero VAD（2.3MB，用于断句/静音判定）
# =============================================================================
fetch_vad() {
  say "Silero VAD：silero_vad.onnx"
  if [ "$FORCE" -eq 0 ] && file_ok "$VAD_DEST"; then info "已就绪，跳过"; return; fi
  local tmp="$CACHE_DIR/silero_vad.onnx"
  if [ "$FORCE" -eq 1 ] || ! { [ -f "$tmp" ] && [ "$(SHA256_OF "$tmp")" = "$(expected_sha "$VAD_DEST")" ]; }; then
    # 主源不通就换官方源；两个都不通才算失败。
    if ! download "$VAD_URL" "$tmp"; then
      info "主源失败，改用 $VAD_FALLBACK_URL"
      download_or_die "$VAD_FALLBACK_URL" "$tmp"
    fi
  else
    info "复用缓存 $tmp"
  fi
  install_verified "$tmp" "$VAD_DEST"
}

# =============================================================================
main() {
  [ -f "$MANIFEST" ] || die "找不到 $MANIFEST"
  mkdir -p "$CACHE_DIR"

  if [ "$DO_AAR" -eq 1 ];    then fetch_aar;  fi
  if [ "$DO_MODELS" -eq 1 ]; then fetch_asr; fetch_kws; fetch_vad; fi

  say "完成"
  info "缓存目录：$CACHE_DIR（可删除；下次重下）"
  if [ "$DO_MODELS" -eq 1 ]; then
    info "现在可以：./gradlew :app:assembleDebug"
  else
    info "现在可以编译/跑单测；要真机跑语音，再执行：bash tools/fetch_models.sh --models-only"
  fi
}

main
