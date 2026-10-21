# Phiên quản trị dùng chung trên Android Agent

Agent giữ một phiên quản trị cho toàn bộ màn hình. Enrollment/device secret và receive-only notification token là các thông tin xác thực riêng, không dùng để duyệt yêu cầu.

## Duy trì đăng nhập

Sau đăng nhập mật khẩu thành công (và không còn bước 2FA/reset password đang chờ), Agent đăng ký installation ID tại `POST /public/agent-admin-session/register`. Server cấp refresh token ngẫu nhiên, chỉ lưu SHA-256 trong DB; cookie truy cập vẫn đi qua AuthFilter và quyền/phạm vi thiết bị hiện hành. Cookie có idle timeout 15 phút; refresh token có hạn tuyệt đối 30 ngày kể từ lần đăng nhập/đăng ký, không kéo dài hạn qua mỗi lần refresh. Tài khoản hiển thị ngày cuối được tự gia hạn.

Mỗi `POST /public/agent-admin-session/refresh` dùng Authorization Bearer, tiêu thụ token cũ và cấp token mới cùng cookie servlet session mới. Token đã dùng được giữ để nhận diện replay; dùng lại token cũ sẽ thu hồi cả family, bao gồm cookie Agent đã cấp. Các lần refresh từ nhiều màn hình được tuần tự hóa qua mutex chung. Không dùng refresh token như bearer cho API quản trị/device.

Cookie, tên tài khoản, server, token và thời hạn được mã hóa cùng nhau bằng AES-GCM với khóa Android Keystore. Không lưu mật khẩu. Token ràng buộc tenant, user, installation và phiên bản thông tin xác thực. Đổi password/authToken, bật/tắt 2FA, mất quyền edit_devices, xóa tài khoản hoặc hết hạn sẽ làm refresh không hợp lệ. Đăng xuất xóa ngay state cục bộ và yêu cầu thu hồi phía server; logout servlet thông thường cũng thu hồi family của cookie Agent đó. Khi offline không thể bảo đảm request thu hồi tới server, nhưng state local đã bị xóa.

Không lấy lỗi 403 thiếu quyền làm bằng chứng phiên hết hạn: Agent kiểm tra `/public/agent-admin-session/status` trước khi refresh/xóa. Mất mạng/lỗi server giữ state mã hóa để thử lại; chỉ xác nhận không hợp lệ từ server mới xóa. Generation của store chặn phản hồi đăng nhập/refresh đến muộn khôi phục một phiên đã logout hoặc đổi server.

## Tương thích và triển khai

Cần cập nhật server với migration `agent-admin-refresh-sessions-v1` và cập nhật APK Agent. Khi server chưa có endpoint (404), Agent giữ cơ chế cookie cũ, hiển thị chưa hỗ trợ tự gia hạn và thử nâng cấp lại có giới hạn tần suất. Phiên cookie hợp lệ cũ được tự nâng cấp khi gọi API admin sau khi server đã cập nhật; nếu cookie đã hết hạn và chưa có refresh token thì cần đăng nhập lại một lần. Không build release/publish tự động cho thay đổi này.

Lưu ý: DB là nguồn thu hồi family; servlet sessions vẫn theo cơ chế triển khai Tomcat hiện tại. Đây là cơ chế refresh dành cho Agent, không thay đổi đăng nhập web/JWT.

Rotation/replay tham chiếu [RFC 9700, mục 4.14](https://www.rfc-editor.org/rfc/rfc9700.html#section-4.14). Không triển khai flow OAuth hay thay giao thức login mật khẩu hiện có.

## Kiểm tra

Test DAO: lưu hash, rotation, replay, hết hạn/thu hồi, đổi password/2FA. Test resource: cookie hết hạn, 2FA chưa xong, thay authToken, cookie family đã thu hồi. Test AuthFilter: family thu hồi không được tiếp tục dùng cookie; browser cookie cũ vẫn hoạt động. Test Agent recovery: nhiều màn hình chỉ refresh một lần, thiếu quyền không logout, offline giữ state, session expiry retry một lần, refresh bị thu hồi xóa shared state.
