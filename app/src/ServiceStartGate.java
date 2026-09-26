package dev.xr.rayneo.probe;

/** Main-thread service request ownership. Cancelling a queued foreground start
 * must let Service.onCreate promote itself before it can stop. */
final class ServiceStartGate {
    private Object owner;
    void request(Object candidate){owner=candidate;}
    boolean cancel(Object candidate){if(owner!=candidate)return false;owner=null;return true;}
    boolean hasOwner(){return owner!=null;}
}
