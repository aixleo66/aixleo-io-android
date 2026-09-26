package dev.xr.rayneo.probe;

/** A confirmed pairing-mode rejection may select pairing on the next explicit
 * connect attempt for the same target; never loop or erase stored bonds. */
final class ConnectionAttemptPolicy {
    static boolean labExistingBond(String packageName, boolean explicitPairing,
                                   boolean hasSaved, boolean systemConnected, int bondState) {
        return "dev.xr.rayneo.sdklab".equals(packageName) && explicitPairing
            && !hasSaved && !systemConnected && bondState == 12;
    }
    static boolean repairUnbonded(boolean automatic, boolean hasSaved, int bondState){
        return !automatic && hasSaved && bondState == 10;
    }
    static boolean usePairingMode(String target,String previousTarget,String error,boolean alreadyTried){
        return target!=null&&!target.isEmpty()&&target.equalsIgnoreCase(previousTarget)
            &&"DEVICE_IN_PAIRING_MODE".equals(error)&&!alreadyTried;
    }
}
