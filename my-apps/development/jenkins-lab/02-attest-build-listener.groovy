import groovy.json.JsonOutput
import hudson.model.CauseAction
import hudson.model.Cause
import hudson.model.ParametersAction
import hudson.model.Result
import hudson.model.Run
import hudson.model.StringParameterValue
import hudson.model.TaskListener
import hudson.model.listeners.RunListener
import hudson.plugins.git.util.BuildData
import hudson.tasks.junit.TestResultAction
import jenkins.model.Jenkins
import hudson.plugins.jacoco.JacocoBuildAction
import org.jenkinsci.plugins.workflow.actions.ErrorAction
import org.jenkinsci.plugins.workflow.actions.LabelAction
import org.jenkinsci.plugins.workflow.cps.nodes.StepStartNode
import org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner
import org.jenkinsci.plugins.workflow.job.WorkflowRun
import org.jenkinsci.plugins.workflow.libs.LibrariesAction

private String resolveAuditId(Run run) {
    return run.getAction(ParametersAction)?.getParameter('PLATFORM_AUDIT_ID')?.value
}

DEFAULT_COVERAGE_THRESHOLD = 70

// Idempotent registration — same reason as audit-graph-listener.
final String ATTEST_LISTENER_MARKER = 'PlatformAttestBuildListener-v1'
def _runListeners = Jenkins.instance.getExtensionList(RunListener.class)
_runListeners.removeIf { it.toString() == ATTEST_LISTENER_MARKER }
_runListeners.add(new RunListener<Run>() {

    String toString() { ATTEST_LISTENER_MARKER }

    @Override
    void onCompleted(Run run, TaskListener listener) {
        def fullName = run.parent.fullName
        listener.logger.println("[Platform:debug] onCompleted fired for ${fullName} #${run.number} result=${run.result}")

        if (fullName.startsWith('teams/') && run.result == Result.SUCCESS) {
            handleTeamBuild(run, listener, fullName)
        } else if ((fullName =~ /^platform\/[^\/]+\/scan$/).matches() && run.result == Result.SUCCESS) {
            handleScanCompleted(run, listener, fullName)
        } else {
            listener.logger.println("[Platform:debug] skipping -- not a tracked build")
        }
    }

    // ── Phase 1: team build completed ──────────────────────────────────────
    // Check Standards 1–4. If all pass, look for an existing scan:
    //   found  → schedule attest immediately (scan was called from Jenkinsfile)
    //   absent → trigger scan now; attest fires when scan completes (Phase 2)
    private void handleTeamBuild(Run run, TaskListener listener, String fullName) {
        def log    = { String msg -> listener.logger.println("[Platform] ${msg}") }
        def refuse = { String reason -> log("Attestation REFUSED for ${fullName} #${run.number} -- ${reason}") }

        log("Checking attestation eligibility for ${fullName} #${run.number}")

        // Standard 1: JUnit tests ran and passed
        def testAction = run.getAction(TestResultAction)
        log("JUnit action present: ${testAction != null}")
        if (!testAction)              { refuse('no JUnit test results recorded'); return }
        if (testAction.failCount > 0) { refuse("${testAction.failCount} test failure(s)"); return }
        log("JUnit: ${testAction.totalCount} tests, ${testAction.failCount} failures")

        // Standard 2: JaCoCo coverage recorded and above threshold
        def coverageAction = run.getAction(JacocoBuildAction)
        log("JaCoCo action present: ${coverageAction != null}")
        if (!coverageAction) { refuse('no JaCoCo coverage report recorded'); return }
        def threshold    = resolveCoverageThreshold(run)
        def lineCoverage = coverageAction.lineCoverage?.getPercentageFloat() ?: 0.0f
        log("Coverage: ${lineCoverage.round(1)}% (threshold: ${threshold}%)")
        if (lineCoverage < threshold) { refuse("line coverage ${lineCoverage.round(1)}% is below threshold ${threshold}%"); return }

        // Standard 3: artifacts.json archived by the build
        def hasArtifacts = run.getArtifacts().any { it.fileName == 'artifacts.json' }
        log("artifacts.json present: ${hasArtifacts}")
        if (!hasArtifacts) { refuse('artifacts.json was not archived -- build may not have produced an image'); return }

        // Standard 4: build was triggered by SCM, not manually
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
        if (!scmTriggered) { refuse('build was not triggered by SCM -- manual builds are not eligible for attestation'); return }

        // Audit ID injected by audit-graph-listener
        def auditId = resolveAuditId(run)
        log("Audit ID: ${auditId ?: 'NOT PRESENT -- graph listener may not be active'}")
        if (!auditId) { refuse('no PLATFORM_AUDIT_ID found -- audit-graph-listener may not be loaded'); return }

        // audit-log.json written by the graph listener — check the file directly
        // since run.getArtifacts() may not reflect it yet at onCompleted time
        def auditLogFile = new File(run.artifactsDir, 'audit-log.json')
        log("audit-log.json present: ${auditLogFile.exists()}")
        if (!auditLogFile.exists()) { refuse('audit-log.json was not written -- graph listener may not have flushed'); return }

        def auditLogDigest = java.security.MessageDigest.getInstance('SHA-256')
            .digest(auditLogFile.bytes).encodeHex().toString()
        log("audit-log.json sha256: ${auditLogDigest}")

        def teamSlug = fullName.split('/')[1]
        def repoName = fullName.split('/')[2]

        // Resolve git metadata early — needed for Standard 7 and scan trigger
        def gitData   = run.getAction(BuildData)
        def gitUrl    = gitData?.remoteUrls?.find() ?: ''
        def gitCommit = gitData?.lastBuiltRevision?.sha1String ?: ''

        // Standard 7: a successful source-scan must exist for this exact commit
        if (!gitCommit) { refuse('could not determine GIT_COMMIT from build data'); return }
        log("Looking for successful source-scan: platform/${teamSlug}/${repoName}/source-scan@${gitCommit.take(7)}")
        def sourceScanResult = findSuccessfulSourceScan(teamSlug, repoName, gitCommit)
        log("Source scan found: ${sourceScanResult != null}${sourceScanResult ? ' (#' + sourceScanResult.number + ')' : ''}")
        if (!sourceScanResult) {
            refuse("no successful source-scan found for ${repoName}@${gitCommit.take(7)} — platform/${teamSlug}/${repoName}/source-scan must pass for this commit before attestation")
            return
        }

        // Standard 5: scan — check if already done, otherwise trigger it now
        log("Looking for successful scan: platform/${teamSlug}/scan upstream=${fullName} build=${run.number}")
        def scanResult = findSuccessfulScan(teamSlug, fullName, run.number.toString())
        log("Scan result found: ${scanResult != null}${scanResult ? ' (#' + scanResult.number + ')' : ''}")

        if (scanResult) {
            log("Scan already completed — scheduling attestation immediately")
            scheduleAttest(run, fullName, teamSlug, scanResult, testAction, lineCoverage, threshold, auditId, auditLogDigest, listener)
        } else {
            def scanJob = Jenkins.get().getItemByFullName("platform/${teamSlug}/scan")
            if (!scanJob) { refuse("no scan job found at platform/${teamSlug}/scan"); return }

            scanJob.scheduleBuild2(0,
                new CauseAction(new Cause.UpstreamCause(run)),
                new ParametersAction([
                    new StringParameterValue('UPSTREAM_JOB',   fullName),
                    new StringParameterValue('UPSTREAM_BUILD', run.number.toString()),
                    new StringParameterValue('GIT_URL',        gitUrl),
                    new StringParameterValue('GIT_COMMIT',     gitCommit),
                ])
            )
            log("Scan triggered for ${fullName} #${run.number} (git=${gitCommit.take(7)}) — attest will run once scan completes")
        }
    }

    // ── Phase 2: platform scan completed ───────────────────────────────────
    // Scan finished successfully. Re-verify the upstream team build is still
    // eligible, then schedule attest. This is the completion of the wait.
    private void handleScanCompleted(Run scanRun, TaskListener listener, String fullName) {
        def log = { String msg -> listener.logger.println("[Platform] ${msg}") }
        log("Scan completed: ${fullName} #${scanRun.number} — looking up upstream build for attestation")

        def params = scanRun.getAction(ParametersAction)
        if (!params) { log("No params on scan build — skipping"); return }

        def upstreamJob   = params.getParameter('UPSTREAM_JOB')?.value
        def upstreamBuild = params.getParameter('UPSTREAM_BUILD')?.value
        if (!upstreamJob || !upstreamBuild) { log("Missing UPSTREAM_JOB/UPSTREAM_BUILD params — skipping"); return }
        if (!upstreamJob.startsWith('teams/')) { log("Upstream is not a team build — skipping"); return }

        def teamBuildJob = Jenkins.get().getItemByFullName(upstreamJob)
        def teamBuild    = teamBuildJob?.getBuildByNumber(upstreamBuild.toInteger())
        if (!teamBuild) { log("Upstream build not found: ${upstreamJob} #${upstreamBuild}"); return }
        if (teamBuild.result != Result.SUCCESS) { log("Upstream build result is ${teamBuild.result} — skipping"); return }

        // Re-verify all standards still hold on the team build
        def teamSlug       = upstreamJob.split('/')[1]
        def repoName       = upstreamJob.split('/')[2]
        def testAction     = teamBuild.getAction(TestResultAction)
        def coverageAction = teamBuild.getAction(JacocoBuildAction)
        def hasArtifacts   = teamBuild.getArtifacts().any { it.fileName == 'artifacts.json' }
        def auditLogFile   = new File(teamBuild.artifactsDir, 'audit-log.json')
        def auditId        = resolveAuditId(teamBuild)

        def gitData2     = teamBuild.getAction(BuildData)
        def gitCommit2   = gitData2?.lastBuiltRevision?.sha1String ?: ''
        def sourceScanOk = gitCommit2 ? findSuccessfulSourceScan(teamSlug, repoName, gitCommit2) != null : false

        if (!testAction || testAction.failCount > 0 || !coverageAction || !hasArtifacts || !auditLogFile.exists() || !auditId || !sourceScanOk) {
            log("Upstream build ${upstreamJob} #${upstreamBuild} no longer meets all attestation standards — skipping")
            return
        }

        def auditLogDigest = java.security.MessageDigest.getInstance('SHA-256')
            .digest(auditLogFile.bytes).encodeHex().toString()

        def threshold    = resolveCoverageThreshold(teamBuild)
        def lineCoverage = coverageAction.lineCoverage?.getPercentageFloat() ?: 0.0f

        log("All standards met — scheduling attestation for ${upstreamJob} #${upstreamBuild}")
        scheduleAttest(teamBuild, upstreamJob, teamSlug, scanRun, testAction, lineCoverage, threshold, auditId, auditLogDigest, listener)
    }

    // ── Shared: schedule the attest job ────────────────────────────────────
    private void scheduleAttest(Run teamBuild, String fullName, String teamSlug,
                                Run scanRun, TestResultAction testAction,
                                float lineCoverage, int threshold, String auditId,
                                String auditLogDigest, TaskListener listener) {
        def log = { String msg -> listener.logger.println("[Platform] ${msg}") }

        def attestJob = Jenkins.get().getItemByFullName("platform/${teamSlug}/attest")
        if (!attestJob) { log("WARNING: no attestation job at platform/${teamSlug}/attest"); return }

        def stages      = extractStages(teamBuild)
        def librarySHAs = getLibrarySHAs(teamBuild)
        log("Libraries: ${librarySHAs.collect { "${it.name}@${it.sha}" }.join(', ') ?: 'none'}")

        attestJob.scheduleBuild2(0, new ParametersAction([
            new StringParameterValue('UPSTREAM_JOB',              fullName),
            new StringParameterValue('UPSTREAM_BUILD',            teamBuild.number.toString()),
            new StringParameterValue('PLATFORM_AUDIT_ID',         auditId),
            new StringParameterValue('PLATFORM_AUDIT_LOG_REF',    "${fullName}#${teamBuild.number}/artifact/audit-log.json"),
            new StringParameterValue('PLATFORM_AUDIT_LOG_DIGEST', auditLogDigest),
            new StringParameterValue('PLATFORM_TESTS_COUNT',      testAction.totalCount.toString()),
            new StringParameterValue('PLATFORM_TESTS_FAILURES',   testAction.failCount.toString()),
            new StringParameterValue('PLATFORM_COVERAGE_PCT',     lineCoverage.round(2).toString()),
            new StringParameterValue('PLATFORM_COVERAGE_THRESH',  threshold.toString()),
            new StringParameterValue('PLATFORM_SCAN_JOB_REF',    "platform/${teamSlug}/scan#${scanRun.number}"),
            new StringParameterValue('PLATFORM_STAGES_JSON',      JsonOutput.toJson(stages)),
            new StringParameterValue('PLATFORM_LIBRARIES_JSON',   JsonOutput.toJson(librarySHAs)),
        ]))

        log("Attestation scheduled for ${fullName} #${teamBuild.number} | auditId=${auditId} | tests=${testAction.totalCount} | coverage=${lineCoverage.round(1)}% | scan=platform/${teamSlug}/scan#${scanRun.number}")
    }

    private Run findSuccessfulSourceScan(String teamSlug, String repoName, String gitCommit) {
        def sourceScanJob = Jenkins.get().getItemByFullName("platform/${teamSlug}/${repoName}/source-scan")
        if (!sourceScanJob) return null

        return sourceScanJob.builds.take(50).find { b ->
            if (b.result != Result.SUCCESS) return false
            def params = b.getAction(ParametersAction)
            if (!params) return false
            params.getParameter('GIT_COMMIT')?.value == gitCommit
        }
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
                cause instanceof Cause.UpstreamCause &&
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

    private List getLibrarySHAs(Run run) {
        def libAction = run.getAction(LibrariesAction)
        if (!libAction) return []
        return libAction.libraries.collect { lib ->
            [name: lib.name, sha: lib.version ?: 'unknown']
        }
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
