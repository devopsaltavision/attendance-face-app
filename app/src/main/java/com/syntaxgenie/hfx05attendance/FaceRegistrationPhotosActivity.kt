package com.syntaxgenie.hfx05attendance

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.syntaxgenie.hfx05attendance.face.repository.FaceRegistrationPhotoStore
import com.syntaxgenie.hfx05attendance.ui.KioskWindowInsets

class FaceRegistrationPhotosActivity : AppCompatActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); setContentView(R.layout.activity_face_registration_photos)
        KioskWindowInsets.apply(this, findViewById(R.id.facePhotosRoot))
        findViewById<MaterialToolbar>(R.id.facePhotosToolbar).setNavigationOnClickListener { finish() }
        val employeeId = intent.getStringExtra(EXTRA_EMPLOYEE_ID).orEmpty()
        findViewById<TextView>(R.id.facePhotosEmployee).text = "Employee: $employeeId"
        val rows = findViewById<LinearLayout>(R.id.facePhotosContent)
        val photos = FaceRegistrationPhotoStore(applicationContext).load(employeeId)
        if (photos == null) rows.addView(TextView(this).apply { text = "Registration photos unavailable" })
        else listOf("STRAIGHT", "LEFT", "RIGHT").zip(photos).forEach { (label, bitmap) ->
            rows.addView(TextView(this).apply { text = label; textSize = 16f; setPadding(0, 24, 0, 8) })
            rows.addView(ImageView(this).apply { setImageBitmap(bitmap); adjustViewBounds = true; scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 360) })
        }
    }
    companion object { private const val EXTRA_EMPLOYEE_ID = "employeeId"; fun createIntent(context: Context, employeeId: String) = Intent(context, FaceRegistrationPhotosActivity::class.java).putExtra(EXTRA_EMPLOYEE_ID, employeeId) }
}
