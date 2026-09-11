package com.pixlory.color.by.number.activities

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.airbnb.lottie.LottieDrawable
import com.pixlory.color.by.number.MyApplication
import com.pixlory.color.by.number.R
import com.pixlory.color.by.number.bases.BaseActivity
import com.pixlory.color.by.number.data.Realm
import com.pixlory.color.by.number.data.RealmCatalog
import com.pixlory.color.by.number.databinding.ActivityRealmFullScreenBinding
import com.pixlory.color.by.number.dialog.SavePicSuccessDialog
import com.pixlory.color.by.number.dialog.SavingDialog
import com.pixlory.color.by.number.utils.ImageSaver
import com.pixlory.color.by.number.utils.LottieFrameRenderer
import com.pixlory.color.by.number.utils.SharedPrefManager
import com.pixlory.color.by.number.utils.setOnUnDoubleClick
import com.pixlory.color.by.number.utils.showToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class RealmFullScreenActivity : BaseActivity<ActivityRealmFullScreenBinding>(
    ActivityRealmFullScreenBinding::inflate
) {

    override val shouldMonitorNetwork = true

    companion object {
        private const val EXTRA_REALM_ID = "REALM_ID"
        private const val EXTRA_PROGRESS = "PROGRESS"
        private const val TAG = "RealmFullScreen"

        /**
         * [progress] là vị trí animation đang chạy ở màn trước (0..1) để mở full screen không
         * bị giật về đầu.
         */
        fun newIntent(context: Context, realmId: String, progress: Float): Intent =
            Intent(context, RealmFullScreenActivity::class.java)
                .putExtra(EXTRA_REALM_ID, realmId)
                .putExtra(EXTRA_PROGRESS, progress)
    }

    private lateinit var realm: Realm
    private var isSavingImage = false
    private var savingImageJob: Job? = null
    private var loadRealmJob: Job? = null
    private var savingDialog: SavingDialog? = null
    private val requestedRealmId: String
        get() = intent.getStringExtra(EXTRA_REALM_ID) ?: RealmCatalog.default.id
    private val appContainer by lazy {
        (application as MyApplication).appContainer
    }

    override fun initData() {
        val realmId = requestedRealmId
        realm = RealmCatalog.findById(realmId)
            ?: RealmCatalog.default
    }

    override fun initView() {
        binding.lavRealmBackground.addLottieOnCompositionLoadedListener {
            updateDownloadButton(enabled = true)
        }
        renderRealm()
        loadRealm()
    }

    private fun renderRealm() {
        updateDownloadButton(enabled = false)
        val fallbackAnimationRes = realm.animationRes
        binding.lavRealmBackground.apply {
            if (!realm.animationUrl.isNullOrBlank()) {
                setFailureListener { error ->
                    Log.w(TAG, "Remote realm animation failed; using bundled fallback", error)
                    if (fallbackAnimationRes != 0) {
                        setFailureListener(null)
                        updateDownloadButton(enabled = false)
                        setAnimation(fallbackAnimationRes)
                        progress = intent.getFloatExtra(EXTRA_PROGRESS, 0f).coerceIn(0f, 1f)
                        repeatCount = LottieDrawable.INFINITE
                        resumeAnimation()
                    }
                }
                setAnimationFromUrl(realm.animationUrl)
            } else {
                setFailureListener(null)
                setAnimation(realm.animationRes)
            }
            progress = intent.getFloatExtra(EXTRA_PROGRESS, 0f).coerceIn(0f, 1f)
            repeatCount = LottieDrawable.INFINITE
            // resumeAnimation() chạy tiếp từ frame hiện tại; playAnimation() sẽ tua về đầu.
            resumeAnimation()
        }
        updateSelectButton()
    }

    private fun updateDownloadButton(enabled: Boolean) = with(binding.btnDownload) {
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.5f
    }

    private fun loadRealm() {
        loadRealmJob?.cancel()
        loadRealmJob = lifecycleScope.launch {
            val loadedRealm = try {
                appContainer.realmRepository.loadRealm(requestedRealmId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (loadedRealm != null && loadedRealm != realm) {
                realm = loadedRealm
                renderRealm()
            }
        }
    }

    override fun initActionView() {
        binding.btnBack.setOnUnDoubleClick { finish() }
        binding.btnDownload.setOnUnDoubleClick { saveCurrentFrame() }
        binding.btnSelect.setOnUnDoubleClick {
            SharedPrefManager.selectedRealmId = RealmCatalog.normalizeId(realm.id)
            updateSelectButton()
        }
    }

    private fun updateSelectButton() = with(binding.btnSelect) {
        val isSelectedRealm = RealmCatalog.idsMatch(SharedPrefManager.selectedRealmId, realm.id)
        if (isSelectedRealm) {
            text = getString(R.string.selected)
            setTextColor(ContextCompat.getColor(this@RealmFullScreenActivity, R.color.green_500))
            setBackgroundResource(R.drawable.bg_gray_green)
            setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_tick_green, 0)
        } else {
            text = getString(R.string.select)
            setTextColor(ContextCompat.getColor(this@RealmFullScreenActivity, R.color.white))
            setBackgroundResource(R.drawable.bg_gradient)
            setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
        }
    }

    private fun saveCurrentFrame() {
        // Render + nén PNG mất vài trăm ms, chặn bấm chồng để không ghi ra nhiều file trùng.
        if (isSavingImage) return
        val composition = binding.lavRealmBackground.composition ?: run {
            showToast(getString(R.string.please_wait))
            return
        }
        isSavingImage = true
        showSavingDialog()

        val progress = binding.lavRealmBackground.progress
        val metrics = resources.displayMetrics
        val aspectRatio = metrics.widthPixels.toFloat() / metrics.heightPixels.toFloat()
        val displayName = "Pixlory_Realm_${realm.id}_${System.currentTimeMillis()}"

        savingImageJob = lifecycleScope.launch {
            var wasCancelledByUser = false
            val saved = try {
                val renderResult = LottieFrameRenderer.renderFrame(
                    composition = composition,
                    progress = progress,
                    targetAspectRatio = aspectRatio
                )
                val bitmap = renderResult.getOrNull()

                if (bitmap == null) {
                    Log.e(TAG, "Could not render the displayed realm frame", renderResult.exceptionOrNull())
                    false
                } else {
                    try {
                        val saveResult = ImageSaver.saveBitmapToGallery(
                            applicationContext,
                            bitmap,
                            displayName
                        )
                        saveResult.exceptionOrNull()?.let { error ->
                            Log.e(TAG, "Could not save the rendered realm frame", error)
                        }
                        saveResult.isSuccess
                    } finally {
                        bitmap.recycle()
                    }
                }
            } catch (e: CancellationException) {
                wasCancelledByUser = true
                false
            } finally {
                isSavingImage = false
                savingImageJob = null
                dismissSavingDialog()
            }

            if (saved) {
                showSaveSuccessDialog()
            } else if (!wasCancelledByUser) {
                showToast(getString(R.string.download_failed))
            }
        }
    }

    private fun showSavingDialog() {
        if (supportFragmentManager.isStateSaved) return
        savingDialog = SavingDialog.newInstance(R.string.picture_is_being_saved_to_your_device).apply {
            onClose = {
                savingImageJob?.cancel()
                showToast(getString(R.string.download_failed))
            }
        }
        savingDialog?.show(supportFragmentManager, SavingDialog.TAG)
    }

    private fun dismissSavingDialog() {
        savingDialog?.dismissAllowingStateLoss()
        savingDialog = null
    }

    private fun showSaveSuccessDialog() {
        binding.root.post {
            showDialogOnce(SavePicSuccessDialog.TAG) {
                SavePicSuccessDialog.newInstance(R.string.picture_was_saved_to_your_device)
            }
        }
    }

    override fun onDestroy() {
        loadRealmJob?.cancel()
        savingImageJob?.cancel()
        loadRealmJob = null
        savingImageJob = null
        super.onDestroy()
    }
}
