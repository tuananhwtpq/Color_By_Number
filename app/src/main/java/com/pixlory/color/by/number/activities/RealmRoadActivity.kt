package com.pixlory.color.by.number.activities

import android.content.Intent
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.pixlory.color.by.number.MyApplication
import com.pixlory.color.by.number.adapters.RealmRoadAdapter
import com.pixlory.color.by.number.adapters.RealmRoadItem
import com.pixlory.color.by.number.bases.BaseActivity
import com.pixlory.color.by.number.data.Realm
import com.pixlory.color.by.number.data.RealmCatalog
import com.pixlory.color.by.number.data.repository.AchievementEvent
import com.pixlory.color.by.number.data.repository.PaintDropStats
import com.pixlory.color.by.number.databinding.ActivityRealmRoadBinding
import com.pixlory.color.by.number.dialog.AreaLockedDialog
import com.pixlory.color.by.number.dialog.NeedMorePaintDialog
import com.pixlory.color.by.number.dialog.NewAreaUnlockedDialog
import com.pixlory.color.by.number.dialog.PaintDropInfoDialog
import com.pixlory.color.by.number.utils.AppThemeManager
import com.pixlory.color.by.number.utils.SharedPrefManager
import com.pixlory.color.by.number.utils.setOnUnDoubleClick
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
    private var realms: List<Realm> = RealmCatalog.realms
    private var loadRealmsJob: Job? = null

    override fun initData() {
    }

    override fun initView() {
        AppThemeManager.applyFullBackground(binding.main)
        binding.rvRealmRoad.layoutManager = LinearLayoutManager(this)
        binding.rvRealmRoad.adapter = adapter
        renderRealmRoad()
        loadRemoteRealms()
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
        loadRemoteRealms()
    }

    private fun buildRealmRoadItems(): List<RealmRoadItem> {
        val currentPaintDrops = currentPaintDropStats().paintDrops
        val unlockedRealmIds = appContainer.paintDropRepository.loadUnlockedRealmIds()
        val selectedRealmId = SharedPrefManager.selectedRealmId

        return realms.mapIndexed { index, realm ->
            val isUnlocked = realm.unlockCost == 0 || realm.idMatchesAny(unlockedRealmIds)
            val isPreviousRealmUnlocked = index > 0 && realms[index - 1].let { previousRealm ->
                previousRealm.unlockCost == 0 || previousRealm.idMatchesAny(unlockedRealmIds)
            }
            RealmRoadItem(
                realm = realm,
                collectedPaintDrops = currentPaintDrops,
                isUnlocked = isUnlocked,
                isReadyToUnlock = !isUnlocked && currentPaintDrops >= realm.unlockCost,
                isNextLockedRealm = !isUnlocked && isPreviousRealmUnlocked,
                isSelected = isUnlocked && realm.idMatches(selectedRealmId),
                showDownArrow = index < realms.lastIndex,
            )
        }
    }

    private fun renderRealmRoad() {
        val stats = currentPaintDropStats()
        binding.tvPaintDropCount.text = stats.paintDrops.toString()
        adapter.submitList(buildRealmRoadItems())
    }

    private fun loadRemoteRealms() {
        loadRealmsJob?.cancel()
        loadRealmsJob = lifecycleScope.launch {
            val loadedRealms = try {
                appContainer.realmRepository.loadRealms()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            if (loadedRealms.isNotEmpty()) {
                realms = loadedRealms
                renderRealmRoad()
            }
        }
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
        val unlockedAreas = realms.count { realm ->
            realm.unlockCost == 0 || realm.idMatchesAny(unlockedRealmIds)
        }
        return stats.copy(areasUnlocked = unlockedAreas)
    }

    private fun Realm.idMatches(otherId: String?): Boolean =
        RealmCatalog.idsMatch(id, otherId)

    private fun Realm.idMatchesAny(otherIds: Set<String>): Boolean =
        otherIds.any { RealmCatalog.idsMatch(id, it) }

    override fun onDestroy() {
        loadRealmsJob?.cancel()
        loadRealmsJob = null
        super.onDestroy()
    }
}
