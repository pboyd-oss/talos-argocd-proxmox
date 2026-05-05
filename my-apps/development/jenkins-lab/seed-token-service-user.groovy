import jenkins.model.Jenkins
import hudson.security.HudsonPrivateSecurityRealm
import jenkins.security.ApiTokenProperty

// Idempotent: creates the token-service Jenkins user and seeds its API token
// from JENKINS_TOKEN_SERVICE_TOKEN env var. The same plaintext value lives in
// the token-service-jenkins-creds Secret in the platform namespace, where the
// Token Service reads it as its Basic-auth credential against the Jenkins API.

def tokenPlaintext = System.getenv('JENKINS_TOKEN_SERVICE_TOKEN')
if (!tokenPlaintext) {
    println "[seed-token-service-user] JENKINS_TOKEN_SERVICE_TOKEN not set — skipping"
    return
}

def jenkins  = Jenkins.get()
def realm    = jenkins.securityRealm
def userId   = 'token-service'
def tokenName = 'platform-token-service'

if (!(realm instanceof HudsonPrivateSecurityRealm)) {
    println "[seed-token-service-user] realm ${realm.class.simpleName} is not local — skipping"
    return
}

def user = hudson.model.User.getById(userId, false)
if (!user) {
    // Password is never used — login is via API token only; random value closes the UI login path.
    realm.createAccount(userId, UUID.randomUUID().toString())
    user = hudson.model.User.getById(userId, true)
    println "[seed-token-service-user] created user '${userId}'"
} else {
    println "[seed-token-service-user] user '${userId}' already exists"
}

def apiTokenProp = user.getProperty(ApiTokenProperty)
if (!apiTokenProp) {
    apiTokenProp = new ApiTokenProperty()
    user.addProperty(apiTokenProp)
}

def store = apiTokenProp.tokenStore
if (store.tokenListSortedByName().any { it.name == tokenName }) {
    println "[seed-token-service-user] token '${tokenName}' already exists — skipping"
    return
}

// addFixedNewToken seeds the store with a known plaintext (stores SHA-256 internally).
// This is the same method used by JCasC for apiTokenProperty.tokenStore entries.
store.addFixedNewToken(tokenName, tokenPlaintext)
user.save()
println "[seed-token-service-user] seeded API token '${tokenName}' for '${userId}'"
