package com.mystic.freyja

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class TargetActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // For testing purposes, we immediately return RESULT_OK back to MainActivity
        setResult(RESULT_OK)
        finish()
    }
}
