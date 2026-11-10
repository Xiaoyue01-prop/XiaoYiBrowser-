#!/bin/bash
G="\033[32m"; R="\033[31m"; Y="\033[33m"; N="\033[0m"
echo -e "${Y}🔍 Đang quét lỗi copy-paste...${N}"

FILES=(
  "gecko-prototype/src/main/java/com/example/webedge/GeckoMenuController.kt"
  "gecko-prototype/src/main/java/com/example/webedge/GeckoBrowserToolsController.kt"
  "gecko-prototype/src/main/java/com/example/summitgecko/GeckoPrototypeActivity.kt"
)

ERRORS=0

for f in "${FILES[@]}"; do
  if [ ! -f "$f" ]; then
    echo -e "${R}❌ Không tìm thấy file: $f${N}"
    ERRORS=$((ERRORS+1))
    continue
  fi
  
  echo -e "\n${G}📄 Đang kiểm tra: $(basename $f)${N}"
  
  # 1. Kiểm tra dòng đầu (phải bắt đầu bằng chữ package)
  FIRST_LINE=$(head -n 1 "$f")
  if [[ "$FIRST_LINE" == package* ]]; then
    echo -e "  ${G}✅ Dòng đầu OK${N}"
  else
    echo -e "  ${R}❌ Dòng đầu LỖI (dính ký tự lạ): $FIRST_LINE${N}"
    ERRORS=$((ERRORS+1))
  fi
  
  # 2. Kiểm tra dòng cuối (phải là dấu })
  LAST_LINE=$(tail -n 1 "$f")
  if [[ "$LAST_LINE" == "}" ]]; then
    echo -e "  ${G}✅ Dòng cuối OK${N}"
  else
    echo -e "  ${R}❌ Dòng cuối LỖI (thiếu dấu } hoặc dính chữ): $LAST_LINE${N}"
    ERRORS=$((ERRORS+1))
  fi
  
  # 3. Đếm ngoặc nhọn { và }
  OPEN=$(grep -o "{" "$f" | wc -l)
  CLOSE=$(grep -o "}" "$f" | wc -l)
  if [ "$OPEN" -eq "$CLOSE" ]; then
    echo -e "  ${G}✅ Ngoặc nhọn cân bằng: $OPEN cặp${N}"
  else
    echo -e "  ${R}❌ Ngoặc nhọn LỆCH: Mở $OPEN - Đóng $CLOSE${N}"
    ERRORS=$((ERRORS+1))
  fi
  
  # 4. Tìm ký tự lạ (dấu ' hoặc ` bám ở đầu dòng)
  WEIRD=$(grep -n "^['\`]" "$f")
  if [ -z "$WEIRD" ]; then
    echo -e "  ${G}✅ Không có ký tự lạ (' hoặc \`)${N}"
  else
    echo -e "  ${R}❌ Phát hiện ký tự lạ ở đầu dòng:${N}"
    echo "$WEIRD"
    ERRORS=$((ERRORS+1))
  fi
done

echo -e "\n=============================="
if [ "$ERRORS" -eq 0 ]; then
  echo -e "${G}🎉 TẤT CẢ FILE ĐỀU SẠCH! An tâm để push.${N}"
  echo -e "Chạy 3 lệnh sau để đẩy lên GitHub:"
  echo -e "  ${Y}git add -A${N}"
  echo -e "  ${Y}git commit -m 'Gecko: Port BottomSheet, fix Menu animation'${N}"
  echo -e "  ${Y}git push${N}"
else
  echo -e "${R}⚠️ CÓ $ERRORS LỖI! ĐỪNG PUSH VỘI.${N}"
  echo -e "Chụp màn hình kết quả gửi lại cho mình để mình đưa lệnh fix tự động."
fi
