package org.jenkinsci.plugins.workflow.support.steps.build;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.FreeStyleProject;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.Run;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.BuildWatcherExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * A {@code build} step whose downstream build runs "on another controller": the fake relay takes every event, and the
 * test delivers it back as the other controller would.
 */
@WithJenkins
class BuildTriggerRelayTest {

    @SuppressWarnings("unused")
    @RegisterExtension
    private static final BuildWatcherExtension BUILD_WATCHER = new BuildWatcherExtension();

    private JenkinsRule j;
    private Fake relay;

    @BeforeEach
    void beforeEach(JenkinsRule rule) throws Exception {
        j = rule;
        j.jenkins.setQuietPeriod(0);
        relay = j.jenkins.getExtensionList(BuildTriggerRelay.class).get(Fake.class);
    }

    private Relayed awaitRelayed(BuildTriggerRelay.Event event) {
        await().atMost(Duration.ofSeconds(60)).until(() -> relay.relayed.stream().anyMatch(r -> r.event == event));
        return relay.relayed.stream().filter(r -> r.event == event).findFirst().orElseThrow();
    }

    private static boolean deliver(Relayed r, BuildTriggerRelay.Event event, String downstreamId, Result result) throws Exception {
        String[] address = r.returnAddress.split("\\|");
        return BuildTriggerStepExecution.deliver(Run.fromExternalizableId(address[0]), address[1], event, downstreamId, result,
                r.waitForStart);
    }

    @Test
    void completedElsewhere() throws Exception {
        FreeStyleProject ds = j.createFreeStyleProject("ds");
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("def b = build 'ds'\necho \"ds.result=${b.result} ds.number=${b.number}\"", true));
        WorkflowRun b = us.scheduleBuild2(0).waitForStart();
        Relayed started = awaitRelayed(BuildTriggerRelay.Event.STARTED);
        assertEquals(b.getExternalizableId() + "|" + started.returnAddress.split("\\|")[1], started.returnAddress);
        assertTrue(deliver(started, BuildTriggerRelay.Event.STARTED, "ds#1", null));
        j.waitForMessage("Starting building: ds #1", b);
        assertEquals(1, b.getAction(DownstreamBuildAction.class).getDownstreamBuilds().get(0).getBuildNumber());

        Relayed completed = awaitRelayed(BuildTriggerRelay.Event.COMPLETED);
        j.waitForCompletion(ds.getBuildByNumber(1));
        assertTrue(b.isBuilding(), "the local path did nothing: the event was relayed");
        assertTrue(deliver(completed, BuildTriggerRelay.Event.COMPLETED, "ds#1", Result.SUCCESS));
        j.assertLogContains("ds.result=SUCCESS ds.number=1", j.assertBuildStatusSuccess(j.waitForCompletion(b)));
        assertTrue(deliver(completed, BuildTriggerRelay.Event.COMPLETED, "ds#1", Result.SUCCESS), "again: harmless");
    }

    @Test
    void failureElsewhereIsPropagatedWithoutTheRecord() throws Exception {
        j.createFreeStyleProject("ds").getBuildersList().add(new FailureBuilder());
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("build 'ds'", true));
        WorkflowRun b = us.scheduleBuild2(0).waitForStart();
        Relayed completed = awaitRelayed(BuildTriggerRelay.Event.COMPLETED);
        // a build not loaded here (its record is not here yet): a failure needs only the result
        assertTrue(deliver(completed, BuildTriggerRelay.Event.COMPLETED, "ds#7", Result.FAILURE));
        j.assertBuildStatus(Result.FAILURE, j.waitForCompletion(b));
        j.assertLogContains("Build ds #7 completed: FAILURE", b);
    }

    @Test
    void successElsewhereWaitsForTheRecord() throws Exception {
        j.createFreeStyleProject("ds");
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("echo \"ds.result=${build(job: 'ds', propagate: false).result}\"", true));
        WorkflowRun b = us.scheduleBuild2(0).waitForStart();
        Relayed completed = awaitRelayed(BuildTriggerRelay.Event.COMPLETED);
        assertFalse(deliver(completed, BuildTriggerRelay.Event.COMPLETED, "ds#7", Result.UNSTABLE), "not loadable yet");
        assertTrue(b.isBuilding());
        assertTrue(deliver(completed, BuildTriggerRelay.Event.COMPLETED, "ds#1", Result.UNSTABLE));
        j.assertLogContains("ds.result=SUCCESS", j.assertBuildStatusSuccess(j.waitForCompletion(b)));
    }

    @Test
    void waitForStartElsewhere() throws Exception {
        j.createFreeStyleProject("ds");
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("echo \"started ${build(job: 'ds', waitForStart: true).number}\"", true));
        WorkflowRun b = us.scheduleBuild2(0).waitForStart();
        Relayed started = awaitRelayed(BuildTriggerRelay.Event.STARTED);
        assertTrue(started.waitForStart);
        assertTrue(deliver(started, BuildTriggerRelay.Event.STARTED, "ds#1", null));
        j.assertLogContains("started 1", j.assertBuildStatusSuccess(j.waitForCompletion(b)));
    }

    @Test
    void waitForStartElsewhereFailsClearlyIfTheBuildIsNotHere() throws Exception {
        j.createFreeStyleProject("ds");
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("build(job: 'ds', waitForStart: true)", true));
        WorkflowRun b = us.scheduleBuild2(0).waitForStart();
        Relayed started = awaitRelayed(BuildTriggerRelay.Event.STARTED);
        assertTrue(deliver(started, BuildTriggerRelay.Event.STARTED, "ds#9", null));
        j.assertBuildStatus(Result.FAILURE, j.waitForCompletion(b));
        j.assertLogContains("ds #9 started on another controller, so it cannot be returned to waitForStart here", b);
    }

    @Test
    void deletedElsewhere() throws Exception {
        j.createFreeStyleProject("ds");
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("build 'ds'", true));
        WorkflowRun b = us.scheduleBuild2(0).waitForStart();
        Relayed started = awaitRelayed(BuildTriggerRelay.Event.STARTED);
        assertTrue(deliver(started, BuildTriggerRelay.Event.DELETED, "ds#1", null));
        j.assertBuildStatus(Result.FAILURE, j.waitForCompletion(b));
        j.assertLogContains("ds #1 was deleted", b);
    }

    @Test
    void takenElsewhereIsNotCancelled() throws Exception {
        FreeStyleProject ds = j.createFreeStyleProject("ds");
        ds.setQuietPeriod(9999);
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("build(job: 'ds', waitForStart: true)", true));
        WorkflowRun b = us.scheduleBuild2(0).waitForStart();
        await().atMost(Duration.ofSeconds(60)).until(() -> j.jenkins.getQueue().getItems().length == 1);
        Queue.Item item = j.jenkins.getQueue().getItems()[0];
        assertTrue(BuildTriggerRelay.waitsForStart(item));
        relay.takenElsewhere = true;
        j.jenkins.getQueue().cancel(item);
        await().atMost(Duration.ofSeconds(60)).until(() -> j.jenkins.getQueue().getItems().length == 0);
        assertTrue(b.isBuilding(), "still waiting for the build that started elsewhere");
        // the other controller relays the start of a build that is not loaded here
        Relayed started = new Relayed(relay.returnAddresses.get(0), true, BuildTriggerRelay.Event.STARTED);
        assertTrue(deliver(started, BuildTriggerRelay.Event.STARTED, "ds#1", null));
        j.assertBuildStatus(Result.FAILURE, j.waitForCompletion(b));
    }

    @Test
    void stopRequestedElsewhere() throws Exception {
        FreeStyleProject ds = j.createFreeStyleProject("ds");
        ds.setQuietPeriod(9999);
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("build 'ds'", true));
        WorkflowRun b = us.scheduleBuild2(0).waitForStart();
        await().atMost(Duration.ofSeconds(60)).until(() -> j.jenkins.getQueue().getItems().length == 1);
        relay.takenElsewhere = true;
        j.jenkins.getQueue().cancel(j.jenkins.getQueue().getItems()[0]);
        Relayed started = new Relayed(relay.returnAddresses.get(0), false, BuildTriggerRelay.Event.STARTED);
        assertTrue(deliver(started, BuildTriggerRelay.Event.STARTED, "ds#5", null)); // runs elsewhere, as #5
        b.getExecutor().interrupt();
        await().atMost(Duration.ofSeconds(60)).until(() -> !relay.stops.isEmpty());
        assertThat(relay.stops, contains("ds#5 by the build step of us #1"));
        assertTrue(b.isBuilding(), "waits for the outcome from where it runs");
        assertTrue(deliver(started, BuildTriggerRelay.Event.COMPLETED, "ds#5", Result.ABORTED));
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(b));
    }

    @Test
    void notRelayedWithoutAddress() throws Exception {
        relay.address = false;
        j.createFreeStyleProject("ds");
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("build 'ds'", true));
        j.buildAndAssertSuccess(us);
        assertTrue(relay.relayed.isEmpty());
    }

    record Relayed(String returnAddress, boolean waitForStart, BuildTriggerRelay.Event event) {}

    @TestExtension
    public static class Fake implements BuildTriggerRelay {
        volatile boolean address = true;
        volatile boolean takenElsewhere;
        final List<Relayed> relayed = new CopyOnWriteArrayList<>();
        final List<String> returnAddresses = new CopyOnWriteArrayList<>();
        final List<String> stops = new CopyOnWriteArrayList<>();

        @CheckForNull
        @Override
        public String returnAddress(@NonNull Run<?, ?> upstream, @NonNull String nodeId) {
            if (!address) {
                return null;
            }
            String a = upstream.getExternalizableId() + "|" + nodeId;
            returnAddresses.add(a);
            return a;
        }

        @Override
        public boolean relay(@NonNull String returnAddress, boolean waitForStart, @NonNull Run<?, ?> downstream,
                @NonNull Event event) {
            relayed.add(new Relayed(returnAddress, waitForStart, event));
            return true;
        }

        @Override
        public boolean isTakenElsewhere(@NonNull Queue.LeftItem item) {
            return takenElsewhere;
        }

        @Override
        public boolean requestStop(@NonNull Job<?, ?> job, int number, @NonNull String by) {
            if (job.getBuildByNumber(number) != null) {
                return false;
            }
            stops.add(job.getFullName() + "#" + number + " by " + by);
            return true;
        }
    }
}
