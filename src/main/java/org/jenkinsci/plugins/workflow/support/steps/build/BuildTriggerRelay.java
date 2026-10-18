package org.jenkinsci.plugins.workflow.support.steps.build;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.Run;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;

/**
 * Relays the events of a downstream build to the {@code build} step waiting for it when the two run on different
 * Jenkins controllers (such as controllers sharing their queue and builds).
 *
 * <p>When the step schedules the downstream build it asks for a {@linkplain #returnAddress return address}, which
 * travels with the queue item. The controller that runs the downstream build hands each event to {@link #relay}; the
 * implementation brings it to the controller running the upstream build, which completes the step with {@link
 * BuildTriggerStepExecution#deliver}.
 *
 * <p>With no relay installed, or a null return address, the step works as it always did: on this controller only.
 */
@Restricted(Beta.class)
public interface BuildTriggerRelay extends ExtensionPoint {

    /**
     * Called when the step schedules a downstream build that it waits for.
     *
     * @param upstream the build running the step
     * @param nodeId the step's flow node
     * @return an opaque address to reach this step from wherever the downstream build runs, or null for this
     *     controller only
     */
    @CheckForNull
    String returnAddress(@NonNull Run<?, ?> upstream, @NonNull String nodeId);

    /**
     * Called on the controller of the downstream build, for a step that gave a return address.
     *
     * @param returnAddress as returned by {@link #returnAddress}
     * @param waitForStart whether the step waits only for the downstream build to start
     * @param downstream the downstream build
     * @param event what happened to it
     * @return true if the event was relayed to another controller, so nothing is to be done with it here; false if
     *     the upstream build runs here
     */
    boolean relay(
            @NonNull String returnAddress, boolean waitForStart, @NonNull Run<?, ?> downstream, @NonNull Event event);

    /**
     * Whether a queue item left this controller because another one took it (it is then not cancelled, and its
     * outcome is relayed from there).
     */
    default boolean isTakenElsewhere(@NonNull Queue.LeftItem item) {
        return false;
    }

    /**
     * Asks for a downstream build that does not run on this controller to be stopped.
     *
     * @param by who asks, for the build's log
     * @return false if no such build is known to run elsewhere
     */
    default boolean requestStop(@NonNull Job<?, ?> job, int number, @NonNull String by) {
        return false;
    }

    /**
     * Whether a queue item must start on the controller that scheduled it. See {@link #waitsForStart}.
     */
    default boolean pinsToOrigin(@NonNull Queue.Item item) {
        return false;
    }

    /** Events of a downstream build. */
    @Restricted(Beta.class)
    enum Event {
        STARTED,
        COMPLETED,
        DELETED
    }

    /**
     * Whether a {@code build} step waits for this queue item to start only ({@code waitForStart: true}): the step then
     * returns the downstream build, which can only be done where the build is loaded.
     */
    static boolean waitsForStart(@NonNull Queue.Item item) {
        for (BuildTriggerAction.Trigger trigger : BuildTriggerAction.triggersFor(item)) {
            if (trigger.waitForStart) {
                return true;
            }
        }
        return false;
    }

    /** The relay in use: the first one registered, or null. */
    @CheckForNull
    static BuildTriggerRelay get() {
        ExtensionList<BuildTriggerRelay> all = ExtensionList.lookup(BuildTriggerRelay.class);
        return all.isEmpty() ? null : all.get(0);
    }
}
