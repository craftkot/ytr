package ru.ehtid.operator.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import ru.ehtid.operator.data.PlayerMemory;

/**
 * Deterministic, server-local decision core.
 *
 * This class only turns observed behaviour into a proposed intervention.
 * It never reads or changes Minecraft world state. World mutation is a
 * separate future subsystem and must have its own validation and rollback.
 */
public final class OperatorBrain {
    public static final long REQUIRED_PERSONAL_TICKS = 24_000L;
    public static final long EVALUATION_INTERVAL_TICKS = 200L;
    public static final long DECISION_COOLDOWN_TICKS = 6_000L;

    private static final double MINIMUM_PLAN_SCORE = 0.30D;

    private OperatorBrain() { }

    public static Decision evaluate(PlayerMemory memory, boolean worldObservationComplete, long gameTick) {
        if (memory == null) {
            return new Decision("NO_PROFILE", "UNKNOWN", "NONE", 0.0D,
                    "No player memory is available.");
        }

        BehaviourScores scores = scoreBehaviour(memory);
        String archetype = classify(scores);

        if (!worldObservationComplete || !memory.personalObservationComplete(REQUIRED_PERSONAL_TICKS)) {
            return new Decision("OBSERVING", archetype, memory.plannedOperatorEvent,
                    scores.maximum(), "The world and this player's 24,000-tick observation gates must both complete.");
        }

        Candidate candidate = chooseCandidate(memory, scores);
        if (candidate == null) {
            return new Decision("CALIBRATING", archetype, memory.plannedOperatorEvent,
                    memory.operatorConfidence, "Observation is complete, but there is not enough reliable behavioural evidence for a plan.");
        }

        String currentPlan = normalizeEvent(memory.plannedOperatorEvent);
        if (!"NONE".equals(currentPlan) && !currentPlan.equals(candidate.event())
                && memory.lastOperatorDecisionAt >= 0L
                && Math.max(0L, gameTick - memory.lastOperatorDecisionAt) < DECISION_COOLDOWN_TICKS) {
            long remaining = DECISION_COOLDOWN_TICKS
                    - Math.max(0L, gameTick - memory.lastOperatorDecisionAt);
            return new Decision("COOLDOWN", archetype, currentPlan, memory.operatorConfidence,
                    "A new candidate (" + candidate.event() + ") is waiting for the decision cooldown (" + remaining + " ticks remain).");
        }

        return new Decision("READY", archetype, candidate.event(), candidate.score(), candidate.reason());
    }

    /** Persists the latest shadow decision. Returns true only when stored state changed. */
    public static boolean apply(PlayerMemory memory, Decision decision, long gameTick) {
        if (memory == null || decision == null) return false;

        boolean changed = false;
        if (memory.lastBrainEvaluationAt != gameTick) {
            memory.lastBrainEvaluationAt = gameTick;
            changed = true;
        }
        if (!decision.phase().equals(memory.operatorPhase)) {
            memory.operatorPhase = decision.phase();
            changed = true;
        }
        if (!decision.archetype().equals(memory.operatorArchetype)) {
            memory.operatorArchetype = decision.archetype();
            changed = true;
        }
        if (!decision.reason().equals(memory.operatorReason)) {
            memory.operatorReason = decision.reason();
            changed = true;
        }

        double confidence = clamp01(decision.confidence());
        if (Math.abs(memory.operatorConfidence - confidence) > 0.0001D) {
            memory.operatorConfidence = confidence;
            changed = true;
        }

        String event = normalizeEvent(decision.event());
        if ("READY".equals(decision.phase()) && !"NONE".equals(event)
                && !event.equals(normalizeEvent(memory.plannedOperatorEvent))) {
            memory.plannedOperatorEvent = event;
            memory.lastOperatorDecisionAt = gameTick;
            memory.operatorDecisionCount++;
            changed = true;
        }
        return changed;
    }

    private static BehaviourScores scoreBehaviour(PlayerMemory memory) {
        double mining = 0.65D * ratio(memory.rockBlocksBroken, 160.0D)
                + 0.15D * ratio(memory.miningSessions, 24.0D)
                + 0.20D * ratio(memory.getMinedTunnelCellCount(), 256.0D);

        double exploration = 0.60D * ratio(memory.distanceTravelled, 6_000.0D)
                + 0.25D * ratio(memory.route == null ? 0 : memory.route.size(), 96.0D)
                + 0.15D * ratio(memory.dimensionVisits == null ? 0 : memory.dimensionVisits.size(), 3.0D);

        double caving = 0.55D * ratio(memory.getTunnelCellCount(), 512.0D)
                + 0.35D * ratio(memory.getObservedAirCellCount(), 256.0D)
                + 0.10D * ratio(memory.miningSessions, 20.0D);

        double builder = 0.60D * ratio(memory.largestBuildingBlockCount, 64.0D)
                + 0.25D * ratio(memory.recognizedBuildingCount, 3.0D)
                + 0.15D * ratio(memory.constructionComplexity, 1.0D);

        return new BehaviourScores(mining, exploration, caving, builder);
    }

    private static String classify(BehaviourScores scores) {
        List<NamedScore> ordered = List.of(
                new NamedScore("MINER", scores.mining()),
                new NamedScore("EXPLORER", scores.exploration()),
                new NamedScore("CAVER", scores.caving()),
                new NamedScore("BUILDER", scores.builder())
        ).stream().sorted(Comparator.comparingDouble(NamedScore::score).reversed()).toList();

        NamedScore first = ordered.get(0);
        NamedScore second = ordered.get(1);
        if (first.score() < 0.18D) return "UNKNOWN";
        if (first.score() - second.score() <= 0.08D) return "MIXED";
        return first.name();
    }

    private static Candidate chooseCandidate(PlayerMemory memory, BehaviourScores scores) {
        List<Candidate> candidates = new ArrayList<>();

        if (memory.getTunnelCellCount() >= 64) {
            double score = 0.75D * scores.caving() + 0.25D * scores.mining();
            candidates.add(new Candidate("TUNNEL_FOLD", score,
                    "The player has a sufficiently mapped tunnel network; the planner prioritizes spatial-route interference."));
        }

        int routeSize = memory.route == null ? 0 : memory.route.size();
        if (routeSize >= 12) {
            double routeCoverage = ratio(routeSize, 96.0D);
            double score = 0.85D * scores.exploration() + 0.15D * routeCoverage;
            candidates.add(new Candidate("PATH_ECHO", score,
                    "The player's route history is detailed enough to plan a subtle navigation anomaly."));
        }

        if (memory.hasEnoughMiningData()) {
            double depthCoverage = ratio(memory.miningDepthBands == null ? 0 : memory.miningDepthBands.size(), 4.0D);
            double score = 0.75D * scores.mining() + 0.25D * depthCoverage;
            candidates.add(new Candidate("DEPTH_LURE", score,
                    "Repeated mining and depth data support a plan that targets the player's familiar underground habits."));
        }

        if (memory.recognizedBuildingCount > 0 && memory.largestBuildingBlockCount >= 24) {
            double placementCoverage = ratio(memory.blocksPlaced, 128.0D);
            double score = 0.75D * scores.builder() + 0.25D * placementCoverage;
            candidates.add(new Candidate("HOME_ECHO", score,
                    "A connected player-placed 3D component matches building-candidate geometry; the planner can target the player's constructed spaces."));
        }

        int dimensions = memory.dimensionVisits == null ? 0 : memory.dimensionVisits.size();
        if (dimensions >= 2) {
            double dimensionCoverage = ratio(dimensions, 3.0D);
            double score = 0.65D * scores.exploration() + 0.35D * dimensionCoverage;
            candidates.add(new Candidate("DIMENSION_DRIFT", score,
                    "The player has visited multiple dimensions; the planner can reason about dimension-specific expectations."));
        }

        return candidates.stream()
                .filter(candidate -> candidate.score() >= MINIMUM_PLAN_SCORE)
                .max(Comparator.comparingDouble(Candidate::score)
                        .thenComparing(Candidate::event, Comparator.reverseOrder()))
                .orElse(null);
    }

    private static String normalizeEvent(String event) {
        return event == null || event.isBlank() ? "NONE" : event;
    }

    private static double ratio(double value, double ceiling) {
        if (!Double.isFinite(value) || value <= 0.0D || ceiling <= 0.0D) return 0.0D;
        return clamp01(value / ceiling);
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) return 0.0D;
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    public record Decision(String phase, String archetype, String event, double confidence, String reason) { }

    private record BehaviourScores(double mining, double exploration, double caving, double builder) {
        private double maximum() {
            return Math.max(builder, Math.max(mining, Math.max(exploration, caving)));
        }
    }

    private record NamedScore(String name, double score) { }

    private record Candidate(String event, double score, String reason) { }
}
