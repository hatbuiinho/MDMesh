package com.mdmesh.agent.ui

import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.Lifecycle
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.mdmesh.agent.MainActivity
import com.mdmesh.agent.R
import com.mdmesh.agent.apps.AppRequestsFragment
import com.mdmesh.agent.usage.AppUsageFragment
import com.mdmesh.agent.web.NetworkAccessFragment

/** One Activity, one navigation view. Destination views are retained with hide/show. */
internal class AgentNavigation(private val activity:MainActivity,home:View,initial:Int) {
    val bottomNavigation=BottomNavigationView(activity)
    private val overview=home
    private val container=FragmentContainerView(activity).apply {id=R.id.agent_destination_container}
    var selected=0
        private set
    init {
        val shell=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setBackgroundColor(activity.getColor(R.color.network_background))}
        val content=android.widget.FrameLayout(activity)
        content.addView(home);content.addView(container,android.widget.FrameLayout.LayoutParams(-1,-1))
        shell.addView(content,LinearLayout.LayoutParams(-1,0,1f))
        bottomNavigation.apply {
            id=R.id.agent_bottom_navigation
            setBackgroundColor(activity.getColor(R.color.network_surface))
            labelVisibilityMode=com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_LABELED
            listOf("Tổng quan" to R.drawable.nav_home,"Sử dụng" to R.drawable.nav_usage,"Ứng dụng" to R.drawable.nav_apps,"Mạng" to R.drawable.nav_network).forEachIndexed {index,item->menu.add(0,index+1,index,item.first).setIcon(item.second)}
            setOnItemSelectedListener {select(it.itemId-1);true}
        }
        shell.addView(bottomNavigation,LinearLayout.LayoutParams(-1,-2))
        activity.setContentView(shell)
        select(initial)
    }
    fun select(index:Int,incoming:Intent?=null) {
        if(index !in 0..3)return
        val manager=activity.supportFragmentManager
        if(manager.isStateSaved)return
        val transaction=manager.beginTransaction().setReorderingAllowed(true)
        manager.fragments.filter{it.tag?.startsWith("agent-tab-")==true}.forEach {transaction.hide(it).setMaxLifecycle(it,Lifecycle.State.STARTED)}
        var destination:Fragment?=null
        if(index!=0) {
            destination=manager.findFragmentByTag("agent-tab-$index") ?: when(index) {
                1->AppUsageFragment();2->AppRequestsFragment();else->NetworkAccessFragment()
            }.also {fragment->fragment.arguments=incoming?.extras;transaction.add(R.id.agent_destination_container,fragment,"agent-tab-$index")}
            transaction.show(destination).setMaxLifecycle(destination,Lifecycle.State.RESUMED)
        }
        transaction.commitNow()
        overview.visibility=if(index==0)View.VISIBLE else View.GONE
        container.visibility=if(index==0)View.GONE else View.VISIBLE
        selected=index
        if(bottomNavigation.selectedItemId!=index+1)bottomNavigation.menu.findItem(index+1).isChecked=true
        if(incoming!=null)when(destination) {is AppRequestsFragment->destination.acceptIntent(incoming);is NetworkAccessFragment->destination.acceptIntent(incoming)}
        activity.updateNavigationSecurity()
    }
}
