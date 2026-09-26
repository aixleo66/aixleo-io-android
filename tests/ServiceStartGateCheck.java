package dev.xr.rayneo.probe;

final class ServiceStartGateCheck {
    static void check(boolean ok){if(!ok)throw new AssertionError();}
    public static void main(String[] args){
        ServiceStartGate gate=new ServiceStartGate();Object first=new Object(),second=new Object();
        gate.request(first);check(gate.hasOwner());
        check(gate.cancel(first)&&!gate.hasOwner()); // Creation may still arrive; it must promote then stop.
        gate.request(second);check(gate.hasOwner());
        check(!gate.cancel(first)&&gate.hasOwner()); // Old Activity cleanup cannot cancel a new session.
        check(gate.cancel(second)&&!gate.hasOwner());check(!gate.cancel(second));
        System.out.println("service request ownership checks passed");
    }
}
