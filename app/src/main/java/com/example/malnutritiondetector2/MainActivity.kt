package com.example.malnutritiondetector2

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.google.android.material.textfield.TextInputEditText
import org.pytorch.IValue
import org.pytorch.Tensor
import java.io.InputStream
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var fusionModule: TorchModule
    private lateinit var muacModule: MuacTorchModule
    private lateinit var imagePreview: ImageView
    private lateinit var etHeight: TextInputEditText
    private lateinit var etWeight: TextInputEditText
    private lateinit var etAge: TextInputEditText
    private lateinit var tvResult: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var resultsCard: CardView
    private lateinit var placeholderLayout: LinearLayout

    private var selectedBitmap: Bitmap? = null
    private var detectedMuac: Float? = null
    private var modelsLoaded = false

    // Modern Activity Result API
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val inputStream: InputStream? = contentResolver.openInputStream(it)
                selectedBitmap = BitmapFactory.decodeStream(inputStream)

                // Display the image in preview (fully visible, not cropped)
                imagePreview.setImageBitmap(selectedBitmap)
                imagePreview.scaleType = ImageView.ScaleType.FIT_CENTER

                // Hide placeholder
                placeholderLayout.visibility = View.GONE

                // Clear previous result & inputs
                resetInputs()
            } catch (e: Exception) {
                Toast.makeText(this, "Error loading image: ${e.message}", Toast.LENGTH_SHORT).show()
                e.printStackTrace()
            }
        }
    }

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        bitmap?.let {
            selectedBitmap = it

            // Display the image in preview (fully visible, not cropped)
            imagePreview.setImageBitmap(selectedBitmap)
            imagePreview.scaleType = ImageView.ScaleType.FIT_CENTER

            // Hide placeholder
            placeholderLayout.visibility = View.GONE

            // Clear previous result & inputs
            resetInputs()
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            cameraLauncher.launch(null)
        } else {
            Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Hide the action bar
        supportActionBar?.hide()

        imagePreview = findViewById(R.id.imagePreview)
        etHeight = findViewById(R.id.etHeight)
        etWeight = findViewById(R.id.etWeight)
        etAge = findViewById(R.id.etAge)
        tvResult = findViewById(R.id.tvResult)
        progressBar = findViewById(R.id.progressBar)
        resultsCard = findViewById(R.id.resultsCard)
        placeholderLayout = findViewById(R.id.placeholderLayout)

        // Request permissions
        requestPermissionsIfNeeded()

        // Load models in background thread
        loadModelsAsync()

        val btnCapture = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCapture)
        val btnSelect = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSelect)
        val btnPredict = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPredict)

        // Capture image
        btnCapture.setOnClickListener {
            if (checkSelfPermission(android.Manifest.permission.CAMERA)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                cameraLauncher.launch(null)
            } else {
                cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
            }
        }

        // Select image from gallery
        btnSelect.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }

        // Predict
        btnPredict.setOnClickListener {
            performPrediction()
        }
    }

    private fun loadModelsAsync() {
        progressBar.visibility = View.VISIBLE
        tvResult.text = "Loading AI models..."
        resultsCard.visibility = View.VISIBLE

        Thread {
            try {
                // Load fusion model
                fusionModule = TorchModule(assets)

                // Load MUAC model
                muacModule = MuacTorchModule(assets)

                modelsLoaded = true

                runOnUiThread {
                    progressBar.visibility = View.GONE
                    resultsCard.visibility = View.GONE
                    tvResult.text = ""
                    Toast.makeText(this, "✓ Models loaded successfully", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    tvResult.text = "❌ Failed to load AI models.\n\nError: ${e.message}\n\nPlease reinstall the app or contact support."
                    resultsCard.visibility = View.VISIBLE
                    Toast.makeText(this, "Model loading failed", Toast.LENGTH_LONG).show()
                }
                e.printStackTrace()
            }
        }.start()
    }

    private fun performPrediction() {
        if (!modelsLoaded) {
            Toast.makeText(this, "⏳ Please wait for models to load", Toast.LENGTH_SHORT).show()
            return
        }

        if (selectedBitmap == null) {
            Toast.makeText(this, "⚠️ Please select or capture an image first", Toast.LENGTH_SHORT).show()
            return
        }

        // Show progress
        progressBar.visibility = View.VISIBLE
        resultsCard.visibility = View.GONE
        tvResult.text = "Analyzing child's health data..."

        try {
            // Step 1: Detect MUAC from image (silently, don't display)
            val muacTensor = TensorUtils.bitmapToFloat32Tensor(selectedBitmap!!)
            val muacOutput = muacModule.module.forward(IValue.from(muacTensor)).toTensor()
            val muacValue = muacOutput.dataAsFloatArray[0]
            detectedMuac = muacValue

            // Step 2: Get anthropometric inputs (use defaults if empty)
            val heightText = etHeight.text.toString()
            val weightText = etWeight.text.toString()
            val ageText = etAge.text.toString()

            val height = heightText.toFloatOrNull() ?: 95f
            val weight = weightText.toFloatOrNull() ?: 14f
            val age = ageText.toFloatOrNull() ?: 24f

            // Step 3: Prepare inputs for fusion model
            val imgTensor = TensorUtils.bitmapToFloat32Tensor(selectedBitmap!!)
            val anthroTensor = Tensor.fromBlob(
                floatArrayOf(height, weight, muacValue, age),
                longArrayOf(1, 4)
            )

            // Step 4: Run fusion model
            val output = fusionModule.module.forward(
                IValue.from(imgTensor),
                IValue.from(anthroTensor)
            ).toTensor()
            val outputArray = output.dataAsFloatArray

            val maxIdx = outputArray.indices.maxByOrNull { outputArray[it] } ?: -1
            val label = if (maxIdx == 0) "Healthy" else "Malnourished"

            val rawConfidence = outputArray[maxIdx] * 100
            val confidence = rawConfidence.coerceIn(0f, 100f)

            // Risk Level
            val risk = when {
                label == "Healthy" && confidence >= 80 -> "Low Risk"
                label == "Healthy" && confidence >= 60 -> "Mild Risk"
                label == "Healthy" -> "Moderate Risk"
                label == "Malnourished" && confidence >= 80 -> "High Risk"
                label == "Malnourished" && confidence >= 60 -> "Moderate Risk"
                else -> "Mild Risk"
            }

            val riskEmoji = when {
                label == "Healthy" && risk == "Low Risk" -> "🟢"
                risk == "Moderate Risk" -> "🟡"
                risk == "High Risk" -> "🔴"
                else -> "🟢"
            }

            val (advice, diet) = when {
                // HEALTHY CHILD
                label == "Healthy" && risk == "Low Risk" -> {
                    "Let the child eat slowly and stop when full. Avoid chips and cool drinks. Give clean water or milk." to
                            "Give home food with rice, dal, milk, curd, fruits, and vegetables. Add egg or fish when possible. Make sure the child eats three meals and two snacks every day."

                }
                label == "Healthy" && risk == "Mild Risk" -> {
                    "Feed 5 small meals every day. Add a spoon of oil or ghee in food. Keep feeding with love and patience." to
                            "Give soft, energy-rich food like khichdi, rice with ghee, roti with dal or egg, and leafy vegetables. Add sattu or multigrain porridge for strength."
                }
                label == "Healthy" && risk == "Moderate Risk" -> {
                    "Take the child to the doctor or center soon. Feed small amounts many times a day. Give vitamin syrup or tablets as told. After recovery, give one extra meal daily." to
                            "Give soft and strong food like thick milk porridge, mashed banana, dal water, khichdi with oil, or egg. Use the special food given by health workers."
                }

                // MALNOURISHED CHILD
                label == "Malnourished" && risk == "Mild Risk" -> {
                    "Feed 5-6 times a day in small amounts. Let the child eat slowly. Keep the food soft and tasty. Give clean water or milk often." to
                            "Give home food made strong with ghee or oil for more energy. Feed rice with dal, egg, milk, curd, or paneer. Give snacks like banana, boiled egg, or thick curd in between."
                }
                label == "Malnourished" && risk == "Moderate Risk" -> {
                    "Feed many small meals frequently with care. Needs more energy food and doctor check-up once.  Follow the advice of the health worker." to
                            "Give soft, rich food with extra oil, dal, egg, or milk. Add sattu or multigrain porridge to meals. If given by health worker, add special food paste (RUSF) or fortified porridge daily."
                }
                label == "Malnourished" && risk == "High Risk" -> {
                    "Take the child to the hospital or center immediately. Feed small amounts often. Follow medical advice strictly and continue feeding even after recovery." to
                            "This child needs a doctor's care. Give only soft, energy-rich food like thick milk porridge, mashed banana, dal water, or egg. Health workers may give special food (RUTF) or milk."
                }

                else -> {
                    "Consult a healthcare professional for proper assessment." to
                            "Maintain a balanced diet with regular meals."
                }
            }

            tvResult.text = buildString {
                append("$riskEmoji Health Status: $label (${String.format("%.1f", confidence)}%)\n\n")
                append("$riskEmoji Risk Level: $risk\n\n")
                append("━━━━━━━━━━━━━━━━━━━\n\n")
                append("📋 Recommendations:\n\n")
                append("$advice\n\n")
                append("━━━━━━━━━━━━━━━━━━━\n\n")
                append("🍽️ Dietary Guidelines:\n\n")
                append("$diet\n\n")
                append("━━━━━━━━━━━━━━━━━━━\n\n")
                append("⚠️ Note: This is a screening tool. Please consult a healthcare professional for proper diagnosis and treatment.")
            }

            resultsCard.visibility = View.VISIBLE

            findViewById<ScrollView>(R.id.mainScrollView)?.post {
                findViewById<ScrollView>(R.id.mainScrollView)?.smoothScrollTo(0, resultsCard.top)
            }

        } catch (e: Exception) {
            tvResult.text = "❌ Error during analysis: ${e.message}\n\nPlease try again or contact support."
            resultsCard.visibility = View.VISIBLE
            Toast.makeText(this, "Analysis failed: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        } finally {
            progressBar.visibility = View.GONE
        }
    }

    private fun requestPermissionsIfNeeded() {
        val permissionsToRequest = mutableListOf<String>()

        if (checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(android.Manifest.permission.READ_MEDIA_IMAGES)
        }
        if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (checkSelfPermission(android.Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(android.Manifest.permission.CAMERA)
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissions(permissionsToRequest.toTypedArray(), 300)
        }
    }

    private fun resetInputs() {
        // Hide results and clear text
        resultsCard.visibility = View.GONE
        tvResult.text = ""
        detectedMuac = null

        // Also clear previous anthropometric inputs when new image is selected
        etHeight.text?.clear()
        etWeight.text?.clear()
        etAge.text?.clear()
    }
}
