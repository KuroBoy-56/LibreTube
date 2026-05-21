package com.github.libretube.helpers

import android.content.Context
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.PopupMenu
import androidx.core.view.forEach
import androidx.core.view.isGone
import com.github.libretube.R
import com.github.libretube.constants.PreferenceKeys
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationBarView

object NavBarHelper {

    fun hasTabs(): Boolean = true

    fun getNavBarItems(context: Context): List<MenuItem> {
        val p = PopupMenu(context, null)
        MenuInflater(context).inflate(R.menu.bottom_menu, p.menu)

        val navBarItems = mutableListOf<MenuItem>()

        p.menu.forEach { item ->
            // CIRUGÍA MAESTRA: Forzamos la visibilidad de los botones en el código fuente
            when (item.itemId) {
                R.id.downloadsFragment -> item.isVisible = false // Oculta Descargas para siempre
                R.id.trendsFragment -> item.isVisible = true     // Activa Tendencias para siempre
                else -> item.isVisible = true                    // Mantiene Inicio, Suscripciones y Biblioteca
            }
            navBarItems.add(item)
        }
        return navBarItems
    }

    private fun getDefaultNavBarItems(context: Context): List<MenuItem> {
        return getNavBarItems(context)
    }

    fun setNavBarItems(items: List<MenuItem>, context: Context) {
        // La dejamos vacía a propósito.
        // Esto bloquea cualquier intento del sistema de cambiar tu barra premium.
    }

    fun applyNavBarStyle(bottomNav: BottomNavigationView): Int {
        val labelVisibilityMode = when (
            PreferenceHelper.getString(PreferenceKeys.LABEL_VISIBILITY, "selected")
        ) {
            "always" -> NavigationBarView.LABEL_VISIBILITY_LABELED
            "selected" -> NavigationBarView.LABEL_VISIBILITY_SELECTED
            "never" -> NavigationBarView.LABEL_VISIBILITY_UNLABELED
            else -> NavigationBarView.LABEL_VISIBILITY_AUTO
        }
        bottomNav.labelVisibilityMode = labelVisibilityMode

        val navBarItems = getNavBarItems(bottomNav.context)

        val menuItems = mutableListOf<MenuItem>()
        navBarItems.forEach {
            menuItems.add(bottomNav.menu.findItem(it.itemId))
            bottomNav.menu.removeItem(it.itemId)
        }

        navBarItems.forEach { navBarItem ->
            if (navBarItem.isVisible) {
                val menuItem = menuItems.first { it.itemId == navBarItem.itemId }
                bottomNav.menu.add(
                    menuItem.groupId,
                    menuItem.itemId,
                    Menu.NONE,
                    menuItem.title
                ).icon = menuItem.icon
            }
        }

        if (navBarItems.none { it.isVisible }) bottomNav.isGone = true

        return getStartFragmentId(bottomNav.context)
    }

    fun getStartFragmentId(context: Context): Int {
        val pref = PreferenceHelper.getInt(PreferenceKeys.START_FRAGMENT, Int.MAX_VALUE)
        val visibleItems = getNavBarItems(context).filter { it.isVisible }

        return if (pref == Int.MAX_VALUE || pref >= visibleItems.size) {
            visibleItems.firstOrNull()?.itemId ?: R.id.homeFragment
        } else {
            visibleItems[pref].itemId
        }
    }

    fun setStartFragment(context: Context, itemId: Int) {
        val visibleItems = getNavBarItems(context).filter { it.isVisible }
        val index = visibleItems.indexOfFirst { it.itemId == itemId }
        PreferenceHelper.putInt(PreferenceKeys.START_FRAGMENT, if (index >= 0) index else 0)
    }
}