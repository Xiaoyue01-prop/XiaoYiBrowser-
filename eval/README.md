# Bộ eval cho edge_script.js

Chấm tự động chế độ tràn viền (header fixed/sticky dưới thanh trạng thái) bằng Chromium mobile giả lập (390x800, inset 50px).
Chuẩn tham chiếu là Safari = 10/10; điểm số tuyệt đối chỉ là thước đo tương đối giữa các phiên bản, KHÔNG phải điểm trên WebView thật.

## Chạy
    npm i -D playwright && npx playwright install chromium     # một lần (hoặc đặt EVAL_CHROMIUM=/đường/dẫn/chromium)
    node eval/run.js                       # chạy bộ công khai + perf, so với baseline.json
    node eval/run.js --verbose             # liệt kê cả check đạt
    node eval/run.js --script <file>       # chấm một file edge_script.js khác
    node eval/run.js --no-perf             # bỏ đo hiệu năng (nhanh hơn)
Mã thoát 2 nếu có thoái lui so với baseline.json.

## Cách tính điểm
- Chỉ ca Fixed (F) và Sticky (S) tạo ra điểm: một ca đạt khi MỌI phép kiểm F/S của ca đó đạt. Tổng = 10 × (0.6×F + 0.4×S).
- Cổng phạt: mỗi phép kiểm An toàn (X: trả lại trạng thái khi tắt chế độ, không lỗi JS, không động vào phần tử không liên quan, nâng thanh đáy) trượt trừ 0.4; mỗi phép kiểm hiệu năng (P) trượt trừ 1.0.
- "Không có nội dung dưới thanh trạng thái" đo vị trí chữ/icon thật của phần tử ghim (fixed/sticky), đây là phép đo chính cho sticky và banner.

## Quy tắc khi sửa edge_script.js
1. KHÔNG sửa eval/run.js, eval/lib.js, eval/cases/*. Không hard-code theo id/tên class/nội dung của ca test.
2. Chỉ cập nhật baseline.json bằng `node eval/run.js --write-baseline` sau khi một thay đổi đã được xác nhận là tăng điểm và không thoái lui.
3. Mỗi thay đổi: chạy cả bộ; nếu điểm không tăng, hoặc có REGRESSION, hoặc perf trượt, thì hoàn tác.
4. Ghi LOG.md: giả thuyết, thay đổi, điểm trước/sau.
