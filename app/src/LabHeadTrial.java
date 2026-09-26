package dev.xr.rayneo.probe;

/** Configuration-only head mode roundtrip; no system-suggestion behavior assertion. */
final class LabHeadTrial extends LabSettingsTrial<RayNeoHeadSettings> {
    interface Port extends LabSettingsTrial.Port<RayNeoHeadSettings> {}
    private static final Policy<RayNeoHeadSettings> POLICY=new Policy<RayNeoHeadSettings>(){
        public RayNeoHeadSettings target(RayNeoHeadSettings original){return original.alternateMode();}
        public boolean same(RayNeoHeadSettings a,RayNeoHeadSettings b){return a!=null&&a.same(b);}
    };
    LabHeadTrial(String id,Port port){super(id,port,POLICY,"head",3000);}
    LabHeadTrial(String id,Port port,RayNeoHeadSettings original,RayNeoHeadSettings target){
        super(id,port,POLICY,"head",3000,original,target);
    }
}
