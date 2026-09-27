# Phát hành và rollout agent Android custom

Agent release và cập nhật server là hai luồng riêng. `AUTO_UPDATE` thuộc updater server;
không dùng biến này để bật cập nhật thiết bị. Upload APK không tự khởi động rollout.

## Nâng cấp control plane lần đầu

Build và chạy **cả server và web** từ code mới. Với deployment external hiện tại:

```bash
mkdir -p backups/manual
docker compose -f docker-compose.external.yml run --rm -T db-tools \
  pg_dump -Fc > backups/manual/pre-agent-rollout.dump

docker compose -f docker-compose.external.yml -f docker-compose.agent.yml \
  -f docker-compose.rollout.yml build server caddy

docker compose -f docker-compose.external.yml -f docker-compose.agent.yml \
  -f docker-compose.rollout.yml up -d --no-deps server caddy
```

Giữ cùng Compose project name và `.env` của deployment đang chạy để dùng đúng database,
network và volume. Kiểm tra lệnh backup/build thành công trước khi chạy lệnh tiếp theo.
Các lệnh trên là hướng dẫn triển khai, không được tự thực hiện khi chạy test.

Server tự áp dụng migration Liquibase khi khởi động. APK được lưu trong volume
`mdmeshdata`, dưới `files/agent-releases/`; metadata, danh sách máy đích và lịch sử lệnh
được lưu trong PostgreSQL. Backup cả database và volume để giữ nguyên các bản phát hành.

Nếu đang có rollout từ giao diện mirror cũ, hủy đợt đó và chờ các lệnh đã giao hoàn tất
trước khi nâng cấp, rồi tạo đợt mới từ danh mục APK. API tạo rollout mới chỉ nhận
`releaseId`, không còn nhận URL/hash/version do client tự khai báo.

## Đưa bản đang dùng vào danh mục

Vào **Settings → Agent releases & rollout → Upload agent APK** và upload
`releases/android/1000/mdmesh-custom.apk` trước. Bản đầu tiên xác lập chứng chỉ ký cho
package trong tổ chức; dùng đúng APK đang cài trên máy. Việc upload không cập nhật máy.

Server kiểm tra chữ ký APK, đọc package/versionName/versionCode, tính SHA-256 của file
và checksum chứng chỉ. Chỉ nhận package `com.mdmesh.agent` hoặc `com.mdmesh.agent.debug`,
APK có một signer hợp lệ và receiver quản trị MDMesh. Các phiên bản tiếp theo của cùng
package phải giữ chứng chỉ ký, tăng versionCode và dùng versionName mới.

Các APK không bị ghi đè. Mỗi file có URL riêng; upload bản tiếp theo không đổi file của
rollout đang chạy. Giới hạn upload là 128 MiB. APK trong danh mục tải được không cần
session, kể cả khi bật secure enrollment, giống APK dùng để provisioning.

## Mỗi lần phát hành mới

1. Build trên máy giữ keystore gốc:

   ```bash
   bash scripts/build-android-release.sh 1001 1.0.1
   ```

2. Upload `agent-android/app/build/distributions/1001/mdmesh-custom.apk` ở Settings.
   Không cần đổi Docker mount hoặc build lại web cho mỗi APK mới.
3. Chọn release vừa upload. Xem số máy cần cập nhật, đã ở bản đích/cao hơn và không đủ
   điều kiện. Màn hình preview và danh sách rollout có tìm kiếm/lọc trạng thái.
4. Mặc định **Test group first**: chọn 1–3 máy đại diện, bấm **Start canary**.
   Khi tất cả máy thử báo đã chạy bản mới, kiểm tra thực tế kiosk/điều khiển rồi bấm
   **Promote to fleet**. Server cũng kiểm tra điều kiện promote, không chỉ giao diện.
5. Nếu đã kiểm thử bản này, có thể chọn **All devices in this organization** và xác nhận
   triển khai thẳng toàn bộ. Danh sách máy đích được chốt tại thời điểm tạo đợt. Máy
   enroll sau đó không tự gia nhập đợt này.
6. Giữ rollout hoạt động cho đến khi các máy offline quay lại. **Finish** chỉ mở khi
   không còn máy chờ/cài/lỗi (máy không đủ điều kiện được thống kê riêng). **Cancel**
   dừng cấp lệnh và hủy lệnh chưa giao; lệnh đã giao có thể vẫn cài xong.

## Máy offline, lỗi và tiến độ

Server đối chiếu phiên bản đích mỗi khi thiết bị check-in, sau khi lưu trạng thái và kết
quả lệnh. Không phụ thuộc việc mở trình duyệt. Nếu lệnh chưa giao đã hết hạn, máy vẫn
nhận lệnh mới khi online; nếu đang cài app khác, máy đợi app đó xong rồi tiếp tục.

Lệnh đã giao/accepted có thời hạn 6 giờ. Sau tối đa 3 lần giao không có kết quả xác nhận,
thiết bị chuyển trạng thái lỗi. Lỗi cài đặt rõ ràng dừng ngay để admin xem chi tiết và
bấm **Retry** từng máy; không thử lại vô hạn khi sai chữ ký hay APK không tương thích.
Retry giữ lịch sử lệnh cũ và bắt đầu lại số lần thử cho máy đó.

ACK `done` chưa đủ để coi rollout thành công: server chờ thiết bị báo phiên bản đang chạy.
Nếu ACK xong nhưng sau 10 phút vẫn chưa có phiên bản mới, giao diện báo lỗi cho phép thử
lại. Khi phiên bản mới đã được báo, server hoàn tất lệnh còn mở của rollout, kể cả trường
hợp agent tự cập nhật làm tiến trình cũ bị đóng trước khi ACK.

Agent từ thay đổi này báo thêm `agentVersionCode`, `agentPackageName` và
`agentSignatureChecksum`. Máy có versionCode cao hơn không bị yêu cầu hạ cấp. Sai package,
chứng chỉ hoặc thiếu `app.silentInstall` được đánh dấu không đủ điều kiện.

Agent cũ vẫn cập nhật được qua `app.install`; trong lần chuyển tiếp server dùng
versionName nếu chưa có versionCode và để Android kiểm tra chữ ký khi cài. Vì vậy phải
upload đúng APK gốc, giữ keystore và thử nhóm nhỏ trước. Một versionName mới cho mỗi bản
phát hành giúp tiến độ của agent cũ không bị nhầm.

## Provisioning máy mới

Trang **Enroll → Agent APK** cho phép chọn cùng release đã upload. QR tự dùng package,
checksum chứng chỉ và URL của release đó; không phải build lại web. Tùy chọn
**Configured APK (existing default)** giữ cấu hình QR cũ để chuyển đổi từng bước. Chọn
bản đã kiểm thử cho máy mới; việc upload không tự đổi lựa chọn provisioning.

## Kiểm thử dành cho developer

Chạy Maven bằng JDK 17, dùng `server/build.properties.example` làm cấu hình build test:

```bash
mvn -pl server -am test
npm ci --prefix web
npm run build --prefix web
cd agent-android
./gradlew :proto:test :core:compileDebugKotlin
```

`AgentRolloutDatabaseTest` chỉ chạy khi có `ROLLOUT_TEST_JDBC`. Dùng PostgreSQL test
riêng, user `postgres`, password `rollout-test`; test tạo/xóa schema riêng và áp dụng
SQL từ chính migration mới. Không trỏ biến này vào database đang vận hành.
`ROLLOUT_TEST_APK` là đường dẫn tuyệt đối đến APK custom đã ký để kiểm thử upload thực,
checksum, file bất biến và từ chối file bị sửa. Không cần keystore/private key cho test.

Xác minh chữ ký APK dùng [Android apksig ApkVerifier](https://android.googlesource.com/platform/tools/apksig/+/master/src/main/java/com/android/apksig/ApkVerifier.java).
