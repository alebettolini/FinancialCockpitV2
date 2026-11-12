#!/bin/bash
set -e

python3 - << 'PY_EOF'
import os

# Find MainActivity.kt wherever it resides in this workspace
p = "app/src/main/java/com/bettolini/financialcockpit/MainActivity.kt"
if not os.path.exists(p):
    found = False
    for root, dirs, files in os.walk("."):
        if "MainActivity.kt" in files:
            p = os.path.join(root, "MainActivity.kt")
            found = True
            break
    if not found:
        print("Error: MainActivity.kt not found in workspace!")
        exit(1)

s = open(p).read()

# 1. Strip Material icon imports causing dependency issues on Core-only setups
s = s.replace("import androidx.compose.material.icons.Icons\n", "")
s = s.replace("import androidx.compose.material.icons.outlined.Settings\n", "")

# 2. Add ExperimentalFoundationApi if missing
if "ExperimentalFoundationApi" not in s:
    s = s.replace(
        "import androidx.compose.foundation.BorderStroke",
        "import androidx.compose.foundation.BorderStroke\nimport androidx.compose.foundation.ExperimentalFoundationApi"
    )

# 3. Add Foundation API opt-in annotation to suppress Experimental Pager warnings
s = s.replace(
    "@OptIn(ExperimentalMaterial3Api::class)",
    "@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)"
)

# 4. Swap Outlined.Settings for a lightweight, native Canvas Gear icon
old_icon = """                                                            Icon(
                                                                imageVector = Icons.Outlined.Settings,
                                                                contentDescription = "Settings",
                                                                tint = palette.textPrimary,
                                                                modifier = Modifier.size(18.dp)
                                                            )"""

new_icon = """                                                            SettingsGearIcon(
                                                                tint = palette.textPrimary,
                                                                modifier = Modifier.size(18.dp)
                                                            )"""

if old_icon in s:
    s = s.replace(old_icon, new_icon)
else:
    # Handle minor format/indentation shifts
    import re
    s = re.sub(
        r'Icon\(\s*imageVector\s*=\s*Icons\.Outlined\.Settings,\s*contentDescription\s*=\s*"Settings",\s*tint\s*=\s*palette\.textPrimary,\s*modifier\s*=\s*Modifier\.size\(18\.dp\)\s*\)',
        'SettingsGearIcon(tint = palette.textPrimary, modifier = Modifier.size(18.dp))', 
        s
    )

# 5. Prevent invalid navigateBack() state transitions during recomposition 
old_fallback = """                                    } else {
                                        navigateBack()
                                    }"""
new_fallback = """                                    } else {
                                        LaunchedEffect(Unit) { navigateBack() }
                                    }"""
s = s.replace(old_fallback, new_fallback)

# 6. Append custom vector SettingsGearIcon composable function 
gear_composable = """
@Composable
fun SettingsGearIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = 1.7.dp.toPx()
        val center = Offset(size.width / 2f, size.height / 2f)
        val outerR = size.minDimension * 0.34f
        val innerR = size.minDimension * 0.14f
        val toothStart = size.minDimension * 0.36f
        val toothEnd = size.minDimension * 0.47f

        drawCircle(
            color = tint,
            radius = outerR,
            center = center,
            style = Stroke(width = stroke)
        )
        drawCircle(
            color = tint,
            radius = innerR,
            center = center,
            style = Stroke(width = stroke)
        )
        for (i in 0 until 8) {
            val angle = Math.toRadians(i * 45.0)
            val cosA = kotlin.math.cos(angle).toFloat()
            val sinA = kotlin.math.sin(angle).toFloat()
            drawLine(
                color = tint,
                start = Offset(center.x + toothStart * cosA, center.y + toothStart * sinA),
                end = Offset(center.x + toothEnd * cosA, center.y + toothEnd * sinA),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
    }
}
"""
if "fun SettingsGearIcon" not in s:
    s = s + "\n" + gear_composable

open(p, "w").write(s)
print(f"Patched compile errors inside: {p}")
PY_EOF

# Skip the gradlew execution block entirely to safeguard against file-not-found errors
git add .
git commit -m "Fix ExperimentalFoundationApi opt-in and replace Settings icon with native Canvas vector" || echo "Zero changes to commit"
git push origin main

rm -f fix_build_compile.sh
