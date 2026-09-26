package dev.xr.rayneo.probe;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Offline acceptance for the CDM association guard. The accumulation case is taken from a real
 * reading of dumpsys companiondevice, where one package held five associations for one MAC.
 * All addresses below are synthetic locally administered values, never real device identifiers. */
final class CompanionAssociationPolicyCheck {
    private static final String TARGET = "02:00:00:00:AB:CD";
    private static void require(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    public static void main(String[] args) {
        // 1. Nothing held yet: the association has to be requested.
        require(CompanionAssociationPolicy.needsRequest(Collections.<String>emptyList(), TARGET),
            "an empty association list must still request");

        // 2. Already held: requesting again is what accumulated five entries for another package.
        require(!CompanionAssociationPolicy.needsRequest(Arrays.asList(TARGET), TARGET),
            "a held association was requested again, which accumulates duplicates");

        // 3. The system prints the MAC lower case; our configuration carries it upper case.
        require(!CompanionAssociationPolicy.needsRequest(Arrays.asList("02:00:00:00:ab:cd"), TARGET),
            "case difference defeated the duplicate check");

        // 4. Other devices' associations must not be mistaken for ours.
        List<String> others = Arrays.asList("02:00:00:00:00:01", "02:00:00:00:00:02");
        require(CompanionAssociationPolicy.needsRequest(others, TARGET),
            "another device's association was treated as ours");

        // 5. Ours among others is still found.
        require(!CompanionAssociationPolicy.needsRequest(
                Arrays.asList("02:00:00:00:00:01", "02:00:00:00:ab:cd", "02:00:00:00:00:02"), TARGET),
            "a held association was missed when other devices were present");

        // 6. No target configured means nothing to request, and presence callbacks follow the API level.
        require(!CompanionAssociationPolicy.needsRequest(Collections.<String>emptyList(), ""),
            "an empty target must not request an association");
        require(!CompanionAssociationPolicy.presenceCallbackAvailable(29), "API 29 must not expect presence callbacks");
        require(!CompanionAssociationPolicy.presenceCallbackAvailable(30), "API 30 must not expect presence callbacks");
        require(CompanionAssociationPolicy.presenceCallbackAvailable(31), "API 31 must expect presence callbacks");
        require(CompanionAssociationPolicy.presenceCallbackAvailable(36), "API 36 must expect presence callbacks");

        System.out.println("passed");
    }
}
