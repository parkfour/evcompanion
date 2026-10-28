package com.evcompanion.core.polling

import com.evcompanion.core.preset.PidCategory

data class DecodedValue(
    val pidName: String,
    val displayName: String,
    val category: PidCategory,
    val value: Double,
    val unit: String,
    val timestampMillis: Long,
)
