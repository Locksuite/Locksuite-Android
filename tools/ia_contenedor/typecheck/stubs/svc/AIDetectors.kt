package com.ejemplo.locksuite.service
class AIFaceDetector {
    fun detectFaceRegions(b: android.graphics.Bitmap): List<DetectedRegion> = emptyList()
    companion object {
        fun getOrCreate(c: android.content.Context): AIFaceDetector = AIFaceDetector()
        fun release() {}
    }
}
class AIPersonDetector {
    fun detectPersonRegions(b: android.graphics.Bitmap): List<DetectedRegion> = emptyList()
    companion object {
        fun getOrCreate(c: android.content.Context): AIPersonDetector = AIPersonDetector()
        fun release() {}
    }
}
