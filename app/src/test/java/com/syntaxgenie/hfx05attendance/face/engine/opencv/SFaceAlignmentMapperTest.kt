package com.syntaxgenie.hfx05attendance.face.engine.opencv

import com.syntaxgenie.hfx05attendance.face.detection.*
import org.junit.Assert.*
import org.junit.Test

class SFaceAlignmentMapperTest {
    @Test fun openCvRowUsesDocumentedLandmarkOrder() {
        val face = DetectedFace(FaceRect(1f, 2f, 101f, 202f), listOf(
            FaceLandmark(FaceLandmarkType.RIGHT_EYE, FacePoint(10f, 20f)), FaceLandmark(FaceLandmarkType.LEFT_EYE, FacePoint(30f, 40f)),
            FaceLandmark(FaceLandmarkType.NOSE_BASE, FacePoint(50f, 60f)), FaceLandmark(FaceLandmarkType.MOUTH_RIGHT, FacePoint(70f, 80f)), FaceLandmark(FaceLandmarkType.MOUTH_LEFT, FacePoint(90f, 100f))),
            FacePose(null, null, null), FaceQuality(.75f), null, 0, 1)
        assertArrayEquals(floatArrayOf(1f, 2f, 100f, 200f, 10f, 20f, 30f, 40f, 50f, 60f, 70f, 80f, 90f, 100f, .75f), SFaceAlignmentMapper.toOpenCvFaceRowValues(face, 200, 300, 0), 0f)
    }
    @Test fun rotationsMapCoordinates() {
        val p = FacePoint(10f, 20f)
        assertEquals(FacePoint(60f, 10f), SFaceAlignmentMapper.orientedPoint(p, 100, 80, 90))
        assertEquals(FacePoint(20f, 90f), SFaceAlignmentMapper.orientedPoint(p, 100, 80, 270))
    }
    @Test fun detectorImageRowReversesThePresentationTransform() {
        val face = DetectedFace(FaceRect(600f, 100f, 800f, 300f), listOf(
            FaceLandmark(FaceLandmarkType.RIGHT_EYE, FacePoint(620f, 150f)), FaceLandmark(FaceLandmarkType.LEFT_EYE, FacePoint(700f, 150f)),
            FaceLandmark(FaceLandmarkType.NOSE_BASE, FacePoint(660f, 200f)), FaceLandmark(FaceLandmarkType.MOUTH_RIGHT, FacePoint(630f, 250f)), FaceLandmark(FaceLandmarkType.MOUTH_LEFT, FacePoint(690f, 250f))),
            FacePose(null, null, null), FaceQuality(.75f), null, 0, 0)
        assertArrayEquals(floatArrayOf(100f, 480f, 200f, 200f, 150f, 660f, 150f, 580f, 200f, 620f, 250f, 650f, 250f, 590f, .75f),
            SFaceAlignmentMapper.toDetectorImageFaceRowValues(face, 1280, 720, 90), 0f)
    }
    @Test fun invalidGeometryRejected() {
        val face = DetectedFace(FaceRect(1f, 1f, 1f, 5f), emptyList(), FacePose(null, null, null), null, null, 0, 1)
        assertNotNull(SFaceAlignmentMapper.validate(face, 100, 100))
    }
}
