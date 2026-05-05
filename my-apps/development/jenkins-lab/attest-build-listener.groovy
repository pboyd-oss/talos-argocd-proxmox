import groovy.json.JsonOutput
import hudson.model.ParametersAction
import hudson.model.Result
import hudson.model.Run
import hudson.model.StringParameterValue
import hudson.model.TaskListener
import hudson.model.listeners.RunListener
import hudson.tasks.junit.TestResultAction
import jenkins.model.Jenkins
import hudson.plugins.jacoco.JacocoBuildAction
import org.jenkinsci.plugins.workflow.actions.ErrorAction
import org.jenkinsci.plugins.workflow.actions.LabelAction
import org.jenkinsci.plugins.workflow.cps.nodes.StepStartNode
import org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner
import org.jenkinsci.plugins.workflow.job.WorkflowRun
import org.jenkinsci.plugins.workflow.libs.LibrariesAction

// Fires after every build in a teams/ folder.
// Acts as a platform standards gate — all checks read Jenkins' internal build records,
// which the team pipeline cannot forge from the workspace.
//
// Standards enforced:
//   1. Build result SUCCESS
//   2. JUnit tests ran and all passed
//   3. JaCoCo coverage recorded and above threshold
//   4. artifacts.json was archived
//   5. Build was triggered by SCM (not manually bypassed)
//   6. platform/{team}/scan ran successfully for this exact build AND was triggered
//      by the team pipeline (UpstreamCause check — manually triggered scans are rejected)
//
// Only if all standards pass does it schedule platform/{team}/attest.
// Teams cannot trigger attestation themselves — this is the only entry point.

// Coverage threshold — percentage (0–100). Override per-team via TUXGRID_COVERAGE_THRESHOLD
// folder property or Jenkins system property 'platform.coverage.threshold'.
private static final int DEFAULT_COVERAGE_THRESHOLD = 70

RunListener.all().add(new RunListener<Run>() {
    @Override
    void onCompleted(Run run, TaskListener listener) {
        def fullName = run.parent.fullName
        if (!fullName.startsWith('teams/')) return
        if (run.result != Result.SUCCESS) return

        def log    = { String msg -> listener.logger.println("[Platform] ${msg}") }
        def refuse = { String reason ->
            log("Attestation REFUSED for ${fullName} #${run.number} — ${reason}")
        }

        // Standard 1: JUnit tests ran and passed
        def testAction = run.getAction(TestResultAction)
        if (!testAction) {
            refuse('no JUnit test results recorded')
            return
        }
        if (testAction.failCount > 0) {
            refuse("${testAction.failCount} test failure(s)")
            return
        }

        // Standard 2: JaCoCo coverage recorded and above threshold
        def coverageAction = run.getAction(JacocoBuildAction)
        if (!coverageAction) {
            refuse('no JaCoCo coverage report recorded')
            return
        }
        def threshold   = resolveCoverageThreshold(run)
        def lineCoverage = coverageAction.lineCoverage?.getPercentageFloat() ?: 0.0f
        if (lineCoverage < threshold) {
            refuse("line coverage ${lineCoverage.round(1)}% is below threshold ${threshold}%")
            return
        }

        // Standard 3: artifacts.json was archived by the build
        if (!run.getArtifacts().any { it.fileName == 'artifacts.json' }) {
            refuse('artifacts.json was not archived — build may not have produced an image')
            return
        }

        // Standard 4: build was triggered by SCM, not manually bypassed
        def scmTriggered = run.getCauses().any { cause ->
            cause.class.simpleName.contains('SCM') ||
            cause.class.simpleName.contains('Branch') ||
            cause.class.simpleName.contains('GitLab') ||
            cause.class.simpleName.contains('Gitea') ||
            cause.class.name.contains('scm')
        }
        if (!scmTriggered) {
            refuse('build was not triggered by SCM — manual builds are not eligible for attestation')
            return
        }

        // Standard 5: platform/{team}/scan completed successfully for THIS exact build.
        // Prevents a team build from receiving attestation if the scan job was never
        // triggered or was triggered with a different build number (e.g. scan ran on
        // build #41 but we're looking at build #42 that was never scanned).
        def teamSlug = fullName.split('/')[1]
        def scanResult = findSuccessfulScan(teamSlug, fullName, run.number.toString())
        if (!scanResult) {
            refuse("no successful platform/${teamSlug}/scan found for this build — " +
                   "scan may not have run or may have failed")
            return
        }
        log("Scan verified: platform/${teamSlug}/scan #${scanResult.number} passed")

        // All standards met — schedule attestation
        def attestJob = Jenkins.get().getItemByFullName("platform/${teamSlug}/attest")
        if (!attestJob) {
            log("WARNING: no attestation job at platform/${teamSlug}/attest")
            return
        }

        def stages    = extractStages(run)
        def librarySHA = getLibrarySHA(run)

        attestJob.scheduleBuild2(0, new hudson.model.ParametersAction([
            new StringParameterValue('UPSTREAM_JOB',              fullName),
            new StringParameterValue('UPSTREAM_BUILD',            run.number.toString()),
            new StringParameterValue('PLATFORM_TESTS_COUNT',      testAction.totalCount.toString()),
            new StringParameterValue('PLATFORM_TESTS_FAILURES',   testAction.failCount.toString()),
            new StringParameterValue('PLATFORM_COVERAGE_PCT',     lineCoverage.round(2).toString()),
            new StringParameterValue('PLATFORM_COVERAGE_THRESH',  threshold.toString()),
            new StringParameterValue('PLATFORM_SCAN_JOB_REF',     "platform/${teamSlug}/scan#${scanResult.number}"),
            new StringParameterValue('PLATFORM_STAGES_JSON',      JsonOutput.toJson(stages)),
            new StringParameterValue('PLATFORM_LIBRARY_SHA',      librarySHA),
        ]))

        log("Attestation scheduled for ${fullName} #${run.number} " +
            "(tests: ${testAction.totalCount}, coverage: ${lineCoverage.round(1)}%)")
    }

    // Searches the platform scan job's recent builds for one that:
    //   - targeted this exact UPSTREAM_JOB + UPSTREAM_BUILD
    //   - completed with result SUCCESS
    //   - was triggered BY the team pipeline (UpstreamCause), not manually
    // Only looks back through the last 50 builds to avoid scanning the full history.
    //
    // The UpstreamCause check is the spoofing prevention: when microservicePipeline()
    // calls build(job:"platform/.../scan", ...), Jenkins records an UpstreamCause on the
    // scan build pointing to the calling job+build number. A manual trigger has no such
    // cause, so any attempt to craft UPSTREAM_JOB+UPSTREAM_BUILD params by hand will fail.
    private Run findSuccessfulScan(String teamSlug, String upstreamJob, String upstreamBuild) {
        def scanJob = Jenkins.get().getItemByFullName("platform/${teamSlug}/scan")
        if (!scanJob) return null

        return scanJob.builds.take(50).find { b ->
            if (b.result != Result.SUCCESS) return false
            def params = b.getAction(ParametersAction)
            if (!params) return false
            if (params.getParameter('UPSTREAM_JOB')?.value   != upstreamJob)   return false
            if (params.getParameter('UPSTREAM_BUILD')?.value != upstreamBuild) return false
            b.getCauses().any { cause ->
                cause instanceof hudson.model.Cause.UpstreamCause &&
                cause.upstreamProject == upstreamJob &&
                cause.upstreamBuild.toString() == upstreamBuild
            }
        }
    }

    // Returns the list of stage names and statuses from the pipeline execution graph.
    // Uses DepthFirstScanner from workflow-api — no Blue Ocean required.
    // Stage nodes are StepStartNodes whose descriptor functionName is 'stage'.
    private List extractStages(Run run) {
        if (!(run instanceof WorkflowRun)) return []
        def exec = run.execution
        if (!exec) return []
        def stages = []
        def scanner = new DepthFirstScanner()
        scanner.setup(exec.getCurrentHeads())
        scanner.each { node ->
            if (node instanceof StepStartNode &&
                node.descriptor?.functionName == 'stage') {
                def label = node.getAction(LabelAction)
                if (label) {
                    stages << [
                        name:   label.displayName,
                        status: node.getAction(ErrorAction) != null ? 'FAILURE' : 'SUCCESS',
                    ]
                }
            }
        }
        return stages
    }

    // Returns the resolved git SHA of the jenkins-library shared library for this build.
    // LibrariesAction is attached to the WorkflowRun by the Pipeline: Shared Groovy Libraries
    // plugin when the library is loaded — the version field holds the resolved commit SHA.
    private String getLibrarySHA(Run run) {
        def libAction = run.getAction(LibrariesAction)
        if (!libAction) return 'unknown'
        def lib = libAction.libraries.find { it.name == 'jenkins-library' }
        return lib?.version ?: 'unknown'
    }

    private int resolveCoverageThreshold(Run run) {
        def folderProp = run.parent?.parent?.getProperties()
                           ?.find { it.class.simpleName == 'FolderPropertiesProperty' }
        if (folderProp) {
            def prop = folderProp.properties?.find { it.key == 'TUXGRID_COVERAGE_THRESHOLD' }
            if (prop?.value) {
                try { return prop.value.toInteger() } catch (ignored) {}
            }
        }
        def sysProp = System.getProperty('platform.coverage.threshold')
        if (sysProp) {
            try { return sysProp.toInteger() } catch (ignored) {}
        }
        return DEFAULT_COVERAGE_THRESHOLD
    }
})
