package dev.xr.rayneo.probe;

/** A transport response is not proof of a completed pairing attempt. */
final class ConnectionReadiness {
    static boolean ready(boolean authenticated, boolean statusReceived, int currentBond,
                         boolean freshPairingRequired, boolean pairingConfirmed) {
        return authenticated && statusReceived && currentBond == 12
            && (!freshPairingRequired || pairingConfirmed);
    }
    static boolean permitsCommand(boolean ready, String kind) {
        return ready || "stop".equals(kind) || "status".equals(kind)
            || "standby-off".equals(kind) || "pair".equals(kind);
    }
}
