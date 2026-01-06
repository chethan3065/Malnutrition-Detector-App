package com.example.malnutritiondetector2

import android.content.res.AssetManager
import org.pytorch.Module
import java.io.File
import java.io.FileOutputStream

class TorchModule(assetManager: AssetManager) {

    val module: Module

    init {
        // Load the fusion model for malnutrition prediction
        val file = File.createTempFile("fusion_model_mobile", ".pt")

        // Try with the exact filename you have in assets
        val modelFileName = try {
            // First try without the space and parentheses
            if (assetManager.list("")?.contains("fusion_model_mobile.pt") == true) {
                "fusion_model_mobile.pt"
            } else {
                // Fall back to your original filename
                "fusion_model_mobile (1).pt"
            }
        } catch (e: Exception) {
            "fusion_model_mobile (1).pt"
        }

        assetManager.open(modelFileName).use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        }
        module = Module.load(file.absolutePath)
    }
}