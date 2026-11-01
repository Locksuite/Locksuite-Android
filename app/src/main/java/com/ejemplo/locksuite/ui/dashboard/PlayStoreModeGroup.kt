package com.ejemplo.locksuite.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ejemplo.locksuite.mdm.PlayStoreCatalog
import com.ejemplo.locksuite.mdm.PlayStoreManager
import com.ejemplo.locksuite.ui.playstore.PlayStoreText
import com.ejemplo.locksuite.util.LocaleManager

/**
 * Grupo "Tienda Play" de la pestaña Políticas del propio celular (29/10/2026, B.101).
 *
 * Es el equivalente local del selector que tiene la ficha del equipo en el panel web: cuatro
 * opciones — Cerrada, Kosher, Trabajo, Lite — y la elegida queda marcada. Cambiarla llama a
 * [PlayStoreManager.setMode], que es el MISMO camino que usan el comando del panel, los
 * perfiles automáticos y el fin del período de gracia: guarda el modo, pausa o reanuda lo que
 * corresponda, actualiza la lista blanca y apaga/enciende el ícono de la tienda.
 *
 * Qué apps trae cada nivel NO se elige acá: lo define el administrador en el panel web
 * (Tienda Play). Esta pantalla solo dice cuántas abre cada modo.
 *
 * @param refreshKey cambia cada vez que la pestaña tiene que releer el estado.
 * @param onChanged  se llama después de cambiar el modo, para que la pestaña se vuelva a leer.
 */
@Composable
fun PlayStoreModeGroup(refreshKey: Int, onChanged: () -> Unit) {
    val context = LocalContext.current
    val lang = LocaleManager.getLang()
    val current = remember(refreshKey) { PlayStoreManager.mode(context) }

    val options = listOf(
        Triple(PlayStoreCatalog.MODE_CLOSED, PlayStoreText.Key.MODE_CLOSED, PlayStoreText.Key.MODE_CLOSED_DETAIL),
        Triple(PlayStoreCatalog.MODE_KOSHER, PlayStoreText.Key.MODE_KOSHER, PlayStoreText.Key.MODE_KOSHER_DETAIL),
        Triple(PlayStoreCatalog.MODE_WORK, PlayStoreText.Key.MODE_WORK, PlayStoreText.Key.MODE_WORK_DETAIL),
        Triple(PlayStoreCatalog.MODE_LITE, PlayStoreText.Key.MODE_LITE, PlayStoreText.Key.MODE_LITE_DETAIL)
    )

    PolicyGroupCard(title = PlayStoreText.t(PlayStoreText.Key.TITLE, lang)) {
        Text(
            text = PlayStoreText.t(PlayStoreText.Key.POLICY_HINT, lang),
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEach { option ->
                val mode = option.first
                val selected = mode == current
                val choose: () -> Unit = {
                    if (!selected) {
                        PlayStoreManager.setMode(context, mode)
                        onChanged()
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (selected) Color(0xFFF5C438).copy(alpha = 0.14f) else Color.Transparent
                        )
                        .clickable { choose() }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selected,
                        onClick = { choose() },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = Color(0xFFF5C438),
                            unselectedColor = Color.White.copy(alpha = 0.6f)
                        )
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = PlayStoreText.t(option.second, lang),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = PlayStoreText.t(option.third, lang),
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}
