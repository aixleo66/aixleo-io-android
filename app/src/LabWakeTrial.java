package dev.xr.rayneo.probe;

/** Head-up angle only; switch and screen-off crown interaction are preserved. */
final class LabWakeTrial extends LabSettingsTrial<RayNeoWakeSettings> {
    interface Port extends LabSettingsTrial.Port<RayNeoWakeSettings> {}
    private static final Policy<RayNeoWakeSettings> POLICY=new Policy<RayNeoWakeSettings>(){
        public RayNeoWakeSettings target(RayNeoWakeSettings original){return original.withHeadUpDegrees(original.headupDegree==30?15:30);}
        public boolean same(RayNeoWakeSettings a,RayNeoWakeSettings b){return a!=null&&a.same(b);}
    };
    LabWakeTrial(String id,Port port){super(id,port,POLICY,"wake",45000);}
    private static Policy<RayNeoWakeSettings> comparison(final int degrees){
        if(degrees!=5&&degrees!=60)throw new IllegalArgumentException("Unsupported comparison angle");
        return new Policy<RayNeoWakeSettings>(){
            public RayNeoWakeSettings target(RayNeoWakeSettings original){return original.withHeadUpDegrees(degrees);}
            public boolean same(RayNeoWakeSettings a,RayNeoWakeSettings b){return a!=null&&a.same(b);}
        };
    }
    LabWakeTrial(String id,Port port,int degrees){super(id,port,comparison(degrees),"wake",60000);}
    LabWakeTrial(String id,Port port,RayNeoWakeSettings original,RayNeoWakeSettings target){
        super(id,port,POLICY,"wake",45000,original,target);
    }
}
