package com.example.malnutritiondetector2

import android.content.res.AssetManager
import org.pytorch.Module
import java.io.File
import java.io.FileOutputStream

class MuacTorchModule(assetManager: AssetManager) {

    val module: Module

    init {
        // Load the MUAC detection model
        val file = File.createTempFile("muac_model_mobile", ".pt")
        assetManager.open("muac_model_mobile.pt").use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        }
        module = Module.load(file.absolutePath)
    }
}