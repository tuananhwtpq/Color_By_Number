package com.example.baseproject.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri
import com.example.baseproject.R
import com.example.baseproject.models.LanguageModel
import com.example.baseproject.utils.Constants.HAWK_LANGUAGE_POSITION

object Common {

    fun setSelectedLanguage(language: LanguageModel) {
        SharedPrefManager.putObject(HAWK_LANGUAGE_POSITION, language)
    }

    fun getSelectedLanguage(): LanguageModel {
        return SharedPrefManager.getLanguage(HAWK_LANGUAGE_POSITION)
            ?: LanguageModel(R.drawable.ic_english, R.string.english, "en")
    }


    fun getLanguageList(): MutableList<LanguageModel> {
        val languageList = mutableListOf<LanguageModel>()
        languageList.add(LanguageModel(R.drawable.ic_english, R.string.english, "en"))
        languageList.add(LanguageModel(R.drawable.ic_hindi, R.string.hindi, "hi"))
        languageList.add(LanguageModel(R.drawable.ic_spanish, R.string.spanish, "es"))
        languageList.add(LanguageModel(R.drawable.ic_french, R.string.french, "fr"))
        languageList.add(LanguageModel(R.drawable.ic_arabic, R.string.arabic, "ar"))
        languageList.add(LanguageModel(R.drawable.ic_bengali, R.string.bengali, "bn"))
        languageList.add(LanguageModel(R.drawable.ic_russian, R.string.russian, "ru"))
        languageList.add(LanguageModel(R.drawable.ic_portuguese, R.string.portuguese, "pt"))
        languageList.add(LanguageModel(R.drawable.ic_indonesian, R.string.indonesian, "in"))
        languageList.add(LanguageModel(R.drawable.ic_german, R.string.german, "de"))
        languageList.add(LanguageModel(R.drawable.ic_italian, R.string.italian, "it"))
        languageList.add(LanguageModel(R.drawable.ic_korean, R.string.korean, "ko"))
        return languageList
    }

    fun gotoPrivacyPolicy(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Constants.PRIVACY_POLICY.toUri())
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun shareApp(context: Context) {
        try {
            val shareIntent = Intent(Intent.ACTION_SEND)
            shareIntent.setType("text/plain")
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "My application name")
            val shareMessage =
                "https://play.google.com/store/apps/details?id=${context.packageName}"
            shareIntent.putExtra(Intent.EXTRA_TEXT, shareMessage)
            context.startActivity(Intent.createChooser(shareIntent, "choose one"))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun feedbackApp(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = "mailto:".toUri()
                putExtra(Intent.EXTRA_EMAIL, arrayOf(context.getString(R.string.rating_email)))
                putExtra(Intent.EXTRA_SUBJECT, "Feedback ${context.getString(R.string.app_name)}")
                putExtra(Intent.EXTRA_TEXT, "")
            }
            context.startActivity(Intent.createChooser(intent, "Send Feedback"))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                context,
                context.getString(R.string.no_email_client_installed), Toast.LENGTH_SHORT
            ).show()
        }
    }
}