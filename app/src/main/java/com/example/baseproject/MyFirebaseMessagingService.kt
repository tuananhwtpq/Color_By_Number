package com.example.baseproject


//import android.util.Log
//import com.google.firebase.messaging.FirebaseMessagingService
//import com.google.firebase.messaging.RemoteMessage
//
//class MyFirebaseMessagingService : FirebaseMessagingService() {
//    val TAG = "MyFirebaseMessagingService"
//
//    override fun onMessageReceived(message: RemoteMessage) {
//        super.onMessageReceived(message)
//        if (message.data.isNotEmpty()) {
//            Log.e(TAG, "onMessageReceived: ")
//
//        }
//
//        message.notification?.let {
//            Log.e(TAG, "Message Notification Body: ${it.body}")
//
//        }
//    }
//
//    @Deprecated("Deprecated in Java")
//    override fun onNewToken(token: String) {
//        super.onNewToken(token)
//        Log.e(TAG, "onNewToken: $token")
//
//    }
//
//    override fun onDeletedMessages() {
//        super.onDeletedMessages()
//        Log.e(TAG, "onDeletedMessages: ")
//    }
//
//    companion object {
//        private const val TAG = "MyFirebaseMsgService"
//    }
//
//}