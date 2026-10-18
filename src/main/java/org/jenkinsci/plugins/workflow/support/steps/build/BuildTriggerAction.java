package org.jenkinsci.plugins.workflow.support.steps.build;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Action;
import hudson.model.Actionable;
import hudson.model.InvisibleAction;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.queue.FoldableAction;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.plugins.workflow.steps.StepContext;

@SuppressWarnings("SynchronizeOnNonFinalField")
class BuildTriggerAction extends InvisibleAction implements FoldableAction {

    private static final Logger LOGGER = Logger.getLogger(BuildTriggerAction.class.getName());

    @Deprecated
    private StepContext context;

    @Deprecated
    private Boolean propagate;

    /** Record of one upstream build step. */
    static class Trigger {

        final StepContext context;

        final boolean propagate;
        final boolean waitForStart;

        /** Record of cancellation cause passed to {@link BuildTriggerStepExecution#stop}, if any. */
        @CheckForNull
        Throwable interruption;

        /** Where to relay the downstream build's events if it runs on another controller ({@link BuildTriggerRelay}). */
        @CheckForNull
        final String returnAddress;

        Trigger(StepContext context, boolean propagate, boolean waitForStart) {
            this(context, propagate, waitForStart, null);
        }

        Trigger(StepContext context, boolean propagate, boolean waitForStart, @CheckForNull String returnAddress) {
            this.context = context;
            this.propagate = propagate;
            this.waitForStart = waitForStart;
            this.returnAddress = returnAddress;
        }

        /**
         * Relays an event to the step if it runs on another controller.
         *
         * @return true if relayed: there is nothing to do here
         */
        boolean relayed(Run<?, ?> downstream, BuildTriggerRelay.Event event) {
            if (returnAddress == null) {
                return false;
            }
            BuildTriggerRelay relay = BuildTriggerRelay.get();
            if (relay == null) {
                return false;
            }
            try {
                return relay.relay(returnAddress, waitForStart, downstream, event);
            } catch (RuntimeException x) {
                LOGGER.log(Level.WARNING, "Could not relay " + event + " of " + downstream + " to " + returnAddress, x);
                return false;
            }
        }

    }

    private /* final */ List<Trigger> triggers;

    BuildTriggerAction(StepContext context, boolean propagate, boolean waitForStart) {
        this(context, propagate, waitForStart, null);
    }

    BuildTriggerAction(StepContext context, boolean propagate, boolean waitForStart, @CheckForNull String returnAddress) {
        triggers = new ArrayList<>();
        triggers.add(new Trigger(context, propagate, waitForStart, returnAddress));
    }

    private Object readResolve() {
        if (triggers == null) {
            triggers = new ArrayList<>();
            triggers.add(new Trigger(context, propagate != null ? propagate : /* old serialized record */ true, false));
            context = null;
            propagate = null;
        }
        return this;
    }

    static Iterable<Trigger> triggersFor(Actionable actionable) {
        List<Trigger> triggers = new ArrayList<>();
        for (BuildTriggerAction action : actionable.getActions(BuildTriggerAction.class)) {
            synchronized (action.triggers) {
                triggers.addAll(action.triggers);
            }
        }
        return triggers;
    }

    @Override public void foldIntoExisting(Queue.Item item, Queue.Task owner, List<Action> otherActions) {
        // there may be >1 upstream builds (or other unrelated causes) for a single downstream build
        BuildTriggerAction existing = item.getAction(BuildTriggerAction.class);
        if (existing == null) {
            item.addAction(this);
        } else {
            synchronized (existing.triggers) {
                existing.triggers.addAll(triggers);
            }
        }
        LOGGER.log(Level.FINE, "coalescing actions for {0}", item);
    }

}
