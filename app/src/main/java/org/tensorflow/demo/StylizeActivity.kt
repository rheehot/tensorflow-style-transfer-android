package org.tensorflow.demo

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.ml.vision.common.FirebaseVisionImage
import com.otaliastudios.cameraview.CameraListener
import com.otaliastudios.cameraview.CameraView
import com.otaliastudios.cameraview.PictureResult
import com.otaliastudios.cameraview.controls.Facing
import com.otaliastudios.cameraview.internal.utils.WorkerHandler
import kotlin.math.max
import kotlin.math.min

/**
 * Sample activity that stylizes the camera preview according to "A Learned Representation For
 * Artistic Styles" (https://arxiv.org/abs/1610.07629)
 */
class StylizeActivity : AppCompatActivity() {

    private lateinit var stylize: Stylize

    private lateinit var cameraView: CameraView

    private lateinit var preview: ImageView

    private lateinit var adapter: ImageGridAdapter

    // Start at a medium size, but let the user step up through smaller sizes so they don't get
    // immediately stuck processing a large image.
    private val SIZES = intArrayOf(128, 192, 256, 384, 512, 720)
    private var desiredSize = SIZES[5]
    private var desiredSizeIndex = -1

    private var lastOtherStyle = 1

    private var allZero = false

    private var lastBitmap: Bitmap? = null
    private var lastStylizedBitmap: Bitmap? = null

    private val gridTouchAdapter = object : OnTouchListener {

        private var slider: ImageSlider? = null

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    (0 until Styles.count).forEach { i ->
                        val child = adapter.getItem(i)
                        val rect = Rect()
                        child.getHitRect(rect)
                        if (rect.contains(event.x.toInt(), event.y.toInt())) {
                            slider = child
                            child.setHighlight(true)
                        }
                    }

                MotionEvent.ACTION_MOVE -> {
                    val slider = this.slider
                    if (slider != null) {
                        val rect = Rect()
                        slider.getHitRect(rect)

                        val newSliderVal = min(
                            1.0,
                            max(0.0, 1.0 - (event.y - slider.top) / slider.height)
                        ).toFloat()

                        setStyle(slider, newSliderVal)
                    }
                }

                MotionEvent.ACTION_UP -> {
                    val slider = this.slider
                    if (slider != null) {
                        slider.setHighlight(false)
                        this.slider = null
                        lastBitmap?.run(::updatePreview)
                    }
                }
            }
            return true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stylize)

        stylize = Stylize(assets)

        cameraView = findViewById(R.id.cameraView)
        preview = findViewById(R.id.preview)

        val cameraButton: View = findViewById(R.id.cameraButton)
        cameraButton.setOnClickListener {
            cameraView.takePicture()
        }

        val galleryButton: View = findViewById(R.id.galleryButton)
        galleryButton.setOnClickListener {
            Gallery.takePicture(this)
        }

        val cameraSwitchButton: ImageView = findViewById(R.id.cameraSwitchButton)
        cameraSwitchButton.setOnClickListener {
            when (cameraView.facing) {
                Facing.BACK -> {
                    cameraSwitchButton.setImageResource(R.drawable.ic_camera_back)
                    cameraView.facing = Facing.FRONT
                }
                Facing.FRONT -> {
                    cameraSwitchButton.setImageResource(R.drawable.ic_camera_front)
                    cameraView.facing = Facing.BACK
                }
            }
        }

        val sizeButton: TextView = findViewById(R.id.sizeButton)
        sizeButton.text = "$desiredSize"
        sizeButton.setOnClickListener {
            desiredSizeIndex = (desiredSizeIndex + 1) % SIZES.size
            desiredSize = SIZES[desiredSizeIndex]
            sizeButton.text = "$desiredSize"
        }

        val saveButton: View = findViewById(R.id.saveButton)
        saveButton.setOnClickListener {
            lastStylizedBitmap?.let {
                Gallery.saveBitmap(this, it)
            }
        }

        adapter = ImageGridAdapter(this, Styles.thumbnails)
        val grid: GridView = findViewById(R.id.grid_layout)
        grid.adapter = adapter
        grid.setOnTouchListener(gridTouchAdapter)

        setStyle(adapter.getItem(0), 1f)

        if (hasPermission()) {
            bindCamera()
        } else {
            requestPermission()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == PERMISSIONS_REQUEST) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                bindCamera()
            } else {
                requestPermission()
            }
        }
    }

    private fun hasPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PERMISSIONS.all {
                checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            }
        } else {
            true
        }
    }

    private fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(PERMISSIONS, PERMISSIONS_REQUEST)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Gallery.onPictureTaken(requestCode, resultCode, data) {
            val bitmap = FirebaseVisionImage
                .fromFilePath(this, it)
                .bitmap

            setPreview(bitmap)
        }
    }

    private fun bindCamera() {
        cameraView.setLifecycleOwner(this)
        cameraView.addCameraListener(object : CameraListener() {

            override fun onPictureTaken(result: PictureResult) {
                super.onPictureTaken(result)
                result.toBitmap {
                    it?.run(::setPreview)
                }
            }
        })
    }

    private fun setPreview(bitmap: Bitmap) {
        lastBitmap = bitmap
        updatePreview(bitmap)
    }

    private fun updatePreview(bitmap: Bitmap) {
        WorkerHandler.execute {
            val stylizedImage = getStylizedImageFrom(bitmap)
            lastStylizedBitmap = stylizedImage
            runOnUiThread {
                preview.setImageBitmap(stylizedImage)
            }
        }
    }

    override fun onDestroy() {
        cameraView.clearFrameProcessors()
        super.onDestroy()
    }

    private fun getStylizedImageFrom(bitmap: Bitmap): Bitmap {
        val previewWidth = bitmap.width
        val previewHeight = bitmap.height

        val frameToCropTransform = ImageUtils.getTransformationMatrix(
            previewWidth, previewHeight,
            desiredSize, desiredSize,
            0, true
        )
        val croppedBitmap = Bitmap.createBitmap(desiredSize, desiredSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(croppedBitmap)
        canvas.drawBitmap(bitmap, frameToCropTransform, null)

        return stylize.stylize(croppedBitmap, desiredSize)
    }

    private fun setStyle(slider: ImageSlider, value: Float) {
        slider.value = value

        val styleCount = Styles.count

        if (NORMALIZE_SLIDERS) {
            // Slider vals correspond directly to the input tensor vals, and normalization is visually
            // maintained by remanipulating non-selected sliders.
            var otherSum = 0f

            for (i in 0 until styleCount) {
                if (adapter.getItem(i) != slider) {
                    otherSum += adapter.getItem(i).value
                }
            }

            if (otherSum > 0) {
                var highestOtherVal = 0f
                val factor = if (otherSum > 0f) (1f - value) / otherSum else 0f
                for (i in 0 until styleCount) {
                    val child = adapter.getItem(i)
                    if (child == slider) {
                        continue
                    }
                    val newVal = child.value * factor
                    child.value = if (newVal > 0.01f) newVal else 0f

                    if (child.value > highestOtherVal) {
                        lastOtherStyle = i
                        highestOtherVal = child.value
                    }
                }
            } else {
                // Everything else is 0, so just pick a suitable slider to push up when the
                // selected one goes down.
                if (adapter.getItem(lastOtherStyle) == slider) {
                    lastOtherStyle = (lastOtherStyle + 1) % styleCount
                }
                adapter.getItem(lastOtherStyle).value = 1f - value
            }
        }

        val lastAllZero = allZero
        var sum = 0f
        for (i in 0 until styleCount) {
            sum += adapter.getItem(i).value
        }
        allZero = sum == 0f

        for (item in adapter.items) {
            item.setAllZero(allZero)
        }

        // Now update the values used for the input tensor. If nothing is set, mix in everything
        // equally. Otherwise everything is normalized to sum to 1.0.
        val styleValues = FloatArray(styleCount)
        for (i in 0 until styleCount) {
            styleValues[i] = if (allZero) 1f / styleCount else adapter.getItem(i).value / sum

            if (lastAllZero != allZero) {
                adapter.getItem(i).postInvalidate()
            }
        }
        stylize.setStyleValues(styleValues)
    }

    companion object {

        private const val PERMISSIONS_REQUEST = 1

        private val PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )

        // Whether to actively manipulate non-selected sliders so that sum of activations always appears
        // to be 1.0. The actual style input tensor will be normalized to sum to 1.0 regardless.
        private const val NORMALIZE_SLIDERS = true
    }
}
