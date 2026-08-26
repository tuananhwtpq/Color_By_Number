package com.example.baseproject.data.remote

import retrofit2.Response

class RemoteApiException(message: String, cause: Throwable? = null) : Exception(message, cause)

fun <T> Response<T>.requireSuccessfulBody(endpoint: String): T {
    if (!isSuccessful) {
        throw RemoteApiException("$endpoint failed with HTTP ${code()}")
    }
    return body() ?: throw RemoteApiException("$endpoint returned an empty body")
}

