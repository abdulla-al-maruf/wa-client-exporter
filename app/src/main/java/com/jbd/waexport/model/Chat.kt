package com.jbd.waexport.model

data class Chat(
    val jid: String,
    val displayName: String?,
    val isSelected: Boolean = false,
    val timestamp: Long = 0L
)
