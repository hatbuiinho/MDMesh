# Lịch nghỉ và hạn chế ứng dụng

Trong Configuration → App usage, chọn **Thêm lịch nghỉ**. Mỗi lịch có tên,
ngày bắt đầu, giờ bắt đầu/kết thúc, nhóm ứng dụng và ngoại lệ. Chọn **Save changes**
để lưu cả lịch nghỉ và hạn mức từng app. Ví dụ:

- Giờ ngủ: hằng ngày 22:00–06:00, tất cả ứng dụng, ngoại lệ Điện thoại/Đồng hồ.
- Nghỉ trưa: thứ 2–6, 12:00–13:30, chọn YouTube, TikTok và game.

Ngày trong lịch là ngày bắt đầu: lịch thứ Hai 22:00–06:00 kết thúc sáng thứ Ba.
Múi giờ theo Configuration, không theo múi giờ mới của điện thoại. Mốc kết thúc
không thuộc khoảng chặn. Ngoại lệ chỉ bỏ qua lịch chứa nó; các lịch khác và hạn
mức ngày vẫn áp dụng. Tất cả app nghĩa là các package có launcher activity mà
Android cho phép quản lý, bao gồm app mới cài. Agent, launcher đang hoạt động,
System UI, dialer mặc định và device admin được bảo vệ. Android có thể từ chối
suspend một số package thiết yếu khác; agent báo `enforcement_failed` trong usage
report và outcome cấu hình, thay vì coi chúng đã chặn thành công.

## Trải nghiệm Android

- Nhắc trước theo số phút cấu hình (mặc định 5), cập nhật ở mốc 1 phút nếu phù hợp.
- Nhắc ban đêm (21:00–07:00 theo giờ bắt đầu lịch) và trạng thái đang nghỉ dùng
  kênh im lặng. Không phát âm thanh lúc kết thúc lịch.
- Thông báo đang nghỉ mở trang **Lịch nghỉ và sử dụng ứng dụng**.
- Trang trạng thái agent và kiosk launcher có thẻ xem lịch. Trong launcher,
  app bị hạn chế có biểu tượng khóa; bấm app sẽ giải thích lý do và thời điểm
  có thể mở lại theo các lịch chồng nhau. Nếu hết quota ngày, thông báo lý do đó.
- Trang lịch hiển thị các lịch đang/chuẩn bị áp dụng trong những ngày tiếp theo,
  phạm vi, ngoại lệ và múi giờ. Nếu thiếu quyền thông báo/hẹn giờ chính xác,
  trang có thông tin và nút mở cài đặt để người dùng xử lý.

## Thực thi offline

Policy được lưu trên thiết bị. AlarmManager xử lý các mốc lịch, đổi ngày và hết
hạn gia hạn. Báo thức nhắc trước tách riêng, không dùng quota wakeup khi idle của
báo thức thực thi. Service quản lý đang chạy kiểm tra trạng thái cục bộ mỗi 30 giây;
WorkManager không yêu cầu mạng đối soát mỗi 15 phút làm dự phòng. Reboot, tự cập
nhật agent, đổi giờ/múi giờ, cài/gỡ app và cấp quyền exact alarm đều kích hoạt tính
lại. Việc chặn theo lịch không cần Usage Access; quota phút/ngày vẫn cần quyền đó.

Android/OEM có thể trì hoãn alarm khi thiếu exact-alarm access hoặc khi các mốc
allow-while-idle quá gần nhau. Nhắc trước có thể bị hoãn trong Doze; không hứa báo
đúng từng giây trên mọi thiết bị. Cần kiểm thử điện thoại thật với màn hình tắt,
Doze, reboot và trạng thái offline. Không dùng popup chiếm toàn màn hình.

## Nâng cấp và kiểm chứng

1. Cập nhật server để chạy Liquibase changeSet `26.10.17-app-rest-schedules`.
   Bảng mới chỉ lưu timezone/lịch dùng chung, giữ nguyên các rule app cũ.
2. Cập nhật web console và agent đã build từ thay đổi này. Agent mới quảng bá
   `app.restSchedule`; server yêu cầu capability này khi cấu hình có lịch, tránh
   agent cũ bỏ qua lịch nhưng vẫn báo áp dụng thành công. Trong fleet có agent cũ,
   nâng cấp agent trước khi bật lịch vì toàn bộ config.apply có lịch sẽ chờ capability.
3. Build/check: `bash scripts/android-docker.sh :app:assembleDebug :app:lintDebug :core:testDebugUnitTest :proto:test`.
4. Dùng thiết bị Device Owner thử lịch vài phút tới. Kiểm tra nhắc trước, app đang
   mở, mở app từ notification/deep link, ngoại lệ, hai lịch chồng nhau, app mới
   cài, xóa/tắt lịch, mất mạng và reboot. Kiểm tra không mở sớm nếu hết quota.

APK Docker nằm tại `.android-docker/output/app/build/outputs/apk/debug/app-debug.apk`.
Bản debug chưa phải APK release để cập nhật thiết bị production; phải giữ đúng
release signing key và tăng versionCode khi phát hành.
