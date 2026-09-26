package dev.xr.rayneo.probe;
final class ConnectionAttemptPolicyCheck {
    static void check(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args){
        check(ConnectionAttemptPolicy.labExistingBond("dev.xr.rayneo.sdklab",true,false,false,12));
        check(!ConnectionAttemptPolicy.labExistingBond("dev.xr.rayneo.probe",true,false,false,12));
        check(!ConnectionAttemptPolicy.labExistingBond("other.app",true,false,false,12));
        check(!ConnectionAttemptPolicy.labExistingBond("dev.xr.rayneo.sdklab",false,false,false,12));
        check(!ConnectionAttemptPolicy.labExistingBond("dev.xr.rayneo.sdklab",true,true,false,12));
        check(!ConnectionAttemptPolicy.labExistingBond("dev.xr.rayneo.sdklab",true,false,true,12));
        check(!ConnectionAttemptPolicy.labExistingBond("dev.xr.rayneo.sdklab",true,false,false,10));
        check(!ConnectionAttemptPolicy.labExistingBond("dev.xr.rayneo.sdklab",true,false,false,11));
        check(ConnectionAttemptPolicy.repairUnbonded(false,true,10));
        check(!ConnectionAttemptPolicy.repairUnbonded(true,true,10));
        check(!ConnectionAttemptPolicy.repairUnbonded(false,false,10));
        check(!ConnectionAttemptPolicy.repairUnbonded(false,true,11));
        check(!ConnectionAttemptPolicy.repairUnbonded(false,true,12));
        check(ConnectionAttemptPolicy.usePairingMode("AA:BB","aa:bb","DEVICE_IN_PAIRING_MODE",false));
        check(!ConnectionAttemptPolicy.usePairingMode("AA:BB","OTHER","DEVICE_IN_PAIRING_MODE",false));
        check(!ConnectionAttemptPolicy.usePairingMode("AA:BB","AA:BB","AUTH_FAILED",false));
        check(!ConnectionAttemptPolicy.usePairingMode("AA:BB","AA:BB","DEVICE_IN_PAIRING_MODE",true));
        check(!ConnectionAttemptPolicy.usePairingMode("","","DEVICE_IN_PAIRING_MODE",false));
        System.out.println("connection attempt policy checks passed");
    }
}
