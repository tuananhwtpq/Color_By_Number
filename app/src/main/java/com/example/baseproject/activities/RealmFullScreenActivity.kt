package com.example.baseproject.activities

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.baseproject.R
import com.example.baseproject.bases.BaseActivity
import com.example.baseproject.data.Realm
import com.example.baseproject.data.RealmCatalog
import com.example.baseproject.databinding.ActivityRealmFullScreenBinding
import com.example.baseproject.dialog.SavePicSuccessDialog
import com.example.baseproject.dialog.SavingDialog
import com.example.baseproject.utils.ImageSaver
import com.example.baseproject.utils.LottieFrameRenderer
import com.example.baseproject.utils.SharedPrefManager
import com.example.baseproject.utils.setOnUnDoubleClick
import com.example.baseproject.utils.showToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class RealmFullScreenActivity : BaseActivity<ActivityRealmFullScreenBinding>(
    ActivityRealmFullScreenBinding::inflate
) {

    companion object {
        private const val EXTRA_REALM_ID = "REALM_ID"
        private const val EXTRA_PROGRESS = "PROGRESS"

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
    private var savingDialog: SavingDialog? = null

    override fun initData() {
        realm = RealmCatalog.findById(intent.getStringExtra(EXTRA_REALM_ID)) ?: RealmCatalog.default
    }

    override fun initView() {
        binding.lavRealmBackground.apply {
            setAnimation(realm.animationRes)
            progress = intent.getFloatExtra(EXTRA_PROGRESS, 0f).coerceIn(0f, 1f)
            // resumeAnimation() chạy tiếp từ frame hiện tại; playAnimation() sẽ tua về đầu.
            resumeAnimation()
        }
        updateSelectButton()
    }

    override fun initActionView() {
        binding.btnBack.setOnUnDoubleClick { finish() }
        binding.btnDownload.setOnUnDoubleClick { saveCurrentFrame() }
        binding.btnSelect.setOnUnDoubleClick {
            SharedPrefManager.selectedRealmId = realm.id
            updateSelectButton()
        }
    }

    private fun updateSelectButton() = with(binding.btnSelect) {
        val isSelectedRealm = SharedPrefManager.selectedRealmId == realm.id
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
        isSavingImage = true
        showSavingDialog()

        val progress = binding.lavRealmBackground.progress
        val metrics = resources.displayMetrics
        val aspectRatio = metrics.widthPixels.toFloat() / metrics.heightPixels.toFloat()
        val displayName = "Pixlory_Realm_${realm.id}_${System.currentTimeMillis()}"

        savingImageJob = lifecycleScope.launch {
            var wasCancelledByUser = false
            val saved = try {
                val bitmap = LottieFrameRenderer.renderFrame(
                    context = applicationContext,
                    animationRes = realm.animationRes,
                    progress = progress,
                    targetAspectRatio = aspectRatio
                ).getOrNull()

                if (bitmap == null) {
                    false
                } else {
                    try {
                        ImageSaver.saveBitmapToGallery(applicationContext, bitmap, displayName)
                            .isSuccess
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
}
