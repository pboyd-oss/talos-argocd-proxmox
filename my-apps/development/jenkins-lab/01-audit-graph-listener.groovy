import groovy.json.JsonOutput
import hudson.model.ParametersAction
import hudson.model.Run
import hudson.model.StringParameterValue
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.flow.FlowExecution
import org.jenkinsci.plugins.workflow.flow.FlowExecutionListener
import org.jenkinsci.plugins.workflow.flow.GraphListener
import org.jenkinsci.plugins.workflow.graph.FlowEndNode
import org.jenkinsci.plugins.workflow.graph.FlowNode
import org.jenkinsci.plugins.workflow.cps.nodes.StepStartNode
import org.jenkinsci.plugins.workflow.cps.nodes.StepEndNode
import org.jenkinsci.plugins.workflow.cps.nodes.StepAtomNode
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

// GitHub org owning library repos — used to construct vars/ source URLs in audit events.
@groovy.transform.Field
final String LIBRARY_GITHUB_ORG = System.getProperty('platform.library.github.org', 'pboyd-oss')

// In-memory store keyed by auditId → list of audit events.
// Retained for audit-log.json artifact (required by attestation listener).
// Events are also streamed to the audit service in real time.
@groovy.transform.Field
final Map<String, List<Map>> auditStore = [:].asSynchronized()

// Library step registry: auditId:nodeId → {library, version, stepName}.
// Populated when a step is identified as a library GlobalVariable or src/ step.
// Used by resolveCalledFrom to attribute inner steps to their library caller.
@groovy.transform.Field
final Map<String, Map> libraryNodeMap = [:].asSynchronized()

// Idempotent registration — the Operator runs this via Script Console on every
// reconcile cycle in addition to init.groovy.d at startup. Guard against
// duplicates using a toString() marker; also fixes FlowExecutionListener.all()
// which is not resolvable in the Script Console's Groovy classloader context.
final String AUDIT_LISTENER_MARKER = 'PlatformAuditGraphListener-v1'
def _flowListeners = Jenkins.instance.getExtensionList(FlowExecutionListener.class)
// Remove then re-add so the Operator always runs the current version of this script.
// ExtensionList does not support removeIf — collect first, then remove individually.
_flowListeners.findAll { it.toString() == AUDIT_LISTENER_MARKER }.each { _flowListeners.remove(it) }
_flowListeners.add(new FlowExecutionListener() {

    String toString() { AUDIT_LISTENER_MARKER }

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

        // Inject PLATFORM_AUDIT_ID into build env via ParametersAction.
        // ParametersAction is a core Jenkins class (XStream-safe) and its values
        // are automatically exported as environment variables for all build steps.
        // AuditIdEnvironmentAction was removed — Groovy-script-defined Action
        // subclasses are rejected by Jenkins's XStream class filter on save().
        try {
            def auditParam = new StringParameterValue('PLATFORM_AUDIT_ID', auditId)
            def existing = run.getAction(ParametersAction)
            def params = existing
                ? existing.parameters.findAll { it.name != 'PLATFORM_AUDIT_ID' } + [auditParam]
                : [auditParam]
            run.replaceAction(new ParametersAction(params))
        } catch (e) {
            println("[Audit] Failed to inject PLATFORM_AUDIT_ID: ${e.message}")
        }

        execution.addListener(new GraphListener.Synchronous() {
            @Override
            void onNewHead(FlowNode node) {
                handleNode(auditId, run, node)
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
        libraryNodeMap.keySet().removeIf { it.startsWith("${auditId}:") }
    }
})

// --- Node handler -----------------------------------------------------------

private void handleNode(String auditId, WorkflowRun run, FlowNode node) {
    def label = resolveLabel(node)

    if (node instanceof StepAtomNode) {
        def args      = resolveArguments(node)
        def libSrc    = resolveLibrarySource(node, run)
        def calledFrom = resolveCalledFrom(node, auditId)

        emitEvent(auditId, [
            event        : 'STEP_ATOM',
            nodeId       : node.id,
            stepName     : label,
            functionName : node.descriptor?.functionName,
            arguments    : args,
            enclosingIds : node.enclosingBlocks*.id,
            workspace    : node.getAction(WorkspaceAction)?.path,
            thread       : node.getAction(ThreadNameAction)?.threadName,
            librarySource: libSrc,
            calledFrom   : calledFrom,
        ])
        return
    }

    if (node instanceof StepStartNode) {
        def args      = resolveArguments(node)
        def libSrc    = resolveLibrarySource(node, run)
        def calledFrom = resolveCalledFrom(node, auditId)

        // Register this node so inner steps can attribute themselves to it.
        if (libSrc?.source == 'library') {
            libraryNodeMap["${auditId}:${node.id}"] = [
                library : libSrc.library,
                version : libSrc.version ?: 'unknown',
                stepName: label,
            ]
        }

        emitEvent(auditId, [
            event        : 'STEP_START',
            nodeId       : node.id,
            stepName     : label,
            functionName : node.descriptor?.functionName,
            arguments    : args,
            enclosingIds : node.enclosingBlocks*.id,
            workspace    : node.getAction(WorkspaceAction)?.path,
            thread       : node.getAction(ThreadNameAction)?.threadName,
            librarySource: libSrc,
            calledFrom   : calledFrom,
        ])
        return
    }

    if (node instanceof StepEndNode) {
        def startNode   = node.startNode
        def startMs     = startNode ? TimingAction.getStartTime(startNode) : 0L
        def endMs       = TimingAction.getStartTime(node)
        def duration    = (startNode && startMs > 0L && endMs >= startMs) ? (endMs - startMs) : null
        def errorAction = node.getAction(ErrorAction)

        emitEvent(auditId, [
            event        : 'STEP_END',
            nodeId       : node.id,
            startNodeId  : startNode?.id,
            stepName     : label,
            functionName : startNode?.descriptor?.functionName,
            result       : errorAction ? 'FAILURE' : 'SUCCESS',
            error        : errorAction?.error?.message,
            durationMs   : duration,
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
        def secret = System.getenv('AUDIT_INGEST_SECRET') ?: ''
        if (secret) conn.setRequestProperty('Authorization', "Bearer ${secret}")
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

    def json = JsonOutput.prettyPrint(JsonOutput.toJson(report))
    try {
        def dir = run.artifactsDir
        dir.mkdirs()
        new File(dir, 'audit-log.json').text = json
        println("[Audit] Flushed ${events.size()} events to audit-log.json for ${auditId}")
    } catch (e) {
        println("[Audit] Failed to flush audit-log.json: ${e.message}")
    }
}

// --- Helpers ----------------------------------------------------------------

private String generateAuditId(Run run) {
    def safe = run.parent.fullName.replaceAll('[^a-zA-Z0-9]', '-')
    return "audit-${safe}-${run.number}-${UUID.randomUUID().toString().take(8)}"
}

private String resolveAuditId(Run run) {
    return run.getAction(ParametersAction)?.getParameter('PLATFORM_AUDIT_ID')?.value
}

private String resolveLabel(FlowNode node) {
    node.getAction(LabelAction)?.displayName ?:
    node.getAction(ThreadNameAction)?.threadName ?:
    node.displayName ?:
    node.id
}

private Map resolveArguments(FlowNode node) {
    try {
        def args = ArgumentsAction.getStepArgumentsAsString(node)
        return args ? [raw: args] : [:]
    } catch (ignored) {
        return [:]
    }
}

// Resolves which shared library a step came from by walking the class loader
// chain and matching against the libraries declared in LibrariesAction.
// Returns {source:'library', library:<name>, version:<sha>} or {source:'pipeline'}.
private Map resolveLibrarySource(FlowNode node, WorkflowRun run) {
    try {
        def descriptor = node.descriptor
        if (!descriptor) return [source: 'pipeline']

        def libAction = run?.getAction(LibrariesAction)
        def libs = libAction?.libraries ?: []

        // Walk the class loader chain looking for a GroovyClassLoader.
        // Jenkins compiles each library into its own GroovyClassLoader whose
        // toString() contains the library name.
        def cl = descriptor.class.classLoader
        while (cl != null) {
            if (cl.class.name.contains('GroovyClassLoader')) {
                def clStr = cl.toString()
                for (def lib : libs) {
                    if (clStr.contains(lib.name)) {
                        def entry = [source: 'library', library: lib.name, version: lib.version ?: 'unknown']
                        def fname = descriptor.functionName
                        def ver   = lib.version ?: ''
                        if (fname && ver && ver != 'unknown') {
                            entry.sourceUrl = "https://github.com/${LIBRARY_GITHUB_ORG}/${lib.name}/blob/${ver}/vars/${fname}.groovy"
                        }
                        return entry
                    }
                }
                // GroovyClassLoader present but library name not in its toString —
                // still mark as library so calledFrom attribution works downstream.
                return [source: 'library', library: 'unknown']
            }
            try { cl = cl.parent } catch (ignored) { break }
        }

        // Fallback: detect GlobalVariable pattern by class name.
        // Library vars/ steps compiled as GlobalVariable subclasses carry the
        // library name in their enclosing class hierarchy.
        def className = descriptor.class.name
        if (className.contains('GlobalVariable') || className.contains('LibraryStep')) {
            def fn = descriptor.functionName?.toLowerCase()
            if (fn) {
                for (def lib : libs) {
                    // e.g. functionName 'tuxgridBuild' declared in 'jenkins-library'
                    def libSlug = lib.name.toLowerCase().replaceAll('[^a-z0-9]', '')
                    if (fn.contains(libSlug) || libSlug.contains(fn)) {
                        def entry = [source: 'library', library: lib.name, version: lib.version ?: 'unknown', via: 'GlobalVariable']
                        def ver   = lib.version ?: ''
                        if (ver && ver != 'unknown') {
                            entry.sourceUrl = "https://github.com/${LIBRARY_GITHUB_ORG}/${lib.name}/blob/${ver}/vars/${descriptor.functionName}.groovy"
                        }
                        return entry
                    }
                }
            }
            return [source: 'library', library: 'unknown', via: 'GlobalVariable']
        }

        return [source: 'pipeline']
    } catch (ignored) {
        return [source: 'pipeline']
    }
}

// Walks the enclosing blocks of a node to find the nearest library step ancestor.
// Returns {library, version, stepName} of the innermost library step that
// encloses this node, or null if the step is not inside any library step.
private Map resolveCalledFrom(FlowNode node, String auditId) {
    try {
        for (def enc : node.enclosingBlocks) {
            def entry = libraryNodeMap["${auditId}:${enc.id}"]
            if (entry) return entry
        }
    } catch (ignored) {}
    return null
}

private Run runFor(FlowExecution execution) {
    try {
        def owner = execution.owner
        return owner?.executable instanceof Run ? owner.executable : null
    } catch (ignored) {
        return null
    }
}
