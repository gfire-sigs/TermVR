package dev.gfire.termvr.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Custom 24dp vector icon set for TermVR navigation, container actions, and storage items.
 */
object TermVrIcons {
    private val StrokePaint = SolidColor(Color.Black)

    val Containers: ImageVector by lazy {
        strokeIcon("Containers") {
            // Isometric container cube
            moveTo(12f, 3f)
            lineTo(20f, 7.5f)
            lineTo(20f, 16.5f)
            lineTo(12f, 21f)
            lineTo(4f, 16.5f)
            lineTo(4f, 7.5f)
            close()
            moveTo(4f, 7.5f)
            lineTo(12f, 12f)
            lineTo(20f, 7.5f)
            moveTo(12f, 12f)
            lineTo(12f, 21f)
        }
    }

    val Terminal: ImageVector by lazy {
        strokeIcon("Terminal") {
            moveTo(3.5f, 5f)
            lineTo(20.5f, 5f)
            lineTo(20.5f, 19f)
            lineTo(3.5f, 19f)
            close()
            moveTo(7f, 9.5f)
            lineTo(10.5f, 12.25f)
            lineTo(7f, 15f)
            moveTo(12.5f, 15f)
            lineTo(17f, 15f)
        }
    }

    val Catalog: ImageVector by lazy {
        strokeIcon("Catalog") {
            // 2x2 grid of crisp tiles
            moveTo(4f, 4f)
            lineTo(10.5f, 4f)
            lineTo(10.5f, 10.5f)
            lineTo(4f, 10.5f)
            close()
            moveTo(13.5f, 4f)
            lineTo(20f, 4f)
            lineTo(20f, 10.5f)
            lineTo(13.5f, 10.5f)
            close()
            moveTo(4f, 13.5f)
            lineTo(10.5f, 13.5f)
            lineTo(10.5f, 20f)
            lineTo(4f, 20f)
            close()
            moveTo(13.5f, 13.5f)
            lineTo(20f, 13.5f)
            lineTo(20f, 20f)
            lineTo(13.5f, 20f)
            close()
        }
    }

    val Network: ImageVector by lazy {
        strokeIcon("Network") {
            // Connected nodes
            moveTo(12f, 4f)
            lineTo(12f, 9f)
            moveTo(6f, 15f)
            lineTo(12f, 9f)
            lineTo(18f, 15f)
            moveTo(10f, 4f)
            lineTo(14f, 4f)
            lineTo(14f, 7f)
            lineTo(10f, 7f)
            close()
            moveTo(4f, 15f)
            lineTo(8f, 15f)
            lineTo(8f, 19f)
            lineTo(4f, 19f)
            close()
            moveTo(16f, 15f)
            lineTo(20f, 15f)
            lineTo(20f, 19f)
            lineTo(16f, 19f)
            close()
        }
    }

    val Files: ImageVector by lazy {
        strokeIcon("Files") {
            moveTo(3.5f, 6f)
            lineTo(9.5f, 6f)
            lineTo(11.5f, 8.5f)
            lineTo(20.5f, 8.5f)
            lineTo(20.5f, 18.5f)
            lineTo(3.5f, 18.5f)
            close()
        }
    }

    val Settings: ImageVector by lazy {
        strokeIcon("Settings") {
            // 3 horizontal sliders
            moveTo(4f, 7f)
            lineTo(20f, 7f)
            moveTo(4f, 12f)
            lineTo(20f, 12f)
            moveTo(4f, 17f)
            lineTo(20f, 17f)
            moveTo(8f, 5f)
            lineTo(8f, 9f)
            moveTo(15f, 10f)
            lineTo(15f, 14f)
            moveTo(10f, 15f)
            lineTo(10f, 19f)
        }
    }

    val Add: ImageVector by lazy {
        strokeIcon("Add") {
            moveTo(12f, 5f)
            lineTo(12f, 19f)
            moveTo(5f, 12f)
            lineTo(19f, 12f)
        }
    }

    val Play: ImageVector by lazy {
        fillIcon("Play") {
            moveTo(7f, 5.5f)
            lineTo(19f, 12f)
            lineTo(7f, 18.5f)
            close()
        }
    }

    val Pause: ImageVector by lazy {
        strokeIcon("Pause") {
            moveTo(8.5f, 6f)
            lineTo(8.5f, 18f)
            moveTo(15.5f, 6f)
            lineTo(15.5f, 18f)
        }
    }

    val Stop: ImageVector by lazy {
        fillIcon("Stop") {
            moveTo(6.5f, 6.5f)
            lineTo(17.5f, 6.5f)
            lineTo(17.5f, 17.5f)
            lineTo(6.5f, 17.5f)
            close()
        }
    }

    val Delete: ImageVector by lazy {
        strokeIcon("Delete") {
            moveTo(4.5f, 7f)
            lineTo(19.5f, 7f)
            moveTo(9.5f, 7f)
            lineTo(9.5f, 4.5f)
            lineTo(14.5f, 4.5f)
            lineTo(14.5f, 7f)
            moveTo(6.5f, 7f)
            lineTo(7.5f, 19.5f)
            lineTo(16.5f, 19.5f)
            lineTo(17.5f, 7f)
            moveTo(10.5f, 10.5f)
            lineTo(10.5f, 16f)
            moveTo(13.5f, 10.5f)
            lineTo(13.5f, 16f)
        }
    }

    val SplitHorizontal: ImageVector by lazy {
        strokeIcon("SplitHorizontal") {
            moveTo(4f, 5f)
            lineTo(20f, 5f)
            lineTo(20f, 19f)
            lineTo(4f, 19f)
            close()
            moveTo(12f, 5f)
            lineTo(12f, 19f)
        }
    }

    val SplitVertical: ImageVector by lazy {
        strokeIcon("SplitVertical") {
            moveTo(4f, 5f)
            lineTo(20f, 5f)
            lineTo(20f, 19f)
            lineTo(4f, 19f)
            close()
            moveTo(4f, 12f)
            lineTo(20f, 12f)
        }
    }

    val Close: ImageVector by lazy {
        strokeIcon("Close") {
            moveTo(6.5f, 6.5f)
            lineTo(17.5f, 17.5f)
            moveTo(17.5f, 6.5f)
            lineTo(6.5f, 17.5f)
        }
    }

    val Search: ImageVector by lazy {
        strokeIcon("Search") {
            moveTo(16.5f, 16.5f)
            lineTo(20.5f, 20.5f)
            moveTo(11f, 4.5f)
            arcTo(6.5f, 6.5f, 0f, true, true, 10.99f, 4.5f)
        }
    }

    val Keyboard: ImageVector by lazy {
        strokeIcon("Keyboard") {
            moveTo(3f, 6.5f)
            lineTo(21f, 6.5f)
            lineTo(21f, 17.5f)
            lineTo(3f, 17.5f)
            close()
            moveTo(6.5f, 10f)
            lineTo(8f, 10f)
            moveTo(11f, 10f)
            lineTo(13f, 10f)
            moveTo(16f, 10f)
            lineTo(17.5f, 10f)
            moveTo(7.5f, 14f)
            lineTo(16.5f, 14f)
        }
    }

    val Folder: ImageVector by lazy {
        strokeIcon("Folder") {
            moveTo(3.5f, 6f)
            lineTo(9.5f, 6f)
            lineTo(11.5f, 8.5f)
            lineTo(20.5f, 8.5f)
            lineTo(20.5f, 18.5f)
            lineTo(3.5f, 18.5f)
            close()
        }
    }

    val FileDoc: ImageVector by lazy {
        strokeIcon("FileDoc") {
            moveTo(6f, 3.5f)
            lineTo(14f, 3.5f)
            lineTo(18f, 7.5f)
            lineTo(18f, 20.5f)
            lineTo(6f, 20.5f)
            close()
            moveTo(14f, 3.5f)
            lineTo(14f, 7.5f)
            lineTo(18f, 7.5f)
            moveTo(9f, 12f)
            lineTo(15f, 12f)
            moveTo(9f, 15.5f)
            lineTo(15f, 15.5f)
        }
    }

    val VolumeDisk: ImageVector by lazy {
        strokeIcon("VolumeDisk") {
            // Cylinder / volume stack
            moveTo(4.5f, 7f)
            arcTo(7.5f, 2.5f, 0f, true, true, 19.5f, 7f)
            arcTo(7.5f, 2.5f, 0f, true, true, 4.5f, 7f)
            moveTo(4.5f, 7f)
            lineTo(4.5f, 17f)
            arcTo(7.5f, 2.5f, 0f, false, false, 19.5f, 17f)
            lineTo(19.5f, 7f)
            moveTo(4.5f, 12f)
            arcTo(7.5f, 2.5f, 0f, false, false, 19.5f, 12f)
        }
    }

    val Edit: ImageVector by lazy {
        strokeIcon("Edit") {
            moveTo(4f, 20f)
            lineTo(8.5f, 19f)
            lineTo(19f, 8.5f)
            lineTo(15.5f, 5f)
            lineTo(5f, 15.5f)
            close()
            moveTo(13.5f, 7f)
            lineTo(17f, 10.5f)
        }
    }

    val Refresh: ImageVector by lazy {
        strokeIcon("Refresh") {
            moveTo(19f, 12f)
            arcTo(7f, 7f, 0f, true, true, 16.5f, 6.6f)
            moveTo(19f, 4.5f)
            lineTo(19f, 8.5f)
            lineTo(15f, 8.5f)
        }
    }

    val Check: ImageVector by lazy {
        strokeIcon("Check") {
            moveTo(5f, 12.5f)
            lineTo(9.5f, 17f)
            lineTo(19f, 7.5f)
        }
    }

    val ChevronRight: ImageVector by lazy {
        strokeIcon("ChevronRight") {
            moveTo(9f, 6f)
            lineTo(15f, 12f)
            lineTo(9f, 18f)
        }
    }

    val Menu: ImageVector by lazy {
        strokeIcon("Menu") {
            moveTo(4f, 6.5f)
            lineTo(20f, 6.5f)
            moveTo(4f, 12f)
            lineTo(20f, 12f)
            moveTo(4f, 17.5f)
            lineTo(20f, 17.5f)
        }
    }

    val ArrowUp: ImageVector by lazy {
        strokeIcon("ArrowUp") {
            moveTo(12f, 19f)
            lineTo(12f, 5.5f)
            moveTo(6.5f, 11f)
            lineTo(12f, 5.5f)
            lineTo(17.5f, 11f)
        }
    }

    val Share: ImageVector by lazy {
        strokeIcon("Share") {
            moveTo(12f, 14f)
            lineTo(12f, 4.5f)
            moveTo(8f, 8.5f)
            lineTo(12f, 4.5f)
            lineTo(16f, 8.5f)
            moveTo(5f, 13.5f)
            lineTo(5f, 19.5f)
            lineTo(19f, 19.5f)
            lineTo(19f, 13.5f)
        }
    }

    val Warning: ImageVector by lazy {
        strokeIcon("Warning") {
            moveTo(12f, 4f)
            lineTo(21f, 19.5f)
            lineTo(3f, 19.5f)
            close()
            moveTo(12f, 10f)
            lineTo(12f, 14f)
            moveTo(12f, 17f)
            lineTo(12f, 17.2f)
        }
    }

    private fun strokeIcon(
        name: String,
        build: PathBuilder.() -> Unit,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = StrokePaint,
            strokeLineWidth = 1.75f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = build,
        )
    }.build()

    private fun fillIcon(
        name: String,
        build: PathBuilder.() -> Unit,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = StrokePaint,
            pathFillType = PathFillType.NonZero,
            pathBuilder = build,
        )
    }.build()
}
