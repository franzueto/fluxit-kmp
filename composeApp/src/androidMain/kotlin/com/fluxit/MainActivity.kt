package com.fluxit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.fluxit.data.AndroidPhotoPicker
import com.fluxit.data.PhotoPicker
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val photoPicker: PhotoPicker by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        (photoPicker as AndroidPhotoPicker).register(this)
        setContent {
            App()
        }
    }
}
