package com.syntaxgenie.hfx05attendance.face.engine.opencv

import com.syntaxgenie.hfx05attendance.face.frame.FaceCameraFrame
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

internal object OpenCvFrameConverter {
    fun nv21ToBgr(frame: FaceCameraFrame): Mat {
        val nv21 = Mat(frame.height + frame.height / 2, frame.width, CvType.CV_8UC1)
        nv21.put(0, 0, frame.copyData())
        return Mat().also { bgr ->
            Imgproc.cvtColor(nv21, bgr, Imgproc.COLOR_YUV2BGR_NV21)
            nv21.release()
        }
    }

    fun nv21YReplicatedBgr(frame: FaceCameraFrame, clahe: Boolean = false): Mat {
        val y = Mat(frame.height, frame.width, CvType.CV_8UC1)
        y.put(0, 0, frame.copyData(), 0, frame.width * frame.height)
        if (clahe) {
            val normalized = Mat()
            Imgproc.createCLAHE(2.0, org.opencv.core.Size(8.0, 8.0)).apply(y, normalized)
            y.release()
            return Mat().also { bgr -> Imgproc.cvtColor(normalized, bgr, Imgproc.COLOR_GRAY2BGR); normalized.release() }
        }
        return Mat().also { bgr -> Imgproc.cvtColor(y, bgr, Imgproc.COLOR_GRAY2BGR); y.release() }
    }
}
