/******************************************************************************
 *                                                                            *
 * Copyright (C) 2021 by nekohasekai <sekai@neko.services>                    *
 * Copyright (C) 2021 by Max Lv <max.c.lv@gmail.com>                          *
 * Copyright (C) 2021 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.os.RemoteException
import android.view.MenuItem
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.preference.PreferenceDataStore
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.IShadowsocksService
import io.nekohasekai.sagernet.aidl.TrafficStats
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.widget.ListHolderListener
import io.nekohasekai.sagernet.widget.ServiceButton
import io.nekohasekai.sagernet.widget.StatsBar

class MainActivity : ThemedActivity(), SagerConnection.Callback,
    OnPreferenceDataStoreChangeListener,
    NavigationView.OnNavigationItemSelectedListener {

    lateinit var fab: ServiceButton
    lateinit var stats: StatsBar
    lateinit var drawer: DrawerLayout
    lateinit var coordinator: CoordinatorLayout
    lateinit var navigation: NavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.layout_main)

        coordinator = findViewById(R.id.coordinator)

        fab = findViewById(R.id.fab)
        stats = findViewById(R.id.stats)
        drawer = findViewById(R.id.drawer_layout)

        navigation = findViewById(R.id.nav_view)
        navigation.setNavigationItemSelectedListener(this)

        if (savedInstanceState == null) {
            navigation.menu.findItem(R.id.nav_configuration).isChecked = true
            displayFragment(ConfigurationFragment())
        }

        fab.setOnClickListener { if (state.canStop) SagerNet.stopService() else connect.launch(null) }
        stats.setOnClickListener { if (state == BaseService.State.Connected) stats.testConnection() }

        ViewCompat.setOnApplyWindowInsetsListener(coordinator, ListHolderListener)

        /* ViewCompat.setOnApplyWindowInsetsListener(fab) { view, insets ->
             view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                 bottomMargin = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom +
                         resources.getDimensionPixelOffset(R.dimen.mtrl_bottomappbar_fab_bottom_margin)
             }
             insets
         }*/

        changeState(BaseService.State.Idle)
        connection.connect(this, this)
        DataStore.configurationStore.registerChangeListener(this)
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        if (item.isChecked) drawer.closeDrawers() else {
            when (item.itemId) {
                R.id.nav_configuration -> {
                    displayFragment(ConfigurationFragment())
                    // request stats update
                    connection.bandwidthTimeout = connection.bandwidthTimeout
                }
                R.id.nav_group -> {
                    displayFragment(GroupFragment())
                }
                R.id.nav_route -> {
                    displayFragment(RouteFragment())
                }
                R.id.nav_settings -> {
                    displayFragment(SettingsFragment())
                }
                R.id.nav_about -> {
                    displayFragment(AboutFragment())
                }
                else -> return false
            }
            item.isChecked = true
        }
        return true
    }

    fun displayFragment(fragment: ToolbarFragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_holder, fragment)
            .commitAllowingStateLoss()
        drawer.closeDrawers()
    }


    var state = BaseService.State.Idle

    private fun changeState(
        state: BaseService.State,
        msg: String? = null,
        animate: Boolean = false,
    ) {
        fab.changeState(state, this.state, animate)
        stats.changeState(state)
        if (msg != null) snackbar(getString(R.string.vpn_error, msg)).show()
        this.state = state
    }

    fun snackbar(text: CharSequence = ""): Snackbar {
        return Snackbar.make(coordinator, text, Snackbar.LENGTH_LONG).apply {
            anchorView = fab
        }
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        changeState(state, msg, true)
    }

    val connection = SagerConnection(true)
    override fun onServiceConnected(service: IShadowsocksService) = changeState(
        try {
            BaseService.State.values()[service.state]
        } catch (_: RemoteException) {
            BaseService.State.Idle
        }
    )

    override fun onServiceDisconnected() = changeState(BaseService.State.Idle)
    override fun onBinderDied() {
        connection.disconnect(this)
        connection.connect(this, this)
    }

    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) {
        if (it) snackbar().setText(R.string.vpn_permission_denied).show()
    }

    override fun trafficUpdated(profileId: Long, stats: TrafficStats) {
        if (profileId != 0L) this@MainActivity.stats.updateTraffic(
            stats.txRateProxy, stats.rxRateProxy
        )
        runOnDefaultDispatcher {
            ProfileManager.postTrafficUpdated(profileId, stats)
        }
    }

    override fun trafficPersisted(profileId: Long) {
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(profileId)
        }
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        when (key) {
            Key.PROXY_APPS, Key.BYPASS_MODE, Key.INDIVIDUAL -> {
                if (state.canStop) {
                    snackbar(getString(R.string.restart)).setAction(R.string.apply) {
                        SagerNet.reloadService()
                    }.show()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        connection.bandwidthTimeout = 1000
    }

    override fun onStop() {
        connection.bandwidthTimeout = 0
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        DataStore.configurationStore.unregisterChangeListener(this)
        connection.disconnect(this)
    }

}