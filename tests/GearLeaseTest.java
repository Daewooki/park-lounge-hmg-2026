package ai.nc.varcospaces;

/** Real ParkedState lease transitions; no Android runtime or fake platform implementation. */
public final class GearLeaseTest {
    private static int assertions;
    private static void check(boolean value, String label) {
        assertions++; if (!value) throw new AssertionError(label);
    }
    private static boolean parked(ParkedState.GearLease state, long now) {
        return SafetyPolicy.mayUseParkedContent(true, true, false, 0,
            state.available(now), state.value == 4);
    }
    public static void main(String[] args) {
        ParkedState.GearLease state = new ParkedState.GearLease();
        check(!parked(state, 0), "Initial state cannot assume P");
        state.event(true, 4, 100);
        check(!parked(state, 10), "P event alone cannot unlock without a successful read");
        check(state.confirm(state.revision, 4, 100, 20), "Independent P read accepts current event timestamp");
        check(parked(state, 20), "Fresh confirmed P unlocks");
        check(parked(state, 2519), "Lease lasts strictly less than 2500 ms from request start");
        check(!parked(state, 2520), "Missing callbacks expire even with unchanged P");
        check(!parked(state, 19), "Clock reversal cannot extend permission");

        state.confirm(state.revision, 4, 100, 3000);
        state.event(true, 4, 101);
        check(parked(state, 4000), "Same P event may preserve an existing fresh read");
        check(!parked(state, 5500), "P notifications cannot renew the read lease");
        check(state.confirm(state.revision, 4, 101, 5500), "Unchanged valid P can be independently revalidated");
        long oldReadRevision = state.revision;
        state.event(true, 8, 200);
        check(!parked(state, 5501), "New D immediately closes gate before next periodic read");
        check(!state.confirm(oldReadRevision, 4, 201, 5502), "Read begun before D cannot resurrect P even with newer timestamp");
        check(!parked(state, 5502), "Late P read stays locked");
        check(!state.confirm(state.revision, 4, 101, 5503), "Older VHAL P cannot overwrite newer injected/event D");
        check(!state.event(true, 4, 102), "Out-of-order P event is ignored");
        check(!parked(state, 5504), "Out-of-order event never unlocks");
        check(state.confirm(state.revision, 8, 200, 5505), "Fresh D read is a valid available value");
        check(state.available(5506) && !parked(state, 5506), "Available D is never parked");

        state.event(true, 4, 300);
        check(!parked(state, 5600), "D to P event still requires independent read");
        state.confirm(state.revision, 4, 300, 5601);
        check(parked(state, 5602), "Fresh P confirmation restores eligibility only");
        state.event(false, 4, 301);
        check(!parked(state, 5603), "Unavailable status with stored P immediately closes gate");
        check(!state.confirm(state.revision, 4, 300, 5604), "Stored old P cannot clear unavailable");
        state.event(true, 4, 302);
        check(!parked(state, 5605), "Availability event does not count as read confirmation");
        state.confirm(state.revision, 4, 302, 5606);
        state.event(false, 4, 100);
        check(!parked(state, 5607), "Even an old malformed/unavailable event fails closed");

        state.confirm(state.revision, 4, 302, 6000);
        long beforeFailure = state.revision;
        state.fail();
        check(!parked(state, 6001), "Read timeout/error clears cached P immediately");
        check(!state.confirm(beforeFailure, 4, 303, 6002), "Late response after failure is rejected");
        state.event(true, 4, 304);
        check(!parked(state, 6003), "P notification after timeout cannot unlock");
        check(!state.confirm(state.revision, 4, -1, 6004), "Negative source timestamp is rejected");
        check(state.confirm(state.revision, 4, 304, 6005), "New valid read recovers after transient failure");
        long beforeReconnect = state.revision;
        state.reset();
        check(!parked(state, 6006), "Disconnect clears all permission");
        check(!state.confirm(beforeReconnect, 4, 305, 6007), "Response from previous connection cannot unlock");
        check(state.confirm(state.revision, 4, 10, 6008), "New connection starts with its own timestamp baseline");
        check(parked(state, 6009), "Fresh new-connection P can unlock");
        check(!SafetyPolicy.mayUseParkedContent(true, false, false, 0, state.available(6009), true),
            "Fresh gear does not bypass connection requirement");
        check(!SafetyPolicy.mayUseParkedContent(true, true, true, 0, state.available(6009), true),
            "Fresh gear does not bypass CarUx requirement");
        System.out.println("Gear revalidation tests passed: " + assertions + " assertions.");
    }
}
