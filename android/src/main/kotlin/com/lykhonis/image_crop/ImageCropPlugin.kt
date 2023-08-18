package com.lykhonis.image_crop

import android.Manifest.permission
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.FlutterPlugin.FlutterPluginBinding
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

class ImageCropPlugin : FlutterPlugin, ActivityAware, MethodCallHandler,
    RequestPermissionsResultListener {
    private var channel: MethodChannel? = null
    private var binding: ActivityPluginBinding? = null
    private var permissionRequestResult: MethodChannel.Result? = null
    private var executor: ExecutorService? = null
    private var activity: Activity? = null


    override fun onAttachedToEngine(binding: FlutterPluginBinding) {
        setup(binding.binaryMessenger)
    }

    override fun onDetachedFromEngine(binding: FlutterPluginBinding) {
        channel!!.setMethodCallHandler(null)
        channel = null
    }

    override fun onAttachedToActivity(activityPluginBinding: ActivityPluginBinding) {
        binding = activityPluginBinding
        activity = activityPluginBinding.activity
        activityPluginBinding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivity() {
        activity = null
        if (binding != null) {
            binding!!.removeRequestPermissionsResultListener(this)
        }
    }

    override fun onReattachedToActivityForConfigChanges(activityPluginBinding: ActivityPluginBinding) {
        onAttachedToActivity(activityPluginBinding)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity()
    }

    private fun setup(messenger: BinaryMessenger) {
        channel = MethodChannel(messenger, "plugins.lykhonis.com/image_crop")
        channel!!.setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "cropImage" -> {
                val path = call.argument<String>("path")!!
                val scale = call.argument<Double>("scale")!!
                val left = call.argument<Double>("left")!!
                val top = call.argument<Double>("top")!!
                val right = call.argument<Double>("right")!!
                val bottom = call.argument<Double>("bottom")!!
                val area = RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
                cropImage(path, area, scale.toFloat(), result)
            }
            "sampleImage" -> {
                val path = call.argument<String>("path")!!
                val maximumWidth = call.argument<Int>("maximumWidth")!!
                val maximumHeight = call.argument<Int>("maximumHeight")!!
                sampleImage(path, maximumWidth, maximumHeight, result)
            }
            "getImageOptions" -> {
                val path = call.argument<String>("path")!!
                getImageOptions(path, result)
            }
            "requestPermissions" -> {
                requestPermissions(result)
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    @Synchronized
    private fun io(runnable: Runnable) {
        if (executor == null) {
            executor = Executors.newCachedThreadPool()
        }
        executor!!.execute(runnable)
    }

    private fun ui(runnable: Runnable) {
        activity!!.runOnUiThread(runnable)
    }

    private fun cropImage(path: String, area: RectF, scale: Float, result: MethodChannel.Result) {
        io(Runnable {
            val srcFile = File(path)
            if (!srcFile.exists()) {
                ui { result.error("INVALID", "Image source cannot be opened", null) }
                return@Runnable
            }
            var srcBitmap = BitmapFactory.decodeFile(path, null)
            if (srcBitmap == null) {
                ui { result.error("INVALID", "Image source cannot be decoded", null) }
                return@Runnable
            }
            val options = decodeImageOptions(path)
            if (options.isFlippedDimensions) {
                val transformations = Matrix()
                transformations.postRotate(options.degrees.toFloat())
                val oldBitmap = srcBitmap
                srcBitmap = Bitmap.createBitmap(
                    oldBitmap,
                    0, 0,
                    oldBitmap.width, oldBitmap.height,
                    transformations, true
                )
                oldBitmap.recycle()
            }
            val width = (options.getWidth() * area.width() * scale).toInt()
            val height = (options.getHeight() * area.height() * scale).toInt()
            val dstBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(dstBitmap)
            val paint = Paint()
            paint.isAntiAlias = true
            paint.isFilterBitmap = true
            paint.isDither = true
            val srcRect = Rect(
                (srcBitmap!!.width * area.left).toInt(),
                (srcBitmap.height * area.top).toInt(),
                (srcBitmap.width * area.right).toInt(),
                (srcBitmap.height * area.bottom).toInt()
            )
            val dstRect = Rect(0, 0, width, height)
            canvas.drawBitmap(srcBitmap, srcRect, dstRect, paint)

            try {
                val dstFile = createTemporaryImageFile()
                compressBitmap(dstBitmap, dstFile)
                ui { result.success(dstFile.absolutePath) }
            } catch (e: IOException) {
                ui { result.error("INVALID", "Image could not be saved", e) }
            } finally {
                canvas.setBitmap(null)
                dstBitmap.recycle()
                srcBitmap.recycle()
            }
        })
    }

    private fun sampleImage(
        path: String,
        maximumWidth: Int,
        maximumHeight: Int,
        result: MethodChannel.Result
    ) {
        io(Runnable {
            val srcFile = File(path)
            if (!srcFile.exists()) {
                ui { result.error("INVALID", "Image source cannot be opened", null) }
                return@Runnable
            }
            val options = decodeImageOptions(path)
            val bitmapOptions = BitmapFactory.Options()
            bitmapOptions.inSampleSize = calculateInSampleSize(
                options.getWidth(), options.getHeight(),
                maximumWidth, maximumHeight
            )
            var bitmap = BitmapFactory.decodeFile(path, bitmapOptions)
            if (bitmap == null) {
                ui { result.error("INVALID", "Image source cannot be decoded", null) }
                return@Runnable
            }
            if (options.getWidth() > maximumWidth && options.getHeight() > maximumHeight) {
                val ratio = max(
                    maximumWidth / options.getWidth().toFloat(),
                    maximumHeight / options.getHeight().toFloat()
                )
                val sample = bitmap
                bitmap = Bitmap.createScaledBitmap(
                    sample,
                    (bitmap.width * ratio).roundToInt(),
                    (bitmap.height * ratio).roundToInt(),
                    true
                )
                sample.recycle()
            }
            try {
                val dstFile = createTemporaryImageFile()
                compressBitmap(bitmap, dstFile)
                copyExif(srcFile, dstFile)
                ui { result.success(dstFile.absolutePath) }
            } catch (e: IOException) {
                ui { result.error("INVALID", "Image could not be saved", e) }
            } finally {
                bitmap!!.recycle()
            }
        })
    }

    @Throws(IOException::class)
    private fun compressBitmap(bitmap: Bitmap?, file: File) {
        val outputStream: OutputStream = FileOutputStream(file)
        try {
            val compressed = bitmap!!.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
            if (!compressed) {
                throw IOException("Failed to compress bitmap into JPEG")
            }
        } finally {
            try {
                outputStream.close()
            } catch (ignore: IOException) {
            }
        }
    }

    private fun calculateInSampleSize(
        width: Int,
        height: Int,
        maximumWidth: Int,
        maximumHeight: Int
    ): Int {
        var inSampleSize = 1
        if (height > maximumHeight || width > maximumWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= maximumHeight && halfWidth / inSampleSize >= maximumWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun getImageOptions(path: String, result: MethodChannel.Result) {
        io(Runnable {
            val file = File(path)
            if (!file.exists()) {
                result.error("INVALID", "Image source cannot be opened", null)
                return@Runnable
            }
            val options = decodeImageOptions(path)
            val properties: MutableMap<String, Any> = HashMap()
            properties["width"] = options.getWidth()
            properties["height"] = options.getHeight()
            ui { result.success(properties) }
        })
    }

    private fun requestPermissions(result: MethodChannel.Result) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (activity!!.checkSelfPermission(permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                activity!!.checkSelfPermission(permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            ) {
                result.success(true)
            } else {
                permissionRequestResult = result
                activity!!.requestPermissions(
                    arrayOf(
                        permission.READ_EXTERNAL_STORAGE,
                        permission.WRITE_EXTERNAL_STORAGE
                    ), PERMISSION_REQUEST_CODE
                )
            }
        } else {
            result.success(true)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ): Boolean {
        if (requestCode == PERMISSION_REQUEST_CODE && permissionRequestResult != null) {
            val readExternalStorage = getPermissionGrantResult(
                permission.READ_EXTERNAL_STORAGE,
                permissions,
                grantResults
            )
            val writeExternalStorage = getPermissionGrantResult(
                permission.WRITE_EXTERNAL_STORAGE,
                permissions,
                grantResults
            )
            permissionRequestResult!!.success(
                readExternalStorage == PackageManager.PERMISSION_GRANTED &&
                        writeExternalStorage == PackageManager.PERMISSION_GRANTED
            )
            permissionRequestResult = null
        }
        return false
    }

    private fun getPermissionGrantResult(
        permission: String,
        permissions: Array<String>,
        grantResults: IntArray
    ): Int {
        for (i in permission.indices) {
            if (permission == permissions[i]) {
                return grantResults[i]
            }
        }
        return PackageManager.PERMISSION_DENIED
    }

    @Throws(IOException::class)
    private fun createTemporaryImageFile(): File {
        val directory = activity!!.cacheDir
        val name = "image_crop_" + UUID.randomUUID().toString()
        return File.createTempFile(name, ".jpg", directory)
    }

    private fun decodeImageOptions(path: String?): ImageOptions {
        var rotationDegrees = 0
        try {
            val exif = ExifInterface(
                path!!
            )
            rotationDegrees = exif.rotationDegrees
        } catch (e: IOException) {
            Log.e("ImageCrop", "Failed to read a file $path", e)
        }
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        BitmapFactory.decodeFile(path, options)
        return ImageOptions(options.outWidth, options.outHeight, rotationDegrees)
    }

    private fun copyExif(source: File, destination: File) {
        try {
            val sourceExif = ExifInterface(source.absolutePath)
            val destinationExif = ExifInterface(destination.absolutePath)
            val tags = listOf(
                ExifInterface.TAG_F_NUMBER,
                ExifInterface.TAG_EXPOSURE_TIME,
                ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
                ExifInterface.TAG_GPS_ALTITUDE,
                ExifInterface.TAG_GPS_ALTITUDE_REF,
                ExifInterface.TAG_FOCAL_LENGTH,
                ExifInterface.TAG_GPS_DATESTAMP,
                ExifInterface.TAG_WHITE_BALANCE,
                ExifInterface.TAG_GPS_PROCESSING_METHOD,
                ExifInterface.TAG_GPS_TIMESTAMP,
                ExifInterface.TAG_DATETIME,
                ExifInterface.TAG_FLASH,
                ExifInterface.TAG_GPS_LATITUDE,
                ExifInterface.TAG_GPS_LATITUDE_REF,
                ExifInterface.TAG_GPS_LONGITUDE,
                ExifInterface.TAG_GPS_LONGITUDE_REF,
                ExifInterface.TAG_MAKE,
                ExifInterface.TAG_MODEL,
                ExifInterface.TAG_ORIENTATION
            )
            for (tag in tags) {
                val attribute = sourceExif.getAttribute(tag)
                if (attribute != null) {
                    destinationExif.setAttribute(tag, attribute)
                }
            }
            destinationExif.saveAttributes()
        } catch (e: IOException) {
            Log.e("ImageCrop", "Failed to preserve Exif information", e)
        }
    }

    private class ImageOptions(
        private val width: Int,
        private val height: Int,
        val degrees: Int
    ) {

        fun getHeight(): Int {
            return if (isFlippedDimensions && degrees != 180) width else height
        }

        fun getWidth(): Int {
            return if (isFlippedDimensions && degrees != 180) height else width
        }

        val isFlippedDimensions: Boolean
            get() = degrees == 90 || degrees == 270 || degrees == 180
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 13094
    }
}