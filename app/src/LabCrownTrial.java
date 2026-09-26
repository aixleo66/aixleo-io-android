package dev.xr.rayneo.probe;

/** Crown trial keeps the existing command identities and 45-second observation window. */
final class LabCrownTrial extends LabSettingsTrial<RayNeoCrownSettings> {
    interface Port extends LabSettingsTrial.Port<RayNeoCrownSettings> {}
    private static final Policy<RayNeoCrownSettings> POLICY=new Policy<RayNeoCrownSettings>(){
        public RayNeoCrownSettings target(RayNeoCrownSettings original){return original.alternateDirection();}
        public boolean same(RayNeoCrownSettings a,RayNeoCrownSettings b){return a!=null&&a.same(b);}
    };
    LabCrownTrial(String id,Port port){super(id,port,POLICY,"crown",45000);}
    LabCrownTrial(String id,Port port,RayNeoCrownSettings original,RayNeoCrownSettings target){
        super(id,port,POLICY,"crown",45000,original,target);
    }
}
