package com.keiranhaas.auralpdf

import android.app.Application
import com.keiranhaas.auralpdf.data.OutputFiles
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class AuralPdfApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        OutputFiles.clearAll(this)
    }
}