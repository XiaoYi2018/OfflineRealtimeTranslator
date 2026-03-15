package com.bohanli.ruzhtranslator.history

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "translation_records")
data class TranslationRecord(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val ruText: String,
    val zhText: String,
    var customName: String? = null,
    val createTime: Long = System.currentTimeMillis(),
    var isFavorite: Boolean = false,
    var isHistory: Boolean = true
)
