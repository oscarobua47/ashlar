// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.engine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.rcwalter.ashlar.config.PluginConfig;
import net.rcwalter.ashlar.rpc.ErrorCode;
import net.rcwalter.ashlar.rpc.RpcError;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RequestValidator}'s {@code liquids: "flow"} support (step8d-prompt.md):
 * {@link RequestValidator#resolveLiquidsFlow} parsing and the {@code
 * limits.max-flowing-liquids-per-operation} counting logic ({@link
 * RequestValidator#sumFlowingLiquidWeight}, backed by {@link LiquidBlocks#isFlowableBlockString}).
 *
 * <p>Deliberately does not call {@link RequestValidator#validateFillOps}/{@link
 * RequestValidator#validateSparseOps} directly: both also parse block strings into real {@code
 * BlockData} via {@link BlockDataParser} (i.e. {@code Bukkit.createBlockData}), which needs a
 * live Paper block registry not present on the plain JUnit classpath (paper-api is {@code
 * compileOnly}, not a test runtime dependency). {@link RequestValidator#sumFlowingLiquidWeight}
 * and {@link LiquidBlocks#isFlowableBlockString} were factored out specifically so the
 * counting/cap logic itself - the part step8d-prompt.md asks to unit test - is pure Java that
 * needs none of that (step8d report has the full rationale).
 */
class RequestValidatorTest {

    private static PluginConfig config(long maxFlowingLiquidsPerOperation) {
        return new PluginConfig(
                new PluginConfig.ServerConfig("0.0.0.0", 8765, "0123456789abcdef", List.of()),
                new PluginConfig.LimitsConfig(500_000, 200_000, 20, 16, 1024, maxFlowingLiquidsPerOperation),
                new PluginConfig.WorldConfig("world", List.of("world"),
                        new PluginConfig.WorldConfig.BuildRegion(false, -1000, -1000, 1000, 1000)),
                new PluginConfig.SnapshotConfig(true, 20, 200_000),
                new PluginConfig.LoggingConfig(true),
                new PluginConfig.RunCommandConfig(true),
                new PluginConfig.EngineConfig(true, true),
                new PluginConfig.AgentConfig(PluginConfig.AgentConfig.Mode.OFF, 5, 500, true, false,
                        new PluginConfig.AgentConfig.ModelConfig("http://localhost", "", "test-model", 25, 120_000,
                                "high", "", false),
                        new PluginConfig.AgentConfig.LimitsConfig(40, 0, 0, 2, 6, 30),
                        new PluginConfig.AgentConfig.PricingConfig(0.30, 0.006, 1.20, "USD", "always", 0.5)));
    }

    private static JsonObject params(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    // --- resolveLiquidsFlow (pure: JsonObject + RpcError only, no Bukkit) --------------------

    @Test
    void resolveLiquidsFlowDefaultsToStatic() {
        RequestValidator validator = new RequestValidator(config(2000));
        assertFalse(validator.resolveLiquidsFlow(params("{}")));
    }

    @Test
    void resolveLiquidsFlowStaticIsFalse() {
        RequestValidator validator = new RequestValidator(config(2000));
        assertFalse(validator.resolveLiquidsFlow(params("{\"liquids\":\"static\"}")));
    }

    @Test
    void resolveLiquidsFlowFlowIsTrue() {
        RequestValidator validator = new RequestValidator(config(2000));
        assertTrue(validator.resolveLiquidsFlow(params("{\"liquids\":\"flow\"}")));
    }

    @Test
    void resolveLiquidsFlowInvalidValueThrows() {
        RequestValidator validator = new RequestValidator(config(2000));
        RpcError e = assertThrows(RpcError.class, () -> validator.resolveLiquidsFlow(params("{\"liquids\":\"gushing\"}")));
        assertEquals(ErrorCode.BAD_REQUEST, e.code());
    }

    // --- LiquidBlocks.isFlowableBlockString (pure string matching) ---------------------------

    @Test
    void plainWaterAndLavaAreFlowable() {
        assertTrue(LiquidBlocks.isFlowableBlockString("minecraft:water"));
        assertTrue(LiquidBlocks.isFlowableBlockString("minecraft:lava"));
    }

    @Test
    void waterWithStatePropertiesIsFlowable() {
        assertTrue(LiquidBlocks.isFlowableBlockString("minecraft:water[level=3]"));
    }

    @Test
    void stoneIsNotFlowable() {
        assertFalse(LiquidBlocks.isFlowableBlockString("minecraft:stone"));
    }

    @Test
    void bubbleColumnIsNotFlowable() {
        // Deliberately narrower than ConnectionPass's internal liquid check: bubble columns are
        // not liquids for liquids:"flow" (step8d-prompt.md).
        assertFalse(LiquidBlocks.isFlowableBlockString("minecraft:bubble_column"));
    }

    @Test
    void waterloggedFenceIsNotFlowable() {
        // Its id is the fence, not water/lava - "waterlogged blocks do NOT count" (step8d-prompt.md).
        assertFalse(LiquidBlocks.isFlowableBlockString("minecraft:oak_fence[waterlogged=true]"));
    }

    @Test
    void nullBlockStringIsNotFlowable() {
        assertFalse(LiquidBlocks.isFlowableBlockString(null));
    }

    // --- RequestValidator.sumFlowingLiquidWeight (pure counting) -----------------------------

    @Test
    void sumIgnoresNonLiquidCandidates() {
        long sum = RequestValidator.sumFlowingLiquidWeight(List.of(
                new RequestValidator.LiquidCandidate("minecraft:stone", 1_000_000),
                new RequestValidator.LiquidCandidate("minecraft:glass", 500)));
        assertEquals(0, sum);
    }

    @Test
    void sumAddsUpLiquidCandidatesOnly() {
        long sum = RequestValidator.sumFlowingLiquidWeight(List.of(
                new RequestValidator.LiquidCandidate("minecraft:water", 1089),
                new RequestValidator.LiquidCandidate("minecraft:stone", 999_999),
                new RequestValidator.LiquidCandidate("minecraft:lava", 1089)));
        assertEquals(2178, sum);
    }

    @Test
    void sumOfEmptyListIsZero() {
        assertEquals(0, RequestValidator.sumFlowingLiquidWeight(List.of()));
    }

    // --- End-to-end cap enforcement: sum + checkVolumeLimit, exactly as validateFillOps/
    // validateSparseOps combine them -----------------------------------------------------------

    @Test
    void underCapPasses() {
        RequestValidator validator = new RequestValidator(config(2000));
        long sum = RequestValidator.sumFlowingLiquidWeight(
                List.of(new RequestValidator.LiquidCandidate("minecraft:water", 100)));
        validator.checkVolumeLimit(sum, 2000, "flowing liquid blocks");
    }

    @Test
    void overCapRejectedWithCapNamedInMessage() {
        RequestValidator validator = new RequestValidator(config(2000));
        long sum = RequestValidator.sumFlowingLiquidWeight(
                List.of(new RequestValidator.LiquidCandidate("minecraft:water", 2500)));
        RpcError e = assertThrows(RpcError.class, () -> validator.checkVolumeLimit(sum, 2000, "flowing liquid blocks"));
        assertEquals(ErrorCode.VOLUME_EXCEEDED, e.code());
        assertTrue(e.getMessage().contains("flowing liquid blocks"), e.getMessage());
        assertTrue(e.getMessage().contains("2000"), e.getMessage());
    }

    @Test
    void multipleOpsSumTogetherAcrossTheCap() {
        RequestValidator validator = new RequestValidator(config(2000));
        // Two ops, 1089 each: neither alone exceeds 2000, but together (2178) they do.
        long sum = RequestValidator.sumFlowingLiquidWeight(List.of(
                new RequestValidator.LiquidCandidate("minecraft:water", 1089),
                new RequestValidator.LiquidCandidate("minecraft:water", 1089)));
        assertTrue(sum > 2000);
        assertThrows(RpcError.class, () -> validator.checkVolumeLimit(sum, 2000, "flowing liquid blocks"));
    }
}
