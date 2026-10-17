package org.jenkinsci.plugins.workflow.support.steps.build;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.Util;
import hudson.console.ModelHyperlinkNote;
import hudson.model.Action;
import hudson.model.CauseAction;
import hudson.model.ChoiceParameterDefinition;
import hudson.model.Computer;
import hudson.model.Describable;
import hudson.model.Executor;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.SimpleParameterDefinition;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.model.TaskListener;
import hudson.model.queue.ScheduleResult;
import hudson.model.queue.Tasks;
import hudson.security.AccessControlled;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;
import org.springframework.security.core.Authentication;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;
import hudson.security.ACL;
import hudson.security.ACLContext;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.actions.WarningAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.StepContext;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class BuildTriggerStepExecution extends AbstractStepExecutionImpl {

    private static final Logger LOGGER = Logger.getLogger(BuildTriggerStepExecution.class.getName());
    private static final Set<String> CHOICE_PARAMETER_DEFINITION_LIKE_CLASSES = ImmutableSet.of(
            "jp.ikedam.jenkins.plugins.extensible_choice_parameter.ExtensibleChoiceParameterDefinition",
            // The names are misleading, but these classes are all parameter definitions, not parameters.
            "org.biouno.unochoice.CascadeChoiceParameter",
            "org.biouno.unochoice.ChoiceParameter",
            "org.biouno.unochoice.DynamicReferenceParameter");

    private final transient BuildTriggerStep step;

    /** As {@link BuildTriggerStep#isPropagate}, for an outcome {@linkplain #deliver delivered} after a restart. */
    private final boolean propagate;

    public BuildTriggerStepExecution(BuildTriggerStep step, @NonNull StepContext context) {
        super(context);
        this.step = step;
        this.propagate = step.isPropagate();
    }

    @SuppressWarnings({"unchecked", "rawtypes"}) // cannot get from ParameterizedJob back to ParameterizedJobMixIn trivially
    @Override
    public boolean start() throws Exception {
        String job = step.getJob();
        Run<?, ?> upstream = getContext().get(Run.class);
        Item item = Jenkins.get().getItem(job, upstream.getParent(), Item.class);
        if (item == null) {
            throw new AbortException("No item named " + job + " found");
        }
        item.checkPermission(Item.BUILD);
        if ((step.getWait() || step.getWaitForStart()) && !(item instanceof Job)) {
            // TODO find some way of allowing ComputedFolders to hook into the listener code
            throw new AbortException("Waiting for non-job items is not supported");
        }

        FlowNode node = getContext().get(FlowNode.class);
        DownstreamBuildAction.getOrCreate(upstream, node.getId(), item);

        List<Action> actions = new ArrayList<>();
        actions.add(new CauseAction(new BuildUpstreamCause(getContext().get(FlowNode.class), upstream)));
        actions.add(new BuildUpstreamNodeAction(node, upstream));

        if (item instanceof ParameterizedJobMixIn.ParameterizedJob) {
            final ParameterizedJobMixIn.ParameterizedJob project = (ParameterizedJobMixIn.ParameterizedJob) item;
            getContext().get(TaskListener.class).getLogger().println("Scheduling project: " + ModelHyperlinkNote.encodeTo(project));

            if (!step.getWait() || step.getWaitForStart()) {
                node.addAction(new LabelAction(Messages.BuildTriggerStepExecution_scheduling(project.getFullDisplayName())));
            } else {
                node.addAction(new LabelAction(Messages.BuildTriggerStepExecution_building_(project.getFullDisplayName())));
            }

            if (step.getWait() || step.getWaitForStart()) {
                StepContext context = getContext();
                actions.add(new BuildTriggerAction(context, step.isPropagate(), step.getWaitForStart(), returnAddress(upstream, node)));
                LOGGER.log(Level.FINER, "scheduling a build of {0} from {1}", new Object[]{project, context});
            }

            List<ParameterValue> parameters = step.getParameters();
            if (parameters != null) {
                parameters = completeDefaultParameters(parameters, (Job) project);
                actions.add(new ParametersAction(parameters));
            }
            int quietPeriod = step.getQuietPeriod() != null ? step.getQuietPeriod() : -1;
            Queue.Item queueItem =
                    ParameterizedJobMixIn.scheduleBuild2(
                            (Job<?, ?>) project, quietPeriod, actions.toArray(new Action[0]));
            if (queueItem == null || queueItem.getFuture() == null) {
                throw new AbortException("Failed to trigger build of " + project.getFullName());
            }
        } else if (item instanceof Queue.Task){
            if (step.getParameters() != null && !step.getParameters().isEmpty()) {
                throw new AbortException("Item type does not support parameters");
            }
            Queue.Task task = (Queue.Task) item;
            getContext().get(TaskListener.class).getLogger().println("Scheduling item: " + ModelHyperlinkNote.encodeTo(item));
            if (!step.getWait() || step.getWaitForStart()) {
                node.addAction(new LabelAction(Messages.BuildTriggerStepExecution_scheduling(task.getFullDisplayName())));
            } else {
                node.addAction(new LabelAction(Messages.BuildTriggerStepExecution_building_(task.getFullDisplayName())));    
            }

            if (step.getWait() || step.getWaitForStart()) {
                StepContext context = getContext();
                actions.add(new BuildTriggerAction(context, step.isPropagate(), step.getWaitForStart(), returnAddress(upstream, node)));
                LOGGER.log(Level.FINER, "scheduling a build of {0} from {1}", new Object[]{task, context});
            }

            Integer quietPeriod = step.getQuietPeriod();
            if (quietPeriod == null) {
                try {
                    Method getQuietPeriod = task.getClass().getMethod("getQuietPeriod");
                    if (getQuietPeriod.getReturnType().equals(int.class)) {
                        quietPeriod = (Integer) getQuietPeriod.invoke(task);
                    }
                } catch (NoSuchMethodException e) {
                    // ignore, best effort only
                } catch (IllegalAccessError | IllegalArgumentException | InvocationTargetException e) {
                    LOGGER.log(Level.WARNING, "Could not determine quiet period of " + item.getFullName(), e);
                }
            }
            if (quietPeriod == null) {
                quietPeriod = Jenkins.get().getQuietPeriod();
            }
            ScheduleResult scheduleResult = Jenkins.get().getQueue().schedule2(task, quietPeriod,actions);
            if (scheduleResult.isRefused()) {
                throw new AbortException("Failed to trigger build of " + item.getFullName());
            }
        } else {
            throw new AbortException("The item named " + job + " is a "
                    + (item instanceof Describable
                    ? ((Describable) item).getDescriptor().getDisplayName()
                    : item.getClass().getName())
                    + " which is not something that can be built");
        }
        if (step.getWait() || step.getWaitForStart()) {
            return false;
        } else {
            getContext().onSuccess(null);
            return true;
        }
    }

    private List<ParameterValue> completeDefaultParameters(List<ParameterValue> parameters, Job<?,?> project) throws IOException, InterruptedException {
        Map<String,ParameterValue> allParameters = new LinkedHashMap<>();
        for (ParameterValue pv : parameters) {
            allParameters.put(pv.getName(), pv);
        }
        if (project != null) {
            ParametersDefinitionProperty pdp = project.getProperty(ParametersDefinitionProperty.class);
            if (pdp != null) {
                for (ParameterDefinition pDef : pdp.getParameterDefinitions()) {
                    if (!allParameters.containsKey(pDef.getName())) {
                        ParameterValue defaultP = pDef.getDefaultParameterValue();
                        if (defaultP != null) {
                            allParameters.put(defaultP.getName(), defaultP);
                        }
                    } else {
                        String description = Util.fixNull(pDef.getDescription());
                        if (pDef instanceof SimpleParameterDefinition && !(pDef instanceof StringParameterDefinition) && !(pDef instanceof ChoiceParameterDefinition)) {
                            // c.f. https://github.com/jenkinsci/parameterized-trigger-plugin/blob/633587c4b0ae027175c738b3a2f46554a672f330/src/main/java/hudson/plugins/parameterizedtrigger/ProjectSpecificParameterValuesActionTransform.java
                            ParameterValue pv = allParameters.get(pDef.getName());
                            if (pv instanceof StringParameterValue) {
                                String pDefDisplayName = pDef.getDescriptor().getDisplayName();
                                // For classes with semantics similar to ChoiceParameterDefinition, a type mismatch for
                                // the parameter versus the definition is expected, so we want to do the conversion, but
                                // not log a warning.
                                if (!CHOICE_PARAMETER_DEFINITION_LIKE_CLASSES.contains(pDef.getClass().getName())) {
                                    getContext().get(TaskListener.class).getLogger().printf("The parameter '%s' did not have the type expected by %s. Converting to %s.%n", pv.getName(), ModelHyperlinkNote.encodeTo(project), pDefDisplayName);
                                    description = Messages.BuildTriggerStepExecution_convertedParameterDescription(description, pDefDisplayName, getContext().get(Run.class).toString());
                                }
                                ParameterValue convertedValue = ((SimpleParameterDefinition) pDef).createValue((String) pv.getValue());
                                allParameters.put(pDef.getName(), convertedValue);
                            }
                        }
                        ParameterValue pv = allParameters.get(pDef.getName());
                        if (!pDef.isValid(pv)) {
                            throw new AbortException("Invalid parameter value: " + pv);
                        }

                        // TODO: Should we try to detect some unconvertible cases and fail here instead of allowing it?
                        // For example, someone passing BooleanParameterValue for a PasswordParameterDefinition?

                        // Get the description of specified parameters here. UI submission of parameters uses formatted description.
                        allParameters.get(pDef.getName()).setDescription(description);
                    }
                }
            }
        }
        return Lists.newArrayList(allParameters.values());
    }

    @Override
    public void stop(@NonNull Throwable cause) throws Exception {
        StepContext context = getContext();
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            context.onFailure(cause);
            return;
        }

        boolean interrupted = false;

        Queue q = jenkins.getQueue();
        // if the build is still in the queue, abort it.
        // BuildQueueListener will report the failure, so this method shouldn't call getContext().onFailure()
        for (Queue.Item i : q.getItems()) {
            for (BuildTriggerAction.Trigger trigger : BuildTriggerAction.triggersFor(i)) {
                if (trigger.context.equals(context) && cancelPermitted(i.task, i.task.getFullDisplayName(), context)) {
                    // Note that it is a little questionable to cancel the queue item in case it has other causes,
                    // but in the common case that this is the only cause, it is most intuitive to do so.
                    // The same applies to aborting the actual build once started.
                    q.cancel(i);
                    interrupted = true;
                }
            }
        }

        // if there's any in-progress build already, abort that.
        // when the build is actually aborted, BuildTriggerListener will take notice and report the failure,
        // so this method shouldn't call getContext().onFailure()
        for (Computer c : jenkins.getComputers()) {
            for (Executor e : c.getExecutors()) {
                interrupted |= maybeInterrupt(e, cause, context);
            }
            for (Executor e : c.getOneOffExecutors()) {
                interrupted |= maybeInterrupt(e, cause, context);
            }
        }

        // a downstream build running on another controller is stopped there; its outcome is relayed back
        BuildTriggerRelay relay = BuildTriggerRelay.get();
        Run<?, ?> upstream = context.get(Run.class);
        FlowNode node = context.get(FlowNode.class);
        DownstreamBuildAction downstreams = relay == null || upstream == null ? null : upstream.getAction(DownstreamBuildAction.class);
        if (downstreams != null && node != null) {
            for (DownstreamBuildAction.DownstreamBuild db : downstreams.getDownstreamBuilds()) {
                Integer number = db.getBuildNumber();
                if (number == null || !db.getFlowNodeId().equals(node.getId())) {
                    continue;
                }
                Job<?, ?> job = jenkins.getItemByFullName(db.getJobFullName(), Job.class);
                Run<?, ?> local = job == null ? null : job.getBuildByNumber(number);
                if (job != null && (local == null || !local.isBuilding())
                        && cancelPermitted(job, job.getFullDisplayName(), context)
                        && relay.requestStop(job, number, "the build step of " + upstream.getFullDisplayName())) {
                    interrupted = true;
                }
            }
        }

        if (!interrupted) {
            super.stop(cause);
        }
    }

    private static @CheckForNull String returnAddress(Run<?, ?> upstream, FlowNode node) {
        BuildTriggerRelay relay = BuildTriggerRelay.get();
        return relay == null ? null : relay.returnAddress(upstream, node.getId());
    }

    /**
     * Completes a {@code build} step with an event of its downstream build relayed from another controller
     * ({@link BuildTriggerRelay}), as {@link BuildTriggerListener} does when both builds run here. Delivering an
     * event again is harmless.
     *
     * @param upstream the build running the step, on this controller
     * @param nodeId the step's flow node
     * @param event what happened to the downstream build
     * @param downstreamExternalizableId the downstream build's {@link Run#getExternalizableId}
     * @param result the downstream build's result, for {@link BuildTriggerRelay.Event#COMPLETED}
     * @param waitForStart whether the step waits only for the downstream build to start
     * @return true if handled (the step completed, or is no longer waiting); false if not possible yet, so the caller
     *     is to deliver it again later: the upstream build is not resumed yet, or the downstream build of a successful
     *     outcome is not loaded here yet
     */
    @Restricted(Beta.class)
    public static boolean deliver(@NonNull Run<?, ?> upstream, @NonNull String nodeId, @NonNull BuildTriggerRelay.Event event,
            @NonNull String downstreamExternalizableId, @CheckForNull Result result, boolean waitForStart)
            throws IOException, InterruptedException {
        if (!(upstream instanceof FlowExecutionOwner.Executable executable)) {
            return true; // nothing to complete
        }
        FlowExecutionOwner owner = executable.asFlowExecutionOwner();
        FlowExecution execution = owner == null ? null : owner.getOrNull();
        if (execution == null) {
            return false; // not resumed yet
        }
        if (execution.isComplete()) {
            return true;
        }
        BuildTriggerStepExecution step = null;
        try {
            for (StepExecution se : execution.getCurrentExecutions(true).get(10, TimeUnit.SECONDS)) {
                if (se instanceof BuildTriggerStepExecution bt && nodeId.equals(bt.getContext().get(FlowNode.class).getId())) {
                    step = bt;
                }
            }
        } catch (ExecutionException | TimeoutException x) {
            LOGGER.log(Level.FINE, "Could not list the steps of " + upstream, x);
            return false;
        }
        if (step == null) {
            return true; // completed already (delivered twice), or stopped
        }
        StepContext context = step.getContext();
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            int hash = downstreamExternalizableId.lastIndexOf('#');
            String jobName = hash < 0 ? downstreamExternalizableId : downstreamExternalizableId.substring(0, hash);
            int number = hash < 0 ? 0 : Integer.parseInt(downstreamExternalizableId.substring(hash + 1));
            Job<?, ?> job = Jenkins.get().getItemByFullName(jobName, Job.class);
            Run<?, ?> downstream = job == null ? null : job.getBuildByNumber(number);
            String link = ModelHyperlinkNote.encodeTo("/" + (job == null ? "" : job.getUrl() + number + "/"), jobName + " #" + number);
            TaskListener listener = context.get(TaskListener.class);
            switch (event) {
                case STARTED -> {
                    listener.getLogger().println("Starting building: " + link);
                    if (job != null) {
                        DownstreamBuildAction.getOrCreate(upstream, nodeId, job).setBuildNumber(number);
                        upstream.save();
                    }
                    if (waitForStart) {
                        if (downstream != null) {
                            context.onSuccess(new RunWrapper(downstream, false));
                        } else {
                            context.onFailure(new AbortException(jobName + " #" + number
                                    + " started on another controller, so it cannot be returned to waitForStart here"));
                        }
                    }
                }
                case COMPLETED -> {
                    if (waitForStart) {
                        return true; // completed when it started
                    }
                    Result r = result == null ? Result.FAILURE : result;
                    if (step.propagate && r != Result.SUCCESS) {
                        listener.getLogger().println("Build " + link + " completed: " + r);
                        context.get(FlowNode.class).addOrReplaceAction(new WarningAction(r));
                        context.onFailure(new FlowInterruptedException(r, false, downstream != null
                                ? new DownstreamFailureCause(downstream)
                                : new DownstreamFailureCause(jobName, number, job == null ? "" : job.getUrl() + number + "/", r)));
                    } else if (downstream == null) {
                        return false; // returned once its record is here
                    } else {
                        listener.getLogger().println("Build " + link + " completed: " + r);
                        context.onSuccess(new RunWrapper(downstream, false));
                    }
                }
                case DELETED -> context.onFailure(new AbortException(jobName + " #" + number + " was deleted"));
                default -> throw new IllegalArgumentException(event.toString());
            }
        }
        return true;
    }
    private static boolean maybeInterrupt(Executor e, Throwable cause, StepContext context) {
        boolean interrupted = false;
        Queue.Executable exec = e.getCurrentExecutable();
        if (exec instanceof Run) {
            for (BuildTriggerAction.Trigger trigger : BuildTriggerAction.triggersFor((Run<?, ?>) exec)) {
                if (trigger.context.equals(context) && cancelPermitted(((Run<?, ?>) exec).getParent(), ((Run<?, ?>) exec).getParent().getFullDisplayName(), context)) {
                    e.interrupt(Result.ABORTED, new BuildTriggerCancelledCause(cause));
                    trigger.interruption = cause;
                    try {
                        ((Run<?, ?>) exec).save();
                    } catch (IOException x) {
                        LOGGER.log(Level.WARNING, "failed to save interrupt cause on " + exec, x);
                    }
                    interrupted = true;
                }
            }
        }
        return interrupted;
    }

    /**
     *  only propagate the abort to the downstream if the identity the upstream build runs as has
     * {@link Item#CANCEL} on it otherwise leave the downstream running and log a message on the upstream build
     */
    static boolean cancelPermitted(Object downstream, String downstreamName, StepContext context) {
        if (!(downstream instanceof AccessControlled ac)) {
            return true;
        }
        Authentication auth;
        try {
            if (!(context.get(Run.class).getParent() instanceof Queue.Task task)) {
                return true;
            }
            auth = Tasks.getAuthenticationOf2(task);
        } catch (IOException | InterruptedException x) {
            LOGGER.log(Level.WARNING, "failed to determine upstream authentication", x);
            return true;
        }
        if (ac.hasPermission2(auth, Item.CANCEL)) {
            return true;
        }
        try {
            context.get(TaskListener.class).getLogger().println(auth.getName()
                    + " is missing the " + Item.CANCEL.group.title + "/" + Item.CANCEL.name
                    + " permission to abort " + downstreamName);
        } catch (IOException | InterruptedException x) {
            LOGGER.log(Level.WARNING, "failed to report missing cancel permission", x);
        }
        return false;
    }


    @Override public String getStatus() {
        for (Queue.Item i : Queue.getInstance().getItems()) {
            for (BuildTriggerAction.Trigger trigger : BuildTriggerAction.triggersFor(i)) {
                if (trigger.context.equals(getContext())) {
                    return "waiting to schedule " + i.task.getFullDisplayName() + "; blocked: " + i.getWhy();
                }
            }
        }
        for (Computer c : Jenkins.get().getComputers()) {
            for (Executor e : c.getExecutors()) {
                String r = running(e);
                if (r != null) {
                    return r;
                }
            }
            for (Executor e : c.getOneOffExecutors()) {
                String r = running(e);
                if (r != null) {
                    return r;
                }
            }
        }
        // TODO QueueTaskFuture does not allow us to record the queue item ID
        return "unsure what happened to downstream build";
    }
    private @CheckForNull String running(@NonNull Executor e) {
        Queue.Executable exec = e.getCurrentExecutable();
        if (exec instanceof Run) {
            Run<?,?> run = (Run<?, ?>) exec;
            for (BuildTriggerAction.Trigger trigger : BuildTriggerAction.triggersFor(run)) {
                if (trigger.context.equals(getContext())) {
                    return "running " + run;
                }
            }
        }
        return null;
    }

    private static final long serialVersionUID = 1L;

}
