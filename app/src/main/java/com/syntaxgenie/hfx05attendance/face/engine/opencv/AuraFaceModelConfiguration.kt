package com.syntaxgenie.hfx05attendance.face.engine.opencv

object AuraFaceModelConfiguration {
    const val ENGINE_ID = "opencv-dnn"
    const val MODEL_ID = "auraface-v1"
    const val MODEL_VERSION = "glintr100-a7933ea5"
    const val TEMPLATE_FORMAT_VERSION = "auraface-f32le-v1"
    const val INPUT_SIZE = 112
    const val EMBEDDING_SIZE = 512
    const val ASSET_PATH = OpenCvEngineConfiguration.AURAFACE_ASSET_PATH
    const val FILENAME = OpenCvEngineConfiguration.AURAFACE_FILENAME
    const val SHA256 = OpenCvEngineConfiguration.AURAFACE_SHA256
}
