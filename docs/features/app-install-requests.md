# Yêu cầu cài ứng dụng

## Hai cách chọn app trên Android Agent

- Trang chính → Yêu cầu cài ứng dụng → tìm theo tên trong Agent. Server xác thực bằng device secret rồi gọi Play Bridge hiện có (không dùng API quản trị cho thiết bị).
- Trong Google Play → mở trang app → Chia sẻ → **MDMesh · Yêu cầu cài app**. Agent nhận ACTION_SEND text/plain, kiểm tra host/path/package, mở biểu mẫu lý do. Không tự gửi khi nhận link. Android có thể hiển thị Agent với mọi nội dung chia sẻ dạng văn bản; nội dung không phải link app Google Play sẽ được từ chối.

Cả hai cách gửi chung một yêu cầu trên server, gắn thiết bị và cấu hình hiện tại. Không import app vào Library, tải APK hay cài ngầm. Play Bridge dùng profile/tài khoản chung của dispenser; kết quả không bảo đảm giống Play Store trên máy. Không gửi Google account, model hay Android version của máy sang bridge. Server tra lại tên/nhà phát triển từ package; khi bridge không khả dụng, giữ package làm tên dự phòng thay vì tin tên do thiết bị khai báo.

## Phê duyệt

Admin dùng tài khoản quản trị chung của Agent → Yêu cầu cài ứng dụng → Quản trị, hoặc mục tương ứng trên web. Quyền `edit_devices` và phạm vi truy cập thiết bị được kiểm tra trên server cho đọc/duyệt/lịch sử. Duyệt, từ chối và thu hồi đều ghi audit bằng compare-and-set để tránh hai quyết định ghi đè nhau.

Thông báo admin dùng subscription receive-only hiện có, cùng lifecycle và giới hạn khi màn hình bật của Truy cập mạng. Cần bật nhận thông báo trong Tài khoản quản trị. Dữ liệu thông báo chỉ có số lượng và ID mới nhất; duyệt vẫn cần phiên admin.

Một thiết bị/package/cấu hình chỉ có một yêu cầu pending hoặc approved. Tối đa 30 yêu cầu/giờ/thiết bị; tìm kiếm và tra metadata được giới hạn chung 10 lần/phút/thiết bị trên mỗi server instance. Màn hình user tải 100 yêu cầu gần nhất; admin phân trang 100 dòng. Bộ lọc web áp dụng trong trang.

Sau khi duyệt, nút mở Google Play kiểm tra lại trạng thái online trước khi mở `market://details?id=...`, chỉ định package `com.android.vending`. Offline hoặc đã thu hồi không mở từ dữ liệu cache. Nếu Google Play không có hoặc đang bị chính sách chặn, Agent báo không mở được; không tự mở trình duyệt hay bỏ chính sách. User tự bấm Cài đặt. Agent kiểm tra package hiện diện khi làm mới/trở lại màn hình, báo thời điểm cài mang tính inventory, không dùng báo cáo đó để cấp quyền.

Quyền duyệt hiện có hiệu lực đến khi thu hồi hoặc đổi cấu hình. Khi server quan sát cấu hình khác qua đồng bộ hoặc tải yêu cầu, yêu cầu cũ được chuyển sang stale trong DB; quay lại cấu hình cũ không khôi phục những quyền đã bị đánh dấu stale. Quyền app bổ sung vào allowlist theo đúng thiết bị, không sửa cấu hình dùng chung và không tự mở toàn bộ Play Store. Thu hồi bỏ phần cấp bổ sung; app vẫn có thể được phép bởi cấu hình gốc. Không tự gỡ app đã cài. Kiosk và truy cập mạng giữ chính sách hiện hành, nên admin cần bảo đảm chúng cho phép luồng cài/sử dụng mong muốn.

## Giới hạn kiểm soát cài đặt

Đây là quy trình phê duyệt và mở trang cài app. Google Play thông thường không giới hạn việc tìm/cài app khác theo quyết định MDMesh. Chưa tích hợp Managed Google Play; không coi trạng thái approved là xác nhận thiết bị đã áp dụng quyền cài riêng package. Không nới user restrictions hoặc thêm Google Play vào kiosk/allowlist một cách tự động.

## Triển khai và kiểm tra

Cần triển khai cùng server (Liquibase tạo `appInstallRequest`/`appInstallAudit`), web và APK Agent chứa màn hình/receiver mới. Agent cũ không có hai lối vào mới. Tìm kiếm cần cấu hình PLAY_STORE_ENABLED/bridge/dispenser hoạt động; chia sẻ link vẫn có thể gửi với tên dự phòng nếu bridge chưa có metadata, nhưng cần thiết bị đã enrollment và kết nối HTTPS tới server.

Kiểm tra trên thiết bị có Google Play: tìm Zalo trong Agent → chọn → lý do → gửi; chia sẻ cùng app từ Google Play → không tạo yêu cầu trùng; admin duyệt từ Agent/web → user làm mới → mở đúng trang; cài và quay lại → báo đã cài. Kiểm tra từ chối, thu hồi trước khi mở, offline, đổi cấu hình, app không tương thích/quốc gia khác, Google Play bị ẩn/kiosk chặn, link giả và nhiều link khác app. Không dùng kết quả bridge như bằng chứng app cài được trên thiết bị.
