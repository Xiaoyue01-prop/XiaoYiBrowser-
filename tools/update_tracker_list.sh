#!/usr/bin/env bash
# Sinh app/src/main/assets/tracker_domains.txt từ EasyPrivacy (chạy trong Termux hoặc máy có mạng).
# Chỉ lấy luật thuần domain dạng ||domain^ hoặc ||domain^$third-party; bỏ mọi luật có đường dẫn, thẻ $domain=, ~...
# Dùng tools/tracker_allowlist.txt (mỗi dòng một domain, # chú thích) để loại domain gây lỗi trang.
# GIẤY PHÉP: EasyList/EasyPrivacy phát hành theo GPLv3 hoặc CC BY-SA 3.0 (theo trang easylist.to, hãy tự kiểm tra lại
# trước khi phát hành app); header của file đầu ra ghi nguồn để đáp ứng yêu cầu ghi công.
set -euo pipefail
cd "$(dirname "$0")/.."
URL="https://easylist.to/easylist/easyprivacy.txt"
OUT="app/src/main/assets/tracker_domains.txt"
ALLOW="tools/tracker_allowlist.txt"
TMP="$(mktemp)"; trap 'rm -f "$TMP" "$TMP.allow"' EXIT

curl -fsSL "$URL" \
  | grep -E '^\|\|[a-z0-9][a-z0-9.-]*\.[a-z]{2,}\^(\$third-party)?$' \
  | sed -E 's/^\|\|//; s/\^.*$//' \
  | sort -u > "$TMP"

if [ -f "$ALLOW" ]; then
  { grep -vE '^\s*(#|$)' "$ALLOW" || true; } | tr 'A-Z' 'a-z' | sort -u > "$TMP.allow"
  grep -vxFf "$TMP.allow" "$TMP" > "$TMP.f" || true
  mv "$TMP.f" "$TMP"
fi

{
  echo "# Sinh bởi tools/update_tracker_list.sh ngày $(date -u +%Y-%m-%d) từ EasyPrivacy ($URL)"
  echo "# EasyList/EasyPrivacy: GPLv3 hoặc CC BY-SA 3.0, (c) The EasyList authors. Không sửa tay; thêm ngoại lệ vào tools/tracker_allowlist.txt."
  cat "$TMP"
} > "$OUT"
echo "Đã ghi $(grep -vc '^#' "$OUT" || true) domain vào $OUT"
