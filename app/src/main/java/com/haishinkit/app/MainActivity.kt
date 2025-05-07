package com.haishinkit.app

import android.os.Bundle
import android.view.MenuItem
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity :
    AppCompatActivity(),
    BottomNavigationView.OnNavigationItemSelectedListener {
    private var fragment: Fragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val navigation = findViewById<BottomNavigationView>(R.id.navigation)
        navigation.setOnNavigationItemSelectedListener(this)

        fragment = CameraTabFragment.newInstance()
        val transaction = supportFragmentManager.beginTransaction()
        transaction.replace(R.id.content, fragment as Fragment).commit()
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.navigation_home -> {
                fragment = CameraTabFragment.newInstance()
            }

            R.id.navigation_mediaprojection -> {
                fragment = MediaProjectionTabFragment.newInstance()
            }

            R.id.navigation_playback -> {
                fragment = PlaybackTabFragment.newInstance()
            }

            else -> {
            }
        }
        val transaction = supportFragmentManager.beginTransaction()
        transaction.replace(R.id.content, fragment as Fragment).commit()
        return true
    }
}
