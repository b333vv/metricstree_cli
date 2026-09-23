package org.b333vv.metric.cli;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProfilesTest {

    @Test
    void allThreeProfilesLoadWithTheSameKeySet() {
        Map<String, Threshold> relaxed = Profiles.thresholds("relaxed", null);
        Map<String, Threshold> standard = Profiles.thresholds("standard", null);
        Map<String, Threshold> strict = Profiles.thresholds("strict", null);

        assertFalse(standard.isEmpty());
        assertEquals(standard.keySet(), relaxed.keySet());
        assertEquals(standard.keySet(), strict.keySet());
    }

    @Test
    void strictIsTighterThanStandardAndRelaxedIsLooser() {
        Map<String, Threshold> relaxed = Profiles.thresholds("relaxed", null);
        Map<String, Threshold> standard = Profiles.thresholds("standard", null);
        Map<String, Threshold> strict = Profiles.thresholds("strict", null);

        // Spot-check an integer-capped metric...
        assertTrue(strict.get("CC").max() <= standard.get("CC").max());
        assertTrue(standard.get("CC").max() <= relaxed.get("CC").max());
        // ...and a ratio metric, where the lower bound is the one that moves.
        assertTrue(strict.get("TCC").min() >= standard.get("TCC").min());
        assertTrue(standard.get("TCC").min() >= relaxed.get("TCC").min());
    }

    @Test
    void unknownProfileNamesAllValidOnesInTheError() {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class, () -> Profiles.thresholds("paranoid", null));

        assertTrue(e.getMessage().contains("paranoid"));
        assertTrue(e.getMessage().contains("relaxed"));
        assertTrue(e.getMessage().contains("standard"));
        assertTrue(e.getMessage().contains("strict"));
    }
}
