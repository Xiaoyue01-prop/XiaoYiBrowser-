#!/data/data/com.termux/files/usr/bin/sh
# Chạy trong thư mục gốc repo Summit (có thư mục app/). Cần: pkg install patch
set -e
[ -d app/src/main/java/com/example/webedge ] || { echo "Chạy trong thư mục gốc Summit (chứa app/)"; exit 1; }
patch -p1 --dry-run < "$(dirname "$0")/summit-debug-dump.patch" && patch -p1 < "$(dirname "$0")/summit-debug-dump.patch"
echo "Xong. Build bản debug rồi mở menu trang để thấy mục [Debug]."
