package dev.xr.rayneo.probe;

final class ConnectionReadinessCheck {
    static void check(boolean value) { if(!value)throw new AssertionError(); }
    public static void main(String[] args) {
        // Reproduction: authenticated + battery response + old phone bond, while
        // glasses are back in pairing mode, must not enable business operations.
        check(!ConnectionReadiness.ready(true,true,12,true,false));
        check(!ConnectionReadiness.permitsCommand(false,"record-start"));
        check(!ConnectionReadiness.permitsCommand(false,"voice-standby"));
        check(!ConnectionReadiness.permitsCommand(false,"notify"));
        check(ConnectionReadiness.permitsCommand(false,"stop"));
        check(ConnectionReadiness.permitsCommand(false,"status"));
        check(ConnectionReadiness.permitsCommand(false,"pair"));
        // Normal reconnect remains usable; a fresh attempt needs a real callback.
        check(ConnectionReadiness.ready(true,true,12,false,false));
        check(ConnectionReadiness.ready(true,true,12,true,true));
        for(int state:new int[]{10,11}) {
            check(!ConnectionReadiness.ready(true,true,state,false,true));
            check(!ConnectionReadiness.ready(true,true,state,true,true));
        }
        check(!ConnectionReadiness.ready(false,true,12,true,true));
        check(!ConnectionReadiness.ready(true,false,12,true,true));
        check(ConnectionReadiness.permitsCommand(true,"record-start"));
        System.out.println("connection readiness checks passed");
    }
}
