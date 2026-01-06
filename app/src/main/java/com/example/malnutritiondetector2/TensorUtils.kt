package com.example.malnutritiondetector2

import android.graphics.Bitmap
import org.pytorch.Tensor

object TensorUtils {

    fun bitmapToFloat32Tensor(bitmap: Bitmap): Tensor {
        val targetWidth = 224
        val targetHeight = 224

        // Center-crop the bitmap to make it square
        val cropSize = minOf(bitmap.width, bitmap.height)
        val startX = (bitmap.width - cropSize) / 2
        val startY = (bitmap.height - cropSize) / 2
        val croppedBitmap = Bitmap.createBitmap(bitmap, startX, startY, cropSize, cropSize)

        // Resize to 224x224
        val resized = Bitmap.createScaledBitmap(croppedBitmap, targetWidth, targetHeight, true)

        val floatArray = FloatArray(3 * targetWidth * targetHeight)
        val pixels = IntArray(targetWidth * targetHeight)
        resized.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)

        for (i in 0 until targetWidth * targetHeight) {
            val px = pixels[i]
            // Normalize using standard ImageNet mean & std
            floatArray[i] = ((px shr 16 and 0xFF) / 255.0f - 0.485f) / 0.229f  // R
            floatArray[i + targetWidth * targetHeight] = ((px shr 8 and 0xFF) / 255.0f - 0.456f) / 0.224f  // G
            floatArray[i + 2 * targetWidth * targetHeight] = ((px and 0xFF) / 255.0f - 0.406f) / 0.225f  // B
        }

        return Tensor.fromBlob(floatArray, longArrayOf(1, 3, targetHeight.toLong(), targetWidth.toLong()))
    }
}
