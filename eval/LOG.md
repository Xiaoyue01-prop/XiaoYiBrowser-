# Nhật ký đánh giá Edge

Bản nguồn `summit_code_20261123_155322.Zip`. Chạy toàn bộ `node eval/run.js` với Chromium 156.0.8078.79, gồm các ca công khai và cả hai phép đo hiệu năng. Mã kiểm tra toàn vẹn của scorer là `93cf396d9ee534a3`.

Điểm xuất phát là 7,16/10. Fixed đạt 8,13, Sticky đạt 5,71. An toàn và hiệu năng đều có 0 lỗi. Scorer đo `__edgeRun` trung bình 15,8 ms và khung cuộn 16,0 ms. Kết quả khớp baseline đi kèm.

## Vòng 1 — sticky của nội dung

Giả thuyết trước khi sửa. Bộ dò đang loại tiêu đề mục và ô bảng khỏi danh sách ứng viên header toàn trang. Đây là phân loại đúng, nhưng các phần tử đó vẫn dùng ngưỡng ghim theo CSS của trang. Khi cuộn, chữ có thể dừng ở mép trên của WebView. Từ các phần tử đã gặp trong quá trình dò, có thể nâng riêng ngưỡng ghim theo mép của vùng cuộn và khôi phục thuộc tính ban đầu khi kết thúc chế độ.

Đã dùng lại các điểm dò header để chỉnh `top` cho sticky phụ trong vùng bị che. CSS tiếp tục xác định giới hạn cuộn của phần tử. Khi giải phóng, script kiểm tra quyền sở hữu giá trị inline trước khi khôi phục.

Giữ thay đổi. TOTAL **7,16 → 8,30**, Fixed 8,13 và Sticky 8,57. Hai ca `s02`, `s03` chuyển sang đạt. So sánh toàn bộ 107 phép kiểm với lần chạy gốc cho thấy 0 hồi quy. Cả hai cổng hiệu năng đạt ở 14,8 ms và 16,0 ms. Lần chạy lại bằng `--write-baseline` cho cùng kết quả từng phép kiểm, với 15,4 ms và 16,0 ms.

## Vòng 2 — dải fixed và header xếp sát nhau

Giả thuyết trước khi sửa. Bộ dò ưu tiên header có ngữ nghĩa nên có thể bỏ qua dải fixed có nội dung ở ngay trên nó. Dời riêng header tới mép an toàn làm hai hộp chồng lên nhau. Với banner đứng riêng, việc loại nó khỏi danh sách header cũng bỏ sót các nút cần tránh thanh trạng thái. Có thể dùng hình học của các hộp fixed rộng đang hiển thị để xác định dải trên cùng và dời các hàng liền nhau cùng một khoảng.

Đã dùng các hộp fixed có nội dung mà bộ dò sẵn có nhìn thấy. Các hàng được dời cùng nhau khi mép trên và mép dưới tiếp giáp và phần lớn chiều ngang trùng nhau. Một banner đứng riêng cũng có thể được dời mà vẫn giữ vai trò của nó trong trang.

Giữ thay đổi. TOTAL **8,30 → 9,05**. Hai ca `f12`, `f15` chuyển sang đạt, gồm bốn phép kiểm mới đạt. Scorer đo `__edgeRun` 16,9 ms và khung cuộn 16,2 ms. Đối chiếu từng phép kiểm với kết quả xuất phát và vòng 1 cho thấy 0 hồi quy. Sau đó chạy lại với `--write-baseline`, cả 107 kết quả đạt/trượt đều lặp lại.

## Kết quả khi dừng

| Nhóm | Ban đầu | Sau vòng 1 | Sau vòng 2 |
| --- | ---: | ---: | ---: |
| Fixed | 8,13 (13/16 ca) | 8,13 (13/16) | 9,38 (15/16) |
| Sticky | 5,71 (4/7 ca) | 8,57 (6/7) | 8,57 (6/7) |
| An toàn | 55/55 phép kiểm | 55/55 | 55/55 |
| Hiệu năng | 2/2 phép kiểm | 2/2 | 2/2 |
| **TOTAL** | **7,16** | **8,30** | **9,05** |

Lần xác nhận cuối đo `__edgeRun` trung bình 16,4 ms trên ca DOM 6.000 hàng, trong ngân sách 20 ms. Khung cuộn trung bình 15,9 ms, trong ngân sách 18 ms. Dừng sau 2 vòng vì TOTAL ≥ 9,0. Cả hai thay đổi đều tăng điểm và được giữ, số vòng hoàn tác là 0.

Lệnh chạy ở thư mục gốc là `EVAL_CHROMIUM=/usr/bin/chromium node eval/run.js`. Mỗi lần ghi baseline đều thực hiện sau một lần chạy đầy đủ đã xác nhận tăng điểm, sau đó đối chiếu kết quả với bản trước khi ghi. ZIP cung cấp tập ca công khai. Chưa có số đo hold-out hoặc WebView trên thiết bị. Ngoài scorer, 52/52 kịch bản Chromium dùng để kiểm tra bản Summit trước cũng đạt.

### Hai ca còn lỗi

- **`s04_tabs_under_fixed_header`**. Khi tải, hàng tab bắt đầu đúng tại đáy header (106 px). Khi cuộn, CSS của tab ghim nó ở 56 px. Điểm dò gần đáy header nằm ngoài hộp tab lúc này (56–96 px), nên `shiftCompanions` bỏ sót hàng và ngưỡng ghim vẫn là 56 px.
- **`f16_header_hides_on_scroll`**. Header cao 56 px, được dời đến `top: 50px`. Khi trang tự ẩn bằng phép dịch −56 px, hộp kết thúc tại vùng −6 đến 50 px và chữ còn lộ dưới thanh trạng thái. Script tiếp tục giữ phần bù `top` trong trạng thái tự ẩn.

Cả hai đều có thể tiếp tục xử lý ở phía JavaScript. Ca thứ nhất cần theo dõi điểm neo khi hàng chuyển từ bố cục thường sang sticky. Ca thứ hai cần phân biệt trạng thái trang chủ động ẩn header với trạng thái header vẫn đang hiển thị.
