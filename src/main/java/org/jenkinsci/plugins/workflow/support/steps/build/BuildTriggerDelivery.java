package org.jenkinsci.plugins.workflow.support.steps.build;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.Extension;
import hudson.console.ModelHyperlinkNote;
import hudson.model.Job;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.persistence.PersistenceStores;
import jenkins.persistence.RunningBuilds;
import org.jenkinsci.plugins.workflow.actions.WarningAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.support.steps.build.BuildTriggerAction.Trigger;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * On a cluster of controllers sharing their builds (cloud-native Jenkins), a downstream build may run on another
 * controller than the upstream build waiting for it, and the upstream build may move (be adopted) while it waits.
 * The outcome is then delivered to whichever controller runs the upstream build ({@link RunningBuilds#deliver}), which
 * completes the waiting {@code build} step there, as {@link BuildTriggerListener} does when both run on one controller.
 */
@Restricted(NoExternalUse.class)
@Extension(optional = true)
public final class BuildTriggerDelivery implements RunningBuilds.Delivery {

    private static final Logger LOGGER = Logger.getLogger(BuildTriggerDelivery.class.getName());
    static final String PREFIX = "build-step-";

    /**
     * Delivers an outcome for a trigger whose upstream build does not run here.
     *
     * @return true if delivered (the caller does nothing more for it here)
     */
    static boolean deliverIfElsewhere(@NonNull Trigger trigger, @NonNull Run<?, ?> downstream, @NonNull String kind) {
        if (trigger.upstreamJob == null || trigger.nodeId == null || PersistenceStores.get() == null) {
            return false; // recorded before, or no cluster: as always
        }
        Job<?, ?> upstreamJob = Jenkins.get().getItemByFullName(trigger.upstreamJob, Job.class);
        if (upstreamJob == null) {
            return false;
        }
        Run<?, ?> upstream = upstreamJob.getBuildByNumber(trigger.upstreamNumber);
        if (upstream != null && upstream.isLogUpdated() && RunningBuilds.owns(upstream)) {
            return false; // it runs here
        }
        Properties p = new Properties();
        p.setProperty("kind", kind);
        p.setProperty("job", downstream.getParent().getFullName());
        p.setProperty("number", String.valueOf(downstream.getNumber()));
        p.setProperty("nodeId", trigger.nodeId);
        p.setProperty("propagate", String.valueOf(trigger.propagate));
        Result result = downstream.getResult();
        p.setProperty("result", result == null ? Result.FAILURE.toString() : result.toString());
        if (trigger.interruption != null) {
            p.setProperty("interruption", String.valueOf(trigger.interruption.getMessage()));
        }
        String id = PREFIX + trigger.nodeId + "-" + kind + "-" + downstream.getNumber() + "-"
                + Integer.toHexString(downstream.getParent().getFullName().hashCode());
        try {
            StringWriter w = new StringWriter();
            p.store(w, null);
            RunningBuilds.deliver(upstreamJob, trigger.upstreamNumber, id.replaceAll("[^A-Za-z0-9-]", "_"), w.toString());
            LOGGER.fine(() -> "Delivered " + kind + " of " + downstream + " to " + trigger.upstreamJob + " #" + trigger.upstreamNumber);
            return true;
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not deliver " + kind + " of " + downstream + " to its upstream build", e);
            return false;
        }
    }

    @Override
    public boolean deliver(@NonNull Run<?, ?> run, @NonNull String id, @NonNull String payload) throws Exception {
        if (!id.startsWith(PREFIX)) {
            return false;
        }
        if (!(run instanceof FlowExecutionOwner.Executable executable)) {
            return true; // nothing to complete
        }
        Properties p = new Properties();
        p.load(new StringReader(payload));
        FlowExecution execution = executable.asFlowExecutionOwner().getOrNull();
        if (execution == null) {
            return false; // not resumed yet
        }
        StepContext context = null;
        List<StepExecution> running = execution.getCurrentExecutions(true).get(10, TimeUnit.SECONDS);
        for (StepExecution se : running) {
            if (se instanceof BuildTriggerStepExecution && p.getProperty("nodeId").equals(se.getContext().get(FlowNode.class).getId())) {
                context = se.getContext();
            }
        }
        if (context == null) {
            // done already (e.g. delivered twice), or the step was stopped
            return execution.isComplete() || running.stream().noneMatch(BuildTriggerStepExecution.class::isInstance)
                    || p.getProperty("kind").equals("started");
        }
        String jobName = p.getProperty("job");
        int number = Integer.parseInt(p.getProperty("number"));
        Job<?, ?> job = Jenkins.get().getItemByFullName(jobName, Job.class);
        switch (p.getProperty("kind")) {
            case "started" -> {
                context.get(TaskListener.class).getLogger().println("Starting building: "
                        + ModelHyperlinkNote.encodeTo("/" + (job == null ? "" : job.getUrl() + number + "/"), jobName + " #" + number));
                if (job != null) {
                    DownstreamBuildAction.getOrCreate(run, p.getProperty("nodeId"), job).setBuildNumber(number);
                    run.save();
                }
                return true;
            }
            case "deleted" -> {
                context.onFailure(new AbortException(jobName + " #" + number + " was deleted"));
                return true;
            }
            default -> {
                Run<?, ?> downstream = job == null ? null : job.getBuildByNumber(number);
                if (downstream == null) {
                    return false; // its record is not here yet (it is published once it completes)
                }
                Result result = Result.fromString(p.getProperty("result"));
                boolean propagate = Boolean.parseBoolean(p.getProperty("propagate"));
                context.get(TaskListener.class).getLogger().println("Build "
                        + ModelHyperlinkNote.encodeTo("/" + downstream.getUrl(), downstream.getFullDisplayName()) + " completed: " + result);
                if (propagate && result != Result.SUCCESS) {
                    context.get(FlowNode.class).addOrReplaceAction(new WarningAction(result));
                }
                if (!propagate || result == Result.SUCCESS) {
                    String interruption = p.getProperty("interruption");
                    if (interruption == null) {
                        context.onSuccess(new RunWrapper(downstream, false));
                    } else {
                        context.onFailure(new AbortException(interruption));
                    }
                } else {
                    context.onFailure(new FlowInterruptedException(result, false, new DownstreamFailureCause(downstream)));
                }
                return true;
            }
        }
    }
}
