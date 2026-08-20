package com.example.baseproject.data

/**
 * Một bộ sưu tập tranh hiển thị ở tab Collection.
 *
 * Cấu trúc field giữ nguyên để sau này Gson parse thẳng JSON từ server vào chính data class
 * này — nguồn dữ liệu (assets hay mạng) chỉ khác nhau ở tầng repository.
 */
data class AlbumCollection(
    /** Tên folder trong assets, vd "Cat moments"; sau này là id do server cấp. */
    val id: String,
    val title: String,
    val description: String? = null,
    /** "file:///android_asset/Collection/<name>/thumbnail.png" hoặc URL http — Glide load được cả hai. */
    val thumbnailUrl: String,
    val imageCount: Int
)
