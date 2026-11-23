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

## Đợt nâng ngưỡng lên 9,5

Chạy lại commit `34c6669` trên cùng bản Chromium. TOTAL 9,05, Fixed 9,38, Sticky 8,57, 0 hồi quy, `__edgeRun` 17,0 ms và khung cuộn 16,1 ms.

### Vòng 3 — trạng thái hiện và ẩn của điểm neo

Giả thuyết trước khi sửa. Hộp header đã bao gồm phần bù của Summit khi trang bắt đầu dịch nó lên. Đọc mép dưới của hộp đó có thể giữ lại một header mà trang đã giấu. Ghi đè trực tiếp `top` của một header fixed còn có thể che mất thao tác ẩn bằng CSS. Cần đọc vị trí trước phần bù và trả quyền định vị cho trang khi header rút lên. Trạng thái này quyết định khi nào các hàng bên dưới có thể dùng đáy header làm điểm neo.

Lần thử đầu tăng TOTAL từ 9,05 lên 9,43, 0 hồi quy, 18,8 ms và 16,1 ms. Các biến thể Chromium đạt 5/6, bản gốc đạt 2/6. Đã hoàn tác lần thử này vì header sticky có transition trên `top` vẫn giữ phần bù khi một thuộc tính trạng thái của tổ tiên thay đổi.

Giả thuyết cho lần thử tiếp. Khi `top` của sticky do Summit quản lý, trình duyệt có thể chưa phát transition để báo CSS của trang đã đổi. Có thể quan sát riêng các thuộc tính trên chuỗi tổ tiên của header đã nhận diện, kể cả lúc nó đang ẩn, rồi dùng lịch cập nhật hiện có để đọc lại vị trí.

Đã tách phần dời hộp fixed sang `margin-top`, đọc vị trí gốc trước phần bù và theo dõi thay đổi thuộc tính tại header cùng các tổ tiên. Header sticky trả lại `top` cho trang khi trạng thái CSS đổi. Giữ thay đổi ở **9,05 → 9,43**, Fixed 10,00 và Sticky 8,57. Đối chiếu 107 phép kiểm có 0 hồi quy. Hiệu năng đạt ở 16,7 ms và 16,1 ms. Lần ghi baseline lặp lại cùng kết quả với 18,6 ms và 15,9 ms.

Chạy `EVAL_CHROMIUM=/usr/bin/chromium node tools/check_edge_layout.cjs motion` cho kết quả **6/6**. Các ca dùng header cao 63–81 px, inset 27–41 px, gồm fixed đặt sau nội dung, translate một phần, CSS top âm trên tổ tiên, top inline có ưu tiên, sticky transform trong wrapper và sticky có transition top. Mỗi ca kiểm tra lúc ẩn, hiện lại và trả style khi tắt chế độ. Bộ kiểm tra Chromium của phiên bản trước đạt 52/52.

### Vòng 4 — điểm neo của các tầng sticky

Giả thuyết trước khi sửa. Dò một điểm bên trong hộp header đã dời không cho biết hàng nào vốn bám ngay sau nó. Nâng mọi sticky lên cùng một `top` còn làm mất khoảng cách giữa các tầng. Cần đo cả mép trong bố cục gốc và mép đã chừa inset, ghi nhớ quan hệ khi các hàng tiếp giáp, rồi đổi ngưỡng CSS trong hệ tọa độ của từng khung cuộn. Với sticky lồng nhau, phải tính phần dời một lần theo ngưỡng gốc của mỗi tầng.

Đã dùng vị trí ghim do trang khai báo hoặc hai mép tiếp giáp để nhận ra hàng dưới, kể cả khi thứ tự DOM khác thứ tự hiển thị. Mỗi tầng nhận ngưỡng CSS theo vùng cuộn của nó. Giữa các lần dò, trình duyệt thực hiện việc ghim. Bộ dò dùng lại các phần tử đã gặp, giới hạn số điểm dò thêm và gộp các sự kiện `scrollend` để phát hiện hàng trước đó còn nằm xa header.

Giữ thay đổi ở **9,43 → 10,00**. Ca `s04_tabs_under_fixed_header` đạt cả khi cuộn. Scorer báo 0 hồi quy, `__edgeRun` 16,7 ms và khung cuộn 16,3 ms. Chỉ sau lần chạy đó mới dùng `--write-baseline`. Lần xác nhận tiếp tục đạt đủ 107 phép kiểm, với 16,5 ms và 16,1 ms.

Chạy `EVAL_CHROMIUM=/usr/bin/chromium node tools/check_edge_layout.cjs sticky` cho **9/9** trên bản mới, so với 0/9 trên bản 9,43.

- Hàng chip dưới header cao 37 px và đổi chiều cao khi đang ghim. Một ca khác đặt header cao 89 px sau hàng trong DOM và bắt đầu ở trang đã cuộn.
- Breadcrumb có `top: 0` tiếp giáp header trong bố cục ban đầu, hai thanh sticky ở các wrapper khác nhau, bộ lọc từ ngoài màn hình cuộn tới, và hàng ghim khi header tự ẩn rồi xuất hiện lại.
- Tiêu đề ngoài, nút bên trong và hàng kế tiếp trong cùng khung cuộn. Lặp lại với khung cuộn có viền, đổi inset và một khung độc lập. Ca cuối dùng ba hàng anh em được CSS sắp ngược thứ tự DOM.

### Kết quả đợt nâng ngưỡng

| Hạng mục | Bản 9,05 | Sau vòng 3 | Sau vòng 4 |
| --- | ---: | ---: | ---: |
| Fixed | 9,38 (15/16) | 10,00 (16/16) | 10,00 (16/16) |
| Sticky | 8,57 (6/7) | 8,57 (6/7) | 10,00 (7/7) |
| An toàn | 55/55 | 55/55 | 55/55 |
| Hiệu năng | 2/2 | 2/2 | 2/2 |
| **TOTAL** | **9,05** | **9,43** | **10,00** |

Dừng khi vượt mục tiêu 9,5. Tất cả 26 ca công khai và cả hai phép kiểm hiệu năng đều đạt. Bộ biến thể riêng đạt 15/15, bộ kiểm tra Chromium cũ đạt 52/52. Tệp đính kèm chỉ cung cấp tập công khai. Điểm của bộ ẩn và hiệu năng trên WebView của điện thoại chưa được đo.
