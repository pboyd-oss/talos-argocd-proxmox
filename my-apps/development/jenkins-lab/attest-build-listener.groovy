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

DEFAULT_COVERAGE_THRESHOLD = 70

RunListener.all().add(new RunListener<Run>() {
    @Override
    void onCompleted(Run run, TaskListener listener) {
        def fullName = run.parent.fullName
        listener.logger.println("[Platform:debug] onCompleted fired for ${fullName} #${run.number} result=${run.result}")

        if (!fullName.startsWith('teams/')) {
            listener.logger.println("[Platform:debug] skipping -- not in teams/ folder")
            return
        }
        if (run.result != Result.SUCCESS) {
            listener.logger.println("[Platform:debug] skipping -- result is ${run.result}")
            return
        }

        def log    = { String msg -> listener.logger.println("[Platform] ${msg}") }
        def refuse = { String reason ->
            log("Attestation REFUSED for ${fullName} #${run.number} -- ${reason}")
        }

        log("Checking attestation eligibility for ${fullName} #${run.number}")

        // Standard 1: JUnit tests ran and passed
        def testAction = run.getAction(TestResultAction)
        log("JUnit action present: ${testAction != null}")
        if (!testAction) {
            refuse('no JUnit test results recorded')
            return
        }
        if (testAction.failCount > 0) {
            refuse("${testAction.failCount} test failure(s)")
            return
        }
        log("JUnit: ${testAction.totalCount} tests, ${testAction.failCount} failures")

        // Standard 2: JaCoCo coverage recorded and above threshold
        def coverageAction = run.getAction(JacocoBuildAction)
        log("JaCoCo action present: ${coverageAction != null}")
        if (!coverageAction) {
            refuse('no JaCoCo coverage report recorded')
            return
        }
        def threshold    = resolveCoverageThreshold(run)
        def lineCoverage = coverageAction.lineCoverage?.getPercentageFloat() ?: 0.0f
        log("Coverage: ${lineCoverage.round(1)}% (threshold: ${threshold}%)")
        if (lineCoverage < threshold) {
            refuse("line coverage ${lineCoverage.round(1)}% is below threshold ${threshold}%")
            return
        }

        // Standard 3: artifacts.json was archived by the build
        def hasArtifacts = run.getArtifacts().any { it.fileName == 'artifacts.json' }
        log("artifacts.json present: ${hasArtifacts}")
        if (!hasArtifacts) {
            refuse('artifacts.json was not archived -- build may not have produced an image')
            return
        }

        // Standard 4: build was triggered by SCM, not manually bypassed
        def causes = run.getCauses()
        log("Build causes: ${causes.collect { it.class.simpleName }.join(', ')}")
        def scmTriggered = causes.any { cause ->
            cause.class.simpleName.contains('SCM') ||
            cause.class.simpleName.contains('Branch') ||
            cause.class.simpleName.contains('GitLab') ||
            cause.class.simpleName.contains('Gitea') ||
            cause.class.name.contains('scm')
        }
        log("SCM triggered: ${scmTriggered}")
        if (!scmTriggered) {
            refuse('build was not triggered by SCM -- manual builds are not eligible for attestation')
            return
        }

        // Standard 5: platform/{team}/scan completed successfully for THIS exact build.
        def teamSlug   = fullName.split('/')[1]
        log("Looking for successful scan: platform/${teamSlug}/scan upstream=${fullName} build=${run.number}")
        def scanResult = findSuccessfulScan(teamSlug, fullName, run.number.toString())
        log("Scan result found: ${scanResult != null}${scanResult ? ' (#' + scanResult.number + ')' : ''}")
        if (!scanResult) {
            refuse("no successful platform/${teamSlug}/scan found for this build -- scan may not have run or may have failed")
            return
        }
        log("Scan verified: platform/${teamSlug}/scan #${scanResult.number} passed")

        // All standards met -- schedule attestation
        def attestJob = Jenkins.get().getItemByFullName("platform/${teamSlug}/attest")
        if (!attestJob) {
            log("WARNING: no attestation job at platform/${teamSlug}/attest")
            return
        }

        def stages     = extractStages(run)
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

        log("Attestation scheduled for ${fullName} #${run.number} (tests: ${testAction.totalCount}, coverage: ${lineCoverage.round(1)}%)")
    }

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

    private List extractStages(Run run) {
        if (!(run instanceof WorkflowRun)) return []
        def exec = run.execution
        if (!exec) return []
        def stages  = []
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
