package com.m3u.ui.components

import androidx.compose.material.AlertDialog
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.m3u.ui.model.LocalTheme

typealias OnDismissRequest = () -> Unit
typealias OnConfirm = () -> Unit
typealias OnDismiss = () -> Unit

@Composable
fun AlertDialog(
    title: String,
    text: String,
    confirm: String?,
    dismiss: String?,
    onDismissRequest: OnDismissRequest,
    onConfirm: OnConfirm,
    onDismiss: OnDismiss,
    modifier: Modifier = Modifier,
    backgroundColor: Color = LocalTheme.current.background,
    contentColor: Color = LocalTheme.current.onBackground,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.h6,
                maxLines = 1
            )
        },
        text = {
            Text(
                text = text,
                style = MaterialTheme.typography.body1
            )
        },
        confirmButton = {
            confirm?.let {
                TextButton(text = it, onClick = onConfirm)
            }
        },
        dismissButton = {
            dismiss?.let {
                TextButton(text = it, onClick = onDismiss)
            }
        },
        backgroundColor = backgroundColor,
        contentColor = contentColor,
        modifier = modifier
    )
}