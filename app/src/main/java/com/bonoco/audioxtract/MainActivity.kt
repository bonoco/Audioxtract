package com.bonoco.audioxtract

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AudioXtractTheme { Screen() } }
    }
}

@Composable
fun AudioXtractTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()

    // Cores fixas (sem Material You), para o app ficar igual em qualquer celular
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFFB0C6FF),
        onPrimary = Color(0xFF002D6E),
        primaryContainer = Color(0xFF2A4A7F),
        onPrimaryContainer = Color(0xFFD9E2FF),
        secondaryContainer = Color(0xFF333438),
        onSecondaryContainer = Color(0xFFE3E2E6),
        surface = Color(0xFF121318),
        surfaceContainerHigh = Color(0xFF333438),
        onSurface = Color(0xFFE3E2E6),
        onSurfaceVariant = Color(0xFFC4C6D0)
    ) else lightColorScheme()

    MaterialTheme(
        colorScheme = colors,
        shapes = Shapes(
            small = RoundedCornerShape(16.dp),
            medium = RoundedCornerShape(24.dp),
            large = RoundedCornerShape(32.dp)
        ),
        content = content
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Screen(vm: AudioViewModel = viewModel()) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) vm.pick(u)
    }
    val bigShape = RoundedCornerShape(28.dp)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "AudioXtract",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "Tire o áudio de qualquer vídeo",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 1. Escolher arquivo (Container Primário do Tema)
            Card(
                shape = bigShape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !vm.running) {
                        try {
                            picker.launch("*/*")
                        } catch (_: Exception) {
                            vm.message = "Não foi possível abrir o seletor de arquivos."
                            vm.isError = true
                        }
                    }
            ) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (vm.uri == null) "Escolher vídeo" else "Vídeo selecionado",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        vm.fileName ?: "Toque aqui para procurar no celular",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }

            // 2. Formato (Container Secundário do Tema)
            Card(
                shape = bigShape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Formato",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AudioFormat.entries.forEach { f ->
                            FilterChip(
                                selected = vm.format == f,
                                onClick = { vm.format = f },
                                enabled = !vm.running,
                                shape = RoundedCornerShape(50),
                                label = { Text(f.label) }
                            )
                        }
                    }
                }
            }

            // 3. Qualidade (Container de Superfície do Tema)
            Card(
                shape = bigShape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Qualidade",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (vm.format.lossy) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(128, 192, 256, 320).forEach { b ->
                                FilterChip(
                                    selected = vm.bitrate == b,
                                    onClick = { vm.bitrate = b },
                                    enabled = !vm.running,
                                    shape = RoundedCornerShape(50),
                                    label = { Text("$b kbps") }
                                )
                            }
                        }
                    } else {
                        Text(
                            "Este formato não perde qualidade (arquivo maior).",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Button(
                onClick = { vm.convert() },
                enabled = vm.uri != null && !vm.running,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            ) {
                Text(
                    if (vm.running) "Extraindo..." else "Extrair áudio",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            if (vm.running) {
                LinearProgressIndicator(
                    progress = { vm.progress },
                    strokeCap = StrokeCap.Round,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                )
                Text(
                    "${(vm.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            vm.message?.let { msg ->
                Card(
                    shape = bigShape,
                    colors = CardDefaults.cardColors(
                        containerColor = if (vm.isError) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.tertiaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        msg,
                        modifier = Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (vm.isError) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }
    }
}
