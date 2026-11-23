#!/data/data/com.termux/files/usr/bin/sh
set -eu
cd "$(dirname "$0")"
patch_file=summit-debug-dump.patch
if git apply --reverse --check "$patch_file" 2>/dev/null; then
    echo "Mục chụp cấu trúc đã có trong mã nguồn. Build bản debug rồi mở Menu trang."
elif git apply --check "$patch_file"; then
    git apply "$patch_file"
    echo "Đã thêm mục chụp cấu trúc. Build bản debug rồi mở Menu trang."
else
    echo "Không thể áp bản vá lên phiên bản mã nguồn này." >&2
    exit 1
fi
