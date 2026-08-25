package com.example.baseproject.activities

import android.content.Intent
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.baseproject.MyApplication
import com.example.baseproject.adapters.RealmRoadAdapter
import com.example.baseproject.adapters.RealmRoadItem
import com.example.baseproject.bases.BaseActivity
import com.example.baseproject.data.Realm
import com.example.baseproject.data.RealmCatalog
import com.example.baseproject.data.repository.AchievementEvent
import com.example.baseproject.data.repository.PaintDropStats
import com.example.baseproject.databinding.ActivityRealmRoadBinding
import com.example.baseproject.dialog.AreaLockedDialog
import com.example.baseproject.dialog.NeedMorePaintDialog
import com.example.baseproject.dialog.NewAreaUnlockedDialog
import com.example.baseproject.dialog.PaintDropInfoDialog
import com.example.baseproject.utils.AppThemeManager
import com.example.baseproject.utils.SharedPrefManager
import com.example.baseproject.utils.setOnUnDoubleClick

class RealmRoadActivity : BaseActivity<ActivityRealmRoadBinding>(ActivityRealmRoadBinding::inflate) {

    private val appContainer by lazy {
        (application as MyApplication).appContainer
    }

    private val adapter by lazy {
        RealmRoadAdapter(
            onRealmClick = {},
            onRealmViewClick = { realm ->
                openRealmFullScreen(realm)
            },
            onUnlockClick = ::unlockRealm,
            onNeedMorePaintClick = { showNeedMorePaintDialog() },
            onLockedClick = { showAreaLockedDialog() },
        )
    }

    override fun initData() {
    }

    override fun initView() {
        AppThemeManager.applyFullBackground(binding.main)
        binding.rvRealmRoad.layoutManager = LinearLayoutManager(this)
        binding.rvRealmRoad.adapter = adapter
        renderRealmRoad()
    }

    override fun initActionView() {
        binding.btnBack.setOnUnDoubleClick {
            finish()
        }

        binding.btnPaintDropCount.setOnUnDoubleClick {
            showPaintDropInfoDialog()
        }
        binding.tvPaintDropCount.setOnUnDoubleClick {
            showPaintDropInfoDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        AppThemeManager.applyFullBackground(binding.main)
        renderRealmRoad()
    }

    private fun buildRealmRoadItems(): List<RealmRoadItem> {
        val currentPaintDrops = currentPaintDropStats().paintDrops
        val unlockedRealmIds = appContainer.paintDropRepository.loadUnlockedRealmIds()
        val selectedRealmId = SharedPrefManager.selectedRealmId
        val realms = RealmCatalog.realms

        return realms.mapIndexed { index, realm ->
            val isUnlocked = realm.unlockCost == 0 || realm.id in unlockedRealmIds
            val isPreviousRealmUnlocked = index > 0 && realms[index - 1].let { previousRealm ->
                previousRealm.unlockCost == 0 || previousRealm.id in unlockedRealmIds
            }
            RealmRoadItem(
                realm = realm,
                collectedPaintDrops = currentPaintDrops,
                isUnlocked = isUnlocked,
                isReadyToUnlock = !isUnlocked && currentPaintDrops >= realm.unlockCost,
                isNextLockedRealm = !isUnlocked && isPreviousRealmUnlocked,
                isSelected = isUnlocked && realm.id == selectedRealmId,
                showDownArrow = index < realms.lastIndex,
            )
        }
    }

    private fun renderRealmRoad() {
        val stats = currentPaintDropStats()
        binding.tvPaintDropCount.text = stats.paintDrops.toString()
        adapter.submitList(buildRealmRoadItems())
    }

    private fun showPaintDropInfoDialog() {
        showDialogOnce(PaintDropInfoDialog.TAG) {
            PaintDropInfoDialog().apply {
                stats = currentPaintDropStats()
            }
        }
    }

    private fun showAreaLockedDialog() {
        showDialogOnce(AreaLockedDialog.TAG) {
            AreaLockedDialog()
        }
    }

    private fun showNeedMorePaintDialog() {
        showDialogOnce(NeedMorePaintDialog.TAG) {
            NeedMorePaintDialog().apply {
                onGoToLibrary = {
                    openLibrary()
                }
            }
        }
    }

    private fun openLibrary() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.EXTRA_SELECTED_TAB, MainActivity.TAB_LIBRARY)
            }
        )
        finish()
    }

    private fun unlockRealm(realm: Realm) {
        appContainer.paintDropRepository.unlockRealm(realm.id)
        appContainer.achievementRepository.track(AchievementEvent.RealmUnlocked(realm.id))
        renderRealmRoad()
        showDialogOnce(NewAreaUnlockedDialog.TAG) {
            NewAreaUnlockedDialog().apply {
                this.realm = realm
                onGoToColorRealm = {
                    openRealmFullScreen(realm)
                }
            }
        }
    }

    private fun openRealmFullScreen(realm: Realm) {
        startActivity(
            RealmFullScreenActivity.newIntent(
                context = this,
                realmId = realm.id,
                progress = 0f,
            )
        )
    }

    private fun currentPaintDropStats(): PaintDropStats {
        val stats = appContainer.paintDropRepository.loadStats()
        val unlockedRealmIds = appContainer.paintDropRepository.loadUnlockedRealmIds()
        val unlockedAreas = RealmCatalog.realms.count { realm ->
            realm.unlockCost == 0 || realm.id in unlockedRealmIds
        }
        return stats.copy(areasUnlocked = unlockedAreas)
    }
}
