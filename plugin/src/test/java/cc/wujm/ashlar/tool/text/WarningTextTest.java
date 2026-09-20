// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.text;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Port of {@code mcp-server/src/tools/warnings.test.ts} (docs/private/prompts/step7b-prompt.md).
 * Test names mirror the TS test titles.
 */
class WarningTextTest {

    @Test
    void formatWarnings_noWarningsReturnsAnEmptyArray() {
        assertEquals(List.of(), WarningText.formatWarnings(List.of(), false));
    }

    @Test
    void formatWarnings_aSingleWarningIsNotGroupedIntoARange() {
        List<WarningText.SupportWarning> warnings = List.of(
                new WarningText.SupportWarning(563, 66, -425, "minecraft:torch", "embedded"));
        List<String> lines = WarningText.formatWarnings(warnings, false);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).startsWith("WARNINGS"));
        assertTrue(lines.get(1).matches("^ {2}1x minecraft:torch at 563,66,-425: embedded.*"));
    }

    @Test
    void formatWarnings_consecutiveYPositionsWithIdenticalBlockReasonGroupIntoOneRangeLine() {
        List<WarningText.SupportWarning> warnings = new ArrayList<>();
        for (int y = 65; y <= 82; y++) {
            warnings.add(new WarningText.SupportWarning(561, y, -428, "minecraft:ladder[facing=east]",
                    "no support behind (facing=east needs a solid block at x-1)"));
        }
        List<String> lines = WarningText.formatWarnings(warnings, false);
        assertEquals(2, lines.size());
        assertEquals(
                "  18x minecraft:ladder[facing=east] at x=561 y=65..82 z=-428: no support behind (facing=east needs a solid block at x-1)",
                lines.get(1));
    }

    @Test
    void formatWarnings_differentReasonsForTheSameBlockNeverMergeIntoOneGroup() {
        List<WarningText.SupportWarning> warnings = List.of(
                new WarningText.SupportWarning(0, 64, 0, "minecraft:torch", "nothing solid below"),
                new WarningText.SupportWarning(1, 64, 0, "minecraft:torch", "embedded"));
        List<String> lines = WarningText.formatWarnings(warnings, false);
        assertEquals(3, lines.size());
    }

    @Test
    void formatWarnings_aNonContiguousAxisRunAGapStaysAsSeparateSinglePositionLines() {
        List<WarningText.SupportWarning> warnings = List.of(
                new WarningText.SupportWarning(10, 64, 0, "minecraft:oak_carpet", "nothing solid below"),
                new WarningText.SupportWarning(12, 64, 0, "minecraft:oak_carpet", "nothing solid below")); // gap at x=11
        List<String> lines = WarningText.formatWarnings(warnings, false);
        assertEquals(3, lines.size());
        assertTrue(lines.get(1).contains("at 10,64,0:"));
        assertTrue(lines.get(2).contains("at 12,64,0:"));
    }

    @Test
    void formatWarnings_truncatedAppendsATrailingNote() {
        List<WarningText.SupportWarning> warnings = List.of(
                new WarningText.SupportWarning(0, 64, 0, "minecraft:torch", "nothing solid below"));
        List<String> lines = WarningText.formatWarnings(warnings, true);
        assertEquals(3, lines.size());
        assertTrue(lines.get(2).contains("capped at 50"));
    }

    @Test
    void formatWarnings_aRunAlongXFixedYZGroupsOnXWithYZShownAsFixedValues() {
        List<WarningText.SupportWarning> warnings = new ArrayList<>();
        for (int x = 100; x <= 104; x++) {
            warnings.add(new WarningText.SupportWarning(x, 70, 200, "minecraft:oak_carpet", "nothing solid below"));
        }
        List<String> lines = WarningText.formatWarnings(warnings, false);
        assertEquals(2, lines.size());
        assertEquals("  5x minecraft:oak_carpet at x=100..104 y=70 z=200: nothing solid below", lines.get(1));
    }
}
