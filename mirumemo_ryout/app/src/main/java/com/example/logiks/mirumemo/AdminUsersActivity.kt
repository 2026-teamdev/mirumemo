package com.example.logiks.mirumemo

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView

class AdminUsersActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_users)
        findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId = R.id.nav_admin_users
    }
}
