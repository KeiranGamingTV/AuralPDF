package com.keiranhaas.auralpdf.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

private data class Tool(val route: String, val title: String, val subtitle: String, val icon: ImageVector)

private val tools = listOf(
    Tool("merge", "Merge", "Combine PDFs into one", Icons.Default.CallMerge),
    Tool("split", "Split", "Extract pages or cut into parts", Icons.Default.CallSplit),
    Tool("compress", "Compress", "Shrink file size", Icons.Default.Compress),
    Tool("sign", "Sign & annotate", "Add a signature or text", Icons.Default.Draw),
    Tool("forms", "Fill forms", "Complete fillable PDFs", Icons.Default.EditNote),
    Tool("scan", "Scan", "Camera to searchable PDF", Icons.Default.DocumentScanner),
    Tool("text", "Extract text", "Read text from any PDF", Icons.Default.TextSnippet),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpen: (String) -> Unit) {
    Scaffold(topBar = { LargeTopAppBar(title = { Text("AuralPDF") }) }) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.padding(pad),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(tools) { t ->
                Card(onClick = { onOpen(t.route) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp).heightIn(min = 120.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(t.icon, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.weight(1f))
                        Text(t.title, style = MaterialTheme.typography.titleMedium)
                        Text(t.subtitle, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item(span = { GridItemSpan(2) }) {
                Text(
                    "Everything happens on this device. AuralPDF has no internet permission.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}