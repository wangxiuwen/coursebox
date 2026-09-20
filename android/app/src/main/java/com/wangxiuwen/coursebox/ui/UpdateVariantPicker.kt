package com.wangxiuwen.coursebox.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangxiuwen.coursebox.core.UpdateVariant
import com.wangxiuwen.coursebox.ui.theme.AccentBlue

/**
 * Lets the update prompt ask which image to install instead of deciding on
 * the device's behalf. Draws nothing when the release offers a single
 * choice, so the ordinary one-apk case keeps the plain prompt.
 *
 * Picking the flavour the device is not already running is a legitimate move
 * — a phone that ended up on the kiosk build gets itself back this way — so
 * the other option is offered plainly, with one line saying what changes.
 */
@Composable
fun UpdateVariantPicker(
    variants: List<UpdateVariant>,
    selected: UpdateVariant?,
    onSelect: (UpdateVariant) -> Unit,
) {
    if (variants.size < 2) return
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(
            "选择要安装的版本",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF6B7280),
            modifier = Modifier.padding(bottom = 4.dp),
        )
        for (variant in variants) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(variant) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = variant.asset.name == selected?.asset?.name,
                    onClick = { onSelect(variant) },
                    colors = RadioButtonDefaults.colors(selectedColor = AccentBlue),
                )
                Column(modifier = Modifier.padding(start = 2.dp)) {
                    Text(
                        variant.label + if (variant.isOurs) "（当前）" else "",
                        fontSize = 15.sp,
                        color = Color.Black,
                    )
                    Text(variant.note, fontSize = 12.sp, color = Color(0xFF6B7280))
                }
            }
        }
    }
}
