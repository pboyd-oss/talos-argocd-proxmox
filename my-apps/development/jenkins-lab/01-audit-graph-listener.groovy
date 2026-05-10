import groovy.json.JsonOutput
import hudson.model.Run
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.flow.FlowExecution
import org.jenkinsci.plugins.workflow.flow.FlowExecutionListener
import org.jenkinsci.plugins.workflow.flow.GraphListener
import org.jenkinsci.plugins.workflow.graph.FlowEndNode
import org.jenkinsci.plugins.workflow.graph.FlowNode
import org.jenkinsci.plugins.workflow.cps.nodes.StepStartNode
import org.jenkinsci.plugins.workflow.cps.nodes.StepEndNode
import org.jenkinsci.plugins.workflow.actions.ErrorAction
import org.jenkinsci.plugins.workflow.actions.LabelAction
import org.jenkinsci.plugins.workflow.actions.TimingAction
import org.jenkinsci.plugins.workflow.actions.ThreadNameAction
import org.jenkinsci.plugins.workflow.actions.WorkspaceAction
import org.jenkinsci.plugins.workflow.actions.ArgumentsAction
import org.jenkinsci.plugins.workflow.job.WorkflowRun
import org.jenkinsci.plugins.workflow.libs.LibrariesAction

// Audit service endpoint. Overridable via JVM system property for testing.
@groovy.transform.Field
final String AUDIT_SERVICE_URL = System.getProperty(
    'platform.audit.service.url',
    'http://platform-audit-service.platform.svc.cluster.local:8080'
)

// In-memory store keyed by auditId → list of audit events.
// Retained for audit-log.json artifact (required by attestation listener).
// Events are also streamed to the audit service in real time.
@groovy.transform.Field
final Map<String, List<Map>> auditStore = [:].asSynchronized()

// Track step start times keyed by auditId:nodeId for duration calculation.
@groovy.transform.Field
final Map<String, Long> stepStartTimes = [:].asSynchronized()

FlowExecutionListener.all().add(new FlowExecutionListener() {

    @Override
    void onRunning(FlowExecution execution) {
        def run  = runFor(execution)
        if (run == null) return
        if (!run.parent.fullName.startsWith('teams/')) return

        def auditId = generateAuditId(run)
        auditStore[auditId] = [].asSynchronized()

        emitEvent(auditId, [
            event      : 'BUILD_START',
            job        : run.parent.fullName,
            build      : run.number,
            auditId    : auditId,
            triggeredBy: run.getCauses().collect { it.shortDescription },
        ])

        // Inject PLATFORM_AUDIT_ID into the build environment so every
        // spawned process inherits it — Tetragon reads it from /proc/*/environ.
        try {
            def envAction = run.getAction(hudson.model.EnvironmentContributingAction)
            run.addAction(new AuditIdEnvironmentAction(auditId))
        } catch (ignored) {}

        execution.addListener(new GraphListener.Synchronous() {
            @Override
            void onNewHead(FlowNode node) {
                handleNode(auditId, node)
            }
        })
    }

    @Override
    void onCompleted(FlowExecution execution) {
        def run = runFor(execution)
        if (run == null) return
        if (!run.parent.fullName.startsWith('teams/')) return

        def auditId = resolveAuditId(run)
        if (!auditId) return

        emitEvent(auditId, [
            event   : 'BUILD_END',
            job     : run.parent.fullName,
            build   : run.number,
            auditId : auditId,
            result  : run.result?.toString() ?: 'UNKNOWN',
            duration: run.duration,
        ])

        flushToArtifact(auditId, run)
        auditStore.remove(auditId)
    }
})

// --- Node handler -----------------------------------------------------------

private void handleNode(String auditId, FlowNode node) {
    def ts    = System.currentTimeMillis()
    def label = resolveLabel(node)

    if (node instanceof StepStartNode) {
        stepStartTimes["${auditId}:${node.id}"] = ts

        def args    = resolveArguments(node)
        def libSrc  = resolveLibrarySource(node)
        emitEvent(auditId, [
            event       : 'STEP_START',
            nodeId      : node.id,
            stepName    : label,
            functionName: node.descriptor?.functionName,
            arguments   : args,
            enclosingId : node.enclosingBlocks*.id,
            workspace   : node.getAction(WorkspaceAction)?.path,
            thread      : node.getAction(ThreadNameAction)?.threadName,
            librarySource: libSrc,
        ])
        return
    }

    if (node instanceof StepEndNode) {
        def startNode   = node.startNode
        def startKey    = "${auditId}:${startNode?.id}"
        def startTime   = stepStartTimes.remove(startKey)
        def duration    = startTime ? (ts - startTime) : null
        def errorAction = node.getAction(ErrorAction)

        emitEvent(auditId, [
            event       : 'STEP_END',
            nodeId      : node.id,
            startNodeId : startNode?.id,
            stepName    : label,
            functionName: startNode?.descriptor?.functionName,
            result      : errorAction ? 'FAILURE' : 'SUCCESS',
            error       : errorAction?.error?.message,
            durationMs  : duration,
        ])
        return
    }

    if (node instanceof FlowEndNode) {
        emitEvent(auditId, [
            event  : 'FLOW_END',
            nodeId : node.id,
            result : node.getAction(ErrorAction) ? 'FAILURE' : 'SUCCESS',
        ])
    }
}

// --- Emit -------------------------------------------------------------------

private void emitEvent(String auditId, Map data) {
    def event = [
        ts     : System.currentTimeMillis(),
        iso    : new Date().format("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", TimeZone.getTimeZone('UTC')),
        auditId: auditId,
    ] + data

    def json = JsonOutput.toJson(event)

    // Stdout is the guaranteed fallback — always write first.
    println("[Audit] ${json}")

    // Stream to audit service for durable flat-file storage and Tetragon correlation.
    postToAuditService(json)

    // Accumulate in memory for audit-log.json artifact (attestation listener requires it).
    auditStore[auditId]?.add(event)
}

private void postToAuditService(String json) {
    try {
        def url  = new URL("${AUDIT_SERVICE_URL}/ingest/event")
        def conn = (HttpURLConnection) url.openConnection()
        conn.setConnectTimeout(500)
        conn.setReadTimeout(500)
        conn.setRequestMethod('POST')
        conn.setDoOutput(true)
        conn.setRequestProperty('Content-Type', 'application/json; charset=UTF-8')
        conn.outputStream.withStream { it.write(json.getBytes('UTF-8')) }
        conn.inputStream.close()
    } catch (ignored) {
        // stdout is the fallback; audit service is best-effort
    }
}

// --- Artifact flush ---------------------------------------------------------

private void flushToArtifact(String auditId, Run run) {
    def events = auditStore[auditId] ?: []

    def libraries = run.getAction(LibrariesAction)?.libraries?.collect { lib ->
        [name: lib.name, sha: lib.version ?: 'unknown']
    } ?: []

    def report = [
        schemaVersion: '1',
        auditId      : auditId,
        job          : run.parent.fullName,
        build        : run.number,
        result       : run.result?.toString() ?: 'UNKNOWN',
        generatedAt  : new Date().format("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", TimeZone.getTimeZone('UTC')),
        libraries    : libraries,
        totalEvents  : events.size(),
        events       : events,
    ]

    def json    = JsonOutput.prettyPrint(JsonOutput.toJson(report))
    def dir     = run.artifactsDir
    dir.mkdirs()
    def file    = new File(dir, 'audit-log.json')
    file.text   = json

    // Register with Jenkins so it appears in the build artifacts UI.
    run.artifactManager.archive(
        run.workspace,
        run.getEnvironment(hudson.model.TaskListener.NULL),
        run.getExecutor()?.owner?.channel,
        ['audit-log.json': 'audit-log.json']
    )

    println("[Audit] Flushed ${events.size()} events to audit-log.json for ${auditId}")
}

// --- Helpers ----------------------------------------------------------------

private String generateAuditId(Run run) {
    def safe = run.parent.fullName.replaceAll('[^a-zA-Z0-9]', '-')
    return "audit-${safe}-${run.number}-${UUID.randomUUID().toString().take(8)}"
}

private String resolveAuditId(Run run) {
    return run.getAction(AuditIdEnvironmentAction)?.auditId
}

private String resolveLabel(FlowNode node) {
    node.getAction(LabelAction)?.displayName ?:
    node.getAction(ThreadNameAction)?.threadName ?:
    node.displayName ?:
    node.id
}

private Map resolveArguments(StepStartNode node) {
    try {
        def args = ArgumentsAction.getStepArgumentsAsString(node)
        return args ? [raw: args] : [:]
    } catch (ignored) {
        return [:]
    }
}

// Detects whether a step was defined in a shared library by inspecting
// the descriptor's defining class loader. Returns library name and step
// class if identifiable, null map if the step is from the Jenkinsfile directly.
private Map resolveLibrarySource(StepStartNode node) {
    try {
        def descriptor = node.descriptor
        if (!descriptor) return null
        def className = descriptor.class.name
        // Library steps are loaded under org.jenkinsci.plugins.workflow.libs
        // or carry the library name in their class loader context.
        if (className.contains('GlobalVariable') || className.contains('LibraryStep')) {
            return [source: 'library', className: className]
        }
        // Check if the step's defining class came from a library classloader
        def cl = descriptor.class.classLoader
        if (cl?.class?.name?.contains('LibraryRecord') ||
            cl?.class?.name?.contains('GroovyClassLoader')) {
            return [source: 'library', classLoader: cl.class.name]
        }
        return [source: 'pipeline']
    } catch (ignored) {
        return null
    }
}

private Run runFor(FlowExecution execution) {
    try {
        def owner = execution.owner
        return owner?.executable instanceof Run ? owner.executable : null
    } catch (ignored) {
        return null
    }
}

// --- Environment action that injects PLATFORM_AUDIT_ID ---------------------

class AuditIdEnvironmentAction implements hudson.model.EnvironmentContributingAction {
    final String auditId

    AuditIdEnvironmentAction(String auditId) {
        this.auditId = auditId
    }

    @Override
    void buildEnvironment(Run run, hudson.EnvVars env) {
        env['PLATFORM_AUDIT_ID'] = auditId
    }

    @Override String getIconFileName()  { null }
    @Override String getDisplayName()  { 'Audit ID' }
    @Override String getUrlName()      { null }
}
