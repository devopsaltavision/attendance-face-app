package com.syntaxgenie.hfx05attendance.face.engine.opencv

/** Official OpenCV Zoo YuNet model; see the bundled MIT notice beside the asset. */
object OpenCvEngineConfiguration {
    const val YUNET_ASSET_PATH = "face_models/face_detection_yunet_2023mar.onnx"
    const val YUNET_FILENAME = "face_detection_yunet_2023mar.onnx"
    const val YUNET_SHA256 = "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4"
    const val DETECTOR_WIDTH = 320
    const val DETECTOR_HEIGHT = 320
    const val SCORE_THRESHOLD = 0.80f
    const val NMS_THRESHOLD = 0.30f
    const val TOP_K = 5000
    const val SFACE_ASSET_PATH = "face_models/face_recognition_sface_2021dec.onnx"
    const val SFACE_FILENAME = "face_recognition_sface_2021dec.onnx"
    const val SFACE_SHA256 = "0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79"
    const val SFACE_ENGINE_ID = "opencv-sface"
    const val SFACE_MODEL_VERSION = "2021dec"
    const val TEMPLATE_FORMAT_VERSION = "1"
}
