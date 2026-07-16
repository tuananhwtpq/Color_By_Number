# TÀI LIỆU YÊU CẦU SẢN PHẨM (PRD) - ỨNG DỤNG TÔ MÀU THEO SỐ

**Dự án:** Ứng dụng Game Casual "Color by Number"
**Nền tảng:** Android (Smartphone & Tablet)
**Giai đoạn:** MVP (Minimum Viable Product) - Phiên bản lõi tập trung vào trải nghiệm sản phẩm.

---

## 1. MỤC TIÊU SẢN PHẨM (PRODUCT VISION & GOALS)

Tạo ra một ứng dụng giải trí, thư giãn (relaxing) thông qua việc tô màu theo số. Sản phẩm yêu cầu thao tác đơn giản, hình ảnh bắt mắt, mang lại cảm giác thỏa mãn (satisfying) cho người dùng khi hoàn thành một tác phẩm.

* **Mục tiêu cốt lõi:** Mang lại trải nghiệm tô màu mượt mà nhất (không giật lag khi zoom/pan), hình ảnh đa dạng, dễ thao tác trên mọi kích thước màn hình Android.
* **Cảm xúc mang lại:** Thư giãn, giảm stress, tập trung, và tự hào (khi chia sẻ tác phẩm).

---

## 2. CHUỖI VÒNG LẶP TRẢI NGHIỆM (CORE GAME LOOP)

Người dùng mở app ➡️ Lướt thư viện (Gallery) để chọn tranh ➡️ Phóng to/Di chuyển/Tô màu theo dải số (Paint) ➡️ Hoàn thành tác phẩm ➡️ Xem lại video tua nhanh quá trình tô (Timelapse) ➡️ Lưu về máy / Chia sẻ lên MXH ➡️ Quay lại Thư viện.

---

## 3. CHI TIẾT TÍNH NĂNG SẢN PHẨM (FEATURES DETAILS)

### 3.1. Tính năng Thư viện ảnh (Gallery & Home Screen)

Đây là màn hình chính khi người dùng mở ứng dụng. Cần hiển thị đa dạng nội dung để kích thích việc chọn tranh.

* **Phân loại danh mục (Categories Tab):**
* Hiển thị theo dạng tab ngang hoặc danh sách cuộn dọc.
* Các danh mục đề xuất: *Mới nhất (New/Daily), Động vật (Animals), Phong cảnh (Nature), Mandala, Hoa (Flowers), Nghệ thuật (Art), Nhân vật (Characters).*


* **Tranh hàng ngày (Daily Picks):** Làm nổi bật 1-2 bức tranh mới mỗi ngày ngay trên đầu trang để tạo thói quen quay lại (Retention) cho người dùng.
* **Thumbnail của tranh:** Phải hiển thị phiên bản ĐÃ TÔ MÀU (hoặc một phần có màu) cực kỳ bắt mắt để thu hút click. Có icon nhỏ báo hiệu mức độ khó (số lượng chi tiết/màu sắc).

### 3.2. Tính năng Quản lý Cá nhân (My Works)

Nơi lưu trữ tiến độ cá nhân.

* **Đang tiến hành (In Progress):** Danh sách các bức tranh đang tô dở. Hiển thị thanh tiến trình (Progress bar: ví dụ 45%).
* **Đã hoàn thành (Completed):** Thư viện các tác phẩm đã tô xong. Cho phép người dùng bấm vào để xem lại hoặc chia sẻ lại.

### 3.3. Trải nghiệm Tô màu (Core Painting Engine)

Đây là "linh hồn" của ứng dụng. Trải nghiệm phải hoàn hảo.

* **Thao tác điều hướng (Navigation):**
* **Pinch to Zoom:** Dùng 2 ngón tay để phóng to/thu nhỏ tranh một cách mượt mà.
* **Pan:** Dùng 1 hoặc 2 ngón tay để kéo/di chuyển khung vẽ khi đang ở trạng thái phóng to.


* **Dải màu (Color Palette):**
* Thanh cuộn ngang phía dưới màn hình chứa các vòng tròn/ô vuông màu sắc được đánh số (1, 2, 3...).
* **Tự động di chuyển (Auto-scroll):** Khi tô xong toàn bộ vùng của số 1, dải màu tự động focus và chuyển sang số 2.
* **Hiển thị tiến độ:** Ô màu nào đã tô xong 100% sẽ xuất hiện dấu tick (✔️) hoặc biến mất để giảm tải thị giác.


* **Tương tác tô màu (Coloring Mechanic):**
* **Highlight vùng cần tô:** Khi chọn màu số 1, tất cả các vùng trống mang số 1 trên tranh sẽ được đánh bóng (highlight bằng hoa văn pattern hoặc tô xám nhẹ) để người dùng dễ tìm kiếm.
* **Tap to Fill:** Chạm 1 lần vào vùng đánh số để đổ màu lập tức.
* **Hỗ trợ Haptic Feedback:** Rung nhẹ (vibration) khi đổ màu thành công để tăng độ "đã" (satisfying).


* **Công cụ hỗ trợ (Boosters/Hints):**
* **Bóng đèn Gợi ý (Hint):** Khi người dùng tìm không ra vùng số bị sót, bấm Hint sẽ tự động zoom camera vào vị trí sót và chớp nháy vùng đó.


* **Hành vi lỗi:** Nếu người dùng đang chọn màu số 1 nhưng bấm nhầm vào vùng số 2, màn hình vùng đó sẽ nháy đỏ nhẹ hoặc rung lên báo lỗi (không trừ điểm, chỉ báo hiệu).

### 3.4. Màn hình Hoàn thành (Completion & Celebration)

Khoảnh khắc trao phần thưởng cảm xúc cho người dùng.

* **Hiệu ứng chúc mừng:** Pháo giấy (Confetti) nổ trên màn hình khi mảng màu cuối cùng được tô.
* **Video Timelapse:** Tự động phát một đoạn video ngắn (3-5 giây) tua nhanh lại toàn bộ quá trình bức tranh được tô màu từ trắng đen sang hoàn thiện.
* **Chia sẻ & Lưu trữ:** Nút Save (Lưu ảnh vào thư viện thiết bị) và Nút Share (Gửi qua Facebook, Instagram, Zalo, Message...).

---

## 4. YÊU CẦU UI/UX (USER INTERFACE & EXPERIENCE)

* **Ngôn ngữ thiết kế (Design Language):** Tối giản (Minimalism), Clean UI. Sử dụng nền sáng (trắng hoặc xám nhạt) hoặc hỗ trợ Dark Mode để làm nổi bật màu sắc rực rỡ của bức tranh.
* **Khu vực an toàn (Safe Zone):** Đảm bảo dải màu (Color Palette) ở cạnh dưới không bị chồng lấn bởi các phím điều hướng hệ thống (Navigation Bar) hoặc thanh home swipe của các thiết bị Android đời mới.
* **Độ mượt (Fluidity):** Các Animation chuyển cảnh (từ Gallery vào màn hình Paint) phải mượt mà. Không có hiện tượng giật cục (stutter).

---

## 5. YÊU CẦU DATA & TÀI NGUYÊN (ASSET REQUIREMENTS)

Để bắt đầu, sản phẩm MVP cần có:

1. **Định dạng hình ảnh:** Sử dụng vector (SVG/JSON) hoặc ma trận Bitmap chất lượng cao tùy thuộc vào engine bạn chọn (mảng miếng hay pixel).
2. **Dữ liệu tranh khởi tạo:** Tối thiểu 50-100 bức tranh đa dạng độ khó (từ 10 màu/50 mảng đến 50 màu/1000 mảng) được tích hợp sẵn (bundle) vào app để người dùng có thể chơi ngay khi tải về (Offline mode).

---

## 6. LỘ TRÌNH PHÁT TRIỂN SẢN PHẨM (PHASE 1 - CORE MVP)

| Hạng mục | Mô tả công việc chi tiết | Tiêu chí nghiệm thu (Acceptance Criteria) |
| --- | --- | --- |
| **Engine Tô màu** | Xây dựng lõi vẽ tranh, xử lý Zoom/Pan, Highlight vùng màu. | Thao tác không bị lag. Đổ màu chuẩn xác. |
| **Trải nghiệm Tô** | Làm thanh Palette, logic tự động nhảy số, logic dấu tick khi hoàn thành. | Trạng thái hiển thị đồng bộ giữa tranh và thanh màu. |
| **Gallery & Storage** | Xây dựng màn hình Home, danh mục tranh. Lưu trạng thái tranh (đang tô, đã tô) xuống thiết bị. | Đóng app mở lại vẫn giữ nguyên tiến độ bức tranh đang tô dở. |
| **Celebration** | Làm hiệu ứng Confetti và tạo luồng Share ảnh cơ bản. | Lưu được ảnh hoàn thiện về thư viện (Gallery) của điện thoại. |