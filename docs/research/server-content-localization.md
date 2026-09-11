# Nghiên cứu: bản địa hoá dữ liệu nội dung từ server

**Phạm vi.** Báo cáo này chỉ thiết kế luồng hiển thị tên/mô tả do server cung cấp cho Category, Collection, Realm và Achievement. Nó không thay đổi mã nguồn. Mục tiêu là mọi nội dung nhìn thấy được dùng đúng ngôn ngữ người dùng đã chọn trong app, mà không đổi khoá tiến độ, ảnh đã tô, realm đã mở khoá hoặc achievement đã đạt.

## Kết luận

Hiện tại app **đã** bản địa hoá Android resources theo lựa chọn của người dùng, nhưng **chưa** bản địa hoá dữ liệu từ server. `BaseActivity` tạo `Context` theo `Common.getSelectedLanguage()` [BaseActivity.kt:48-50, 171-177](../../app/src/main/java/com/example/baseproject/bases/BaseActivity.kt#L48-L50), và lựa chọn này được lưu trước khi mở Main [LanguageViewModel.kt:39-48](../../app/src/main/java/com/example/baseproject/ui/language/LanguageViewModel.kt#L39-L48). Tuy nhiên, toàn bộ API content không nhận locale qua header hay query parameter [PixcolorApi.kt:7-39](../../app/src/main/java/com/example/baseproject/data/remote/PixcolorApi.kt#L7-L39), còn HTTP client chỉ cài timeout và logging [PixcolorApiClient.kt:15-38](../../app/src/main/java/com/example/baseproject/data/remote/PixcolorApiClient.kt#L15-L38).

Vì vậy, tên/mô tả server trả về hiện là một chuỗi duy nhất và sẽ được hiển thị nguyên văn bất kể app đang chọn `en`, `vi`, `ar`, v.v. Giải pháp nên chọn là: **server trả về nội dung đã resolve cho locale app yêu cầu; client luôn truyền locale đã chọn; mọi cache của chuỗi hiển thị phải phân vùng theo locale; mọi ID phục vụ nghiệp vụ phải bất biến và không dịch.**

## Các đường dữ liệu hiện có

| Khu vực UI | Chuỗi đang hiển thị | Đường đi hiện tại | Kết quả với dữ liệu remote |
|---|---|---|---|
| Library category tabs | `LevelConfig.categoryName` | Category API → `RemoteLevelMapper.levelSummaryToConfig(... groupName)` → `LibraryViewModel.categoryNames` → `LibraryFragment` | Chưa locale-aware; `RemoteGroupDto.displayName` lấy `title/name/id` đơn lẻ. [RemoteDtos.kt:5-22](../../app/src/main/java/com/example/baseproject/data/remote/RemoteDtos.kt#L5-L22), [RemoteLevelRepositoryImpl.kt:73-104](../../app/src/main/java/com/example/baseproject/data/repository/RemoteLevelRepositoryImpl.kt#L73-L104), [LibraryViewModel.kt:92-112](../../app/src/main/java/com/example/baseproject/ui/library/LibraryViewModel.kt#L92-L112), [LibraryFragment.kt:117-143](../../app/src/main/java/com/example/baseproject/fragments/LibraryFragment.kt#L117-L143) |
| Collection title/description | `AlbumCollection.title`, `description` | Collections API → `RemoteLevelMapper.collectionFromGroup` → `CollectionDetailActivity` | Chưa locale-aware; mapper copy thẳng `displayName` và `description`. [RemoteLevelMapper.kt:60-71](../../app/src/main/java/com/example/baseproject/data/remote/RemoteLevelMapper.kt#L60-L71), [CollectionDetailActivity.kt:97-125](../../app/src/main/java/com/example/baseproject/activities/CollectionDetailActivity.kt#L97-L125) |
| Realm / Realm Road | `Realm.name` (remote) hoặc `nameRes` (fallback local) | Realms API → mapper → `Realm.displayName(context)` → Fragment/adapter | Fallback asset được Android dịch qua `nameRes`, nhưng remote `name` luôn thắng nên không được dịch. [Realm.kt:13-27](../../app/src/main/java/com/example/baseproject/data/Realm.kt#L13-L27), [RemoteRealmMapper.kt:5-20](../../app/src/main/java/com/example/baseproject/data/remote/RemoteRealmMapper.kt#L5-L20), [RealmFragment.kt:69-110](../../app/src/main/java/com/example/baseproject/fragments/RealmFragment.kt#L69-L110), [RealmRoadAdapter.kt:52-60](../../app/src/main/java/com/example/baseproject/adapters/RealmRoadAdapter.kt#L52-L60) |
| Achievement list, detail và completed dialog | `AchievementDefinition.title`, `description` | Achievements API → `RemoteAchievementMapper` → repository → adapter/dialog | Chưa locale-aware; remote title/description có ưu tiên cao hơn `titleRes`/`descriptionRes`, vì thế chuỗi server không đổi theo locale. [RemoteAchievementMapper.kt:7-24](../../app/src/main/java/com/example/baseproject/data/remote/RemoteAchievementMapper.kt#L7-L24), [AchievementCatalog.kt:45-69](../../app/src/main/java/com/example/baseproject/data/AchievementCatalog.kt#L45-L69), [AchievementAdapter.kt:42-83](../../app/src/main/java/com/example/baseproject/adapters/AchievementAdapter.kt#L42-L83), [AchieveDetailDialog.kt:21-44](../../app/src/main/java/com/example/baseproject/dialog/AchieveDetailDialog.kt#L21-L44), [AchieveCompletedDialog.kt:32-53](../../app/src/main/java/com/example/baseproject/dialog/AchieveCompletedDialog.kt#L32-L53) |

`CollectionAdapter` hiện không bind title (chỉ thumbnail và count) [CollectionAdapter.kt:39-46](../../app/src/main/java/com/example/baseproject/adapters/CollectionAdapter.kt#L39-L46); nhưng `CollectionDetailActivity` đã hiển thị title/description nên hợp đồng server vẫn phải dịch cả hai.

## Ràng buộc không được phá

Tên hiển thị không thể là khoá định danh. Hiện `category` và `levelId` nằm trực tiếp trong khoá SharedPreferences của tiến độ [PaintingProgressRepositoryImpl.kt:10-63](../../app/src/main/java/com/example/baseproject/data/repository/PaintingProgressRepositoryImpl.kt#L10-L63), thumbnail local [ThumbnailRepositoryImpl.kt:12-18](../../app/src/main/java/com/example/baseproject/data/repository/ThumbnailRepositoryImpl.kt#L12-L18), paint drops [PaintDropRepositoryImpl.kt:27-41](../../app/src/main/java/com/example/baseproject/data/repository/PaintDropRepositoryImpl.kt#L27-L41), và achievement completion [AchievementRepositoryImpl.kt:62-75, 112-132](../../app/src/main/java/com/example/baseproject/data/repository/AchievementRepositoryImpl.kt#L62-L75). Với collection, code hiện tạo category key `Collection/<groupId>` [RemoteLevelMapper.kt:73-78](../../app/src/main/java/com/example/baseproject/data/remote/RemoteLevelMapper.kt#L73-L78). Dịch `groupId`, `realm.id`, `achievement.id`, `level.id` hoặc đổi chúng theo từng locale sẽ làm mất phần trăm tranh, thumbnail, lịch sử và trạng thái unlock.

Đặc biệt, `RemoteGroupDto.stableId` hiện là `slug ?: id` [RemoteDtos.kt:19-21](../../app/src/main/java/com/example/baseproject/data/remote/RemoteDtos.kt#L19-L21), trong khi API level được lọc theo `groupId` chính xác [RemoteLevelMetadataLoader.kt:23-35](../../app/src/main/java/com/example/baseproject/data/remote/RemoteLevelMetadataLoader.kt#L23-L35). Server cần cam kết một ID chuẩn không đổi để tránh khác biệt giữa `slug` và `id`; locale không được tham gia vào quyết định này.

## Hợp đồng server khuyến nghị

### 1. Locale theo request, không theo thiết bị

Mỗi request content gửi **locale đã chọn trong app**, không suy ra từ locale hệ điều hành. Dùng header chuẩn `Accept-Language`, ví dụ:

```http
GET /api/v1/categories
Accept-Language: vi, en;q=0.9
X-Content-Locale: vi
```

`X-Content-Locale` là khoá rõ ràng để backend log/validate; backend có thể chỉ cần một trong hai header nếu API contract quy định rõ. Server trả:

```http
Content-Language: vi
Vary: Accept-Language
ETag: "categories-vi-r42"
```

và body có metadata chung:

```json
{
  "success": true,
  "data": {
    "contentLocale": "vi",
    "fallbackLocale": "en",
    "revision": "r42",
    "categories": [ ... ]
  }
}
```

Client chỉ nhận một bản dịch đã được server resolve. Đây là phương án nên dùng cho danh sách lớn vì không tải toàn bộ 12 bản dịch về điện thoại. Nếu server cần gửi nhiều ngôn ngữ offline trong một response, dùng map `translations[locale]`, nhưng client vẫn phải chọn một locale trước khi map sang model UI; không nên để UI tự truy cập `title` mơ hồ.

### 2. Định dạng entity

Mọi entity giữ ID kỹ thuật bất biến và có phần presentation đã resolve:

```json
{
  "id": "cat_moments",
  "groupType": "COLLECTION",
  "sortOrder": 20,
  "title": "Khoảnh khắc mèo",
  "description": "Một bộ sưu tập ấm áp về những chú mèo.",
  "resolvedLocale": "vi",
  "fallbackUsed": false,
  "thumbnailPath": "...",
  "levelCount": 12
}
```

- **Category:** `id`, `title`, optional `description`; `id` là category/group ID được dùng để tải level và lưu progress.
- **Collection:** `id`, `title`, `description`, thumbnail, count; `id` là collection ID bất biến, không phải title/folder theo ngôn ngữ.
- **Realm:** `id`, `title`, animation/preview paths, unlock cost, sort order. Đổi remote field từ ý nghĩa mơ hồ `name` sang `title` là rõ ràng hơn; có thể duy trì `name` tạm thời để tương thích API v1.
- **Achievement:** `id`, `title`, `description`, rule type, `ruleRefId`, target count, icons. `ruleRefId` phải tham chiếu ID kỹ thuật của category/collection/realm, không bao giờ là title đã dịch.

`resolvedLocale` và `fallbackUsed` có giá trị quan trọng: client có thể ghi telemetry khi bản dịch vắng, và QA biết server đang trả tiếng Anh fallback thay vì bản dịch yêu cầu.

### 3. Quy tắc fallback thống nhất

Server resolve theo thứ tự:

1. exact requested locale (ví dụ `pt-BR`),
2. base language (`pt`),
3. default content locale (`en`),
4. ID kỹ thuật chỉ là fallback cuối cùng để không để trống UI và phải được theo dõi telemetry.

Không trộn title tiếng Việt và description tiếng Anh khi server có thể fallback ở cấp entity; nếu fallback từng field là bắt buộc, server phải báo rõ field nào fallback. Response phải có `Content-Language`/`resolvedLocale` thực tế, không nói là `vi` khi thực tế trả `en`.

Danh sách ngôn ngữ app đang lưu gồm `en`, `hi`, `es`, `fr`, `ar`, `bn`, `ru`, `pt`, `in`, `de`, `it`, `ko` [Common.kt:24-38](../../app/src/main/java/com/example/baseproject/utils/Common.kt#L24-L38). Trước rollout, cần chốt bảng canonical code giữa app và server; đặc biệt kiểm thử riêng Indonesian vì app hiện dùng `in`, còn API contract nên chỉ chấp nhận một code canonical nhất quán.

## Chiến lược client đề xuất

### Một nguồn locale cho cả resources và remote content

Tạo abstraction nhỏ, ví dụ `ContentLocaleProvider`, do `SettingsRepository` sở hữu:

- đọc locale đã lưu;
- cung cấp `StateFlow<ContentLocale>`;
- chuẩn hoá sang code server đã chốt;
- chỉ phát event khi code canonical thật sự đổi.

`BaseActivity` tiếp tục dùng locale này để tạo Android `Context`; Retrofit/OkHttp interceptor lấy **cùng nguồn** để thêm header cho mọi content endpoint. Không dùng `Locale.getDefault()` trong network interceptor: nó là trạng thái process và không phải hợp đồng lựa chọn ngôn ngữ của app.

Mỗi remote repository nhận `ContentLocale` (hoặc API đã được locale-scoped) và trả model đã resolve. Có thể giữ UI model là `String` cho lần triển khai đầu, nhưng cache entry phải mang `locale` và `revision`, ví dụ `LocalizedContent<T>(value, locale, revision)`, để không vô tình bind bản tiếng cũ sau khi người dùng đổi ngôn ngữ.

### Cache và refresh

Các cache hiện tại không an toàn khi thêm translation:

- Level metadata được giữ cả memory lẫn file `remote_level_metadata/levels.json` [AppContainer.kt:53-67](../../app/src/main/java/com/example/baseproject/app/AppContainer.kt#L53-L67), trong đó `categoryName` được persist và merge lại [RemoteLevelRepositoryImpl.kt:69-104](../../app/src/main/java/com/example/baseproject/data/repository/RemoteLevelRepositoryImpl.kt#L69-L104). Cache này phải tách thành `levels_<locale>.json`, hoặc tách metadata bất biến (chia sẻ) khỏi map name theo locale.
- Collection cache memory chỉ có một `cachedCollections` [RemoteCollectionRepositoryImpl.kt:28-33, 72-88](../../app/src/main/java/com/example/baseproject/data/repository/RemoteCollectionRepositoryImpl.kt#L28-L33); Realm tương tự `cachedRealms`/`cachedRealmsById` [RemoteRealmRepositoryImpl.kt:23-26](../../app/src/main/java/com/example/baseproject/data/repository/RemoteRealmRepositoryImpl.kt#L23-L26). Chúng phải là `Map<ContentLocale, …>` hoặc bị invalidate khi locale đổi.
- Achievement definitions được nạp lại khi mở màn nhưng không truyền locale [RemoteAchievementDefinitionProvider.kt:20-38](../../app/src/main/java/com/example/baseproject/data/repository/RemoteAchievementDefinitionProvider.kt#L20-L38). Provider cần locale-scoped response; trạng thái tiến độ `AchievementRepositoryImpl` vẫn dùng ID như cũ.

Ưu tiên **stale-while-revalidate theo từng locale**: render cache đúng locale ngay nếu có, request với ETag/revision nền, rồi thay list khi response mới khác revision. Không dùng cache tiếng Anh như cache hiển thị cho tiếng Việt, trừ khi server đánh dấu chính xác đó là fallback. Server/CDN phải gửi `Vary: Accept-Language` nếu có HTTP cache trung gian.

Khi người dùng đổi ngôn ngữ, phát event locale mới, huỷ request cũ, bind cache của locale mới (hoặc skeleton), request fresh mới và render lại các màn còn sống. Không xoá `PaintingProgress`, thumbnails, paint drops, unlocked realm IDs, reward status hoặc completed level sets vì chúng dùng ID kỹ thuật bất biến.

## Kế hoạch migration không phá dữ liệu

1. **Inventory server:** chốt canonical immutable IDs cho tất cả group/collection/realm/achievement/level và bảng legacy aliases. Kiểm tra `groupId` của `/groups/{type}/{id}/levels` trùng với ID client sẽ dùng.
2. **Thêm API v2 hoặc mở rộng v1 tương thích:** server nhận locale optional, mặc định `en`; trả `contentLocale`, `fallbackLocale`, `revision`, localized title/description. Không đổi tên hoặc giá trị ID hiện có.
3. **Thêm locale provider + interceptor:** một nơi đọc ngôn ngữ đã chọn và chuẩn hoá code cho server. Đảm bảo LanguageActivity ghi locale trước request Main, như flow hiện tại đã làm [LanguageViewModel.kt:39-48](../../app/src/main/java/com/example/baseproject/ui/language/LanguageViewModel.kt#L39-L48).
4. **Đổi DTO/mapper theo chiều từ transport sang domain:** tách `contentId` (bất biến) khỏi `displayTitle`/`displayDescription`; không để `title` trở thành input của `categoryKey`, progress hay achievement rule.
5. **Phân vùng/invalidate cache:** chuyển file levels cũ sang cache default locale một lần, hoặc bỏ cache presentation cũ và refetch. Chỉ metadata hiển thị bị invalidate; region metadata cần cho phần trăm tranh vẫn được giữ/migrate nguyên vẹn.
6. **Refresh UI sau đổi ngôn ngữ:** Library, Collection detail, Realm/Realm Road và Achievement screen subscribe locale event, reload đúng repository. Màn Paint đang mở không cần reload art/config chỉ vì title đổi.
7. **Quan sát rollout:** log `requestedLocale`, `resolvedLocale`, `fallbackUsed`, revision và missing-translation key; chỉ bật locale mới khi coverage đạt mức đã chốt.

## Rủi ro và kiểm thử nghiệm thu

| Rủi ro | Cách chặn / test |
|---|---|
| Dịch ID/groupId khiến % tranh về 0 hoặc thumbnail biến mất | Đổi `en → vi → en` sau khi tô dở và sau khi tô xong; kiểm tra cùng progress, history, thumbnail và timelapse. Các key hiện phụ thuộc category/level [PaintingProgressRepositoryImpl.kt:56-63](../../app/src/main/java/com/example/baseproject/data/repository/PaintingProgressRepositoryImpl.kt#L56-L63). |
| Response/cached list sai locale | Test cold start và đổi ngôn ngữ nhanh `en → vi → ar`; assert mọi title/description cùng `resolvedLocale`; huỷ response cũ hoặc bỏ qua nếu locale không còn current. |
| Cache CDN trả tiếng trước đó | Test header và `Vary: Accept-Language`, ETag khác theo locale; thêm integration test server. |
| Mapping `slug`/`id` không nhất quán | Test category/collection có slug khác id và gọi group levels; chọn một `contentId` duy nhất cho storage và routes. |
| Achievement rule bị dịch | Với `ArtworkInCategory` và `CollectionCompleted`, verify `ruleRefId` là immutable ID; đổi ngôn ngữ không đổi số achievement đã unlock. |
| Bản dịch thiếu/RTL | Test 12 locale đã hỗ trợ, fallback metadata, chuỗi dài, Arabic RTL, và date của completed dialog. Date hiện dựa trên `Locale.getDefault()` [AchieveCompletedDialog.kt:81-88](../../app/src/main/java/com/example/baseproject/dialog/AchieveCompletedDialog.kt#L81-L88), nên cần xác nhận nó nhận cùng locale canonical. |

## Quyết định đề xuất

Triển khai **server-resolved translation qua locale header**, không đưa toàn bộ dictionary translation vào từng response; giữ một `contentId` bất biến và cache presentation theo locale + revision. Đây là cách ít băng thông, hợp với luồng API hiện có, và quan trọng nhất là bảo toàn toàn bộ dữ liệu người dùng đã lưu theo `category/levelId`.
