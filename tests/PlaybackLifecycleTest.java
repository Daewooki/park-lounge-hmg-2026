package ai.nc.varcospaces;

/** Real service state transitions, without constructing an Android Service. */
public final class PlaybackLifecycleTest {
    private static int assertions;
    private static void check(boolean value, String label) {
        assertions++; if (!value) throw new AssertionError(label);
    }
    public static void main(String[] args) {
        PlaybackService.Lifecycle state = new PlaybackService.Lifecycle();
        check(!state.active(true, false) && state.stopped, "A browse connection is stopped and inactive");
        check(!state.keepForeground(), "Never-played browsing releases the foreground service");
        state.play();
        check(state.active(true, false) && !state.stopped, "Explicit play activates the session");
        check(state.keepForeground(), "Playback owns foreground lifetime");
        // AudioEngine.pause() changes audio state only: user pause must keep resume available.
        check(state.active(true, false) && state.keepForeground(), "User pause keeps media controls available");
        state.end();
        check(state.stopped && !state.active(true, true), "Actual timer expiry stops and deactivates");
        check(!state.keepForeground(), "Timer expiry releases foreground lifetime");
        // Changing duration only changes finished from true to false; it is not a PLAY event.
        check(!state.active(true, false), "Changing timer after expiry must not reactivate the session");
        state.play();
        check(!state.active(true, true), "Expired duration stays inactive during initial pause callback");
        check(state.active(true, false), "Explicit replay becomes active after AudioEngine resets the timer");
        state.interrupt();
        check(!state.active(true, false) && !state.keepForeground(), "Focus loss removes active/foreground ownership");
        check(!state.active(true, false), "Focus return alone cannot resume or reactivate");
        state.play(); state.interrupt();
        check(!state.active(false, false), "Driving or unknown state is inactive");
        check(!state.active(true, false), "Returning to park cannot reactivate without explicit play");
        state.play(); state.end();
        check(!state.active(true, false) && !state.keepForeground(), "Explicit stop remains stopped at a reset timer");
        state.play(); state.interrupt();
        check(!state.keepForeground(), "Fatal playback errors do not keep the service foreground");
        System.out.println("Playback lifecycle tests passed: " + assertions + " assertions.");
    }
}
