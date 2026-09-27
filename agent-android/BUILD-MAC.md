# Build APK release trên MacBook

Cài Android Studio, SDK Platform 35, Android SDK Build Tools và JDK 17.
Clone/copy repository có script này về Mac. Chạy từ thư mục gốc repository:

```bash
bash scripts/build-android-release.sh 1000 1.0.0
```

Script tìm SDK ở `~/Library/Android/sdk`, JDK 17 qua `java_home`, hoặc JBR
của Android Studio. Có thể chỉ định `JAVA_HOME`, `ANDROID_HOME`, `APKSIGNER`.

Lần đầu nhập và xác nhận mật khẩu keystore. Script tạo
`~/.mdmesh-signing/release.jks`, alias `mdmesh`, với cùng mật khẩu cho key và store.
Lần sau dùng lại file này; không tạo key mới cho thiết bị đã enroll.
Sao lưu keystore và mật khẩu vào nơi an toàn; không đưa chúng lên server/Git.
Mật khẩu chỉ tồn tại trong biến môi trường của quá trình build.

Để dùng keystore riêng, đặt đường dẫn tuyệt đối và alias:

```bash
MDM_RELEASE_STORE_FILE="/absolute/path/release.jks" \
MDM_RELEASE_KEY_ALIAS=mdmesh \
bash scripts/build-android-release.sh 1001 1.0.1
```

Tăng versionCode ở mỗi lần phát hành. Script từ chối ghi đè thư mục output
đã tồn tại, nhưng không kiểm tra version đã cài trên thiết bị; bạn phải giữ
versionCode tăng cả khi chuyển sang Mac/checkout mới hoặc chạy Gradle clean.

Kết quả trong `agent-android/app/build/distributions/1000/`:

- `mdmesh-custom.apk`: APK đã ký và được apksigner xác minh.
- `SHA256SUMS`: checksum file để kiểm tra sau khi chuyển lên server.
- `web-build.env`: package, checksum **chứng chỉ ký**, URL APK cho QR provisioning.
- `release-info.txt`: version và fingerprint chứng chỉ, không chứa mật khẩu.

Chuyển bốn file này lên server. Output nằm trong thư mục build nên cần lưu
bản phát hành trước khi chạy Gradle clean. Script không tự deploy lên server.
Server phải phục vụ APK không cần đăng nhập và build lại web với các biến
trong `web-build.env`. Chỉ restart web image upstream không cập nhật QR.
URL mặc định là `https://mdm.hatbuinho.me/files/mdmesh-custom.apk`, có thể đổi
bằng `MDM_APK_URL`. `/files/agent.apk` hiện trỏ tới mirror upstream nên không
dùng đường dẫn đó cho APK riêng nếu chưa sửa routing.

Sau khi nâng cấp control plane có danh mục agent release, các bản tiếp theo có thể
upload trực tiếp tại **Settings → Agent releases & rollout**; không cần đổi mount APK
hay build lại web. Xem [quy trình rollout toàn bộ thiết bị](../docs/agent-rollout.md).
