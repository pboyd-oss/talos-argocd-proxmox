package main

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/exec"
	"regexp"
	"strings"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/sts"
	stypes "github.com/aws/aws-sdk-go-v2/service/sts/types"
	"github.com/coreos/go-oidc/v3/oidc"
	"gopkg.in/yaml.v3"
)

// TokenRequest is the body of POST /token.
type TokenRequest struct {
	ImageRef    string `json:"image_ref"`
	Environment string `json:"environment"`
	RoleARN     string `json:"role_arn"`
}

// TokenResponse is returned on success.
type TokenResponse struct {
	AccessKeyId     string    `json:"AccessKeyId"`
	SecretAccessKey string    `json:"SecretAccessKey"`
	SessionToken    string    `json:"SessionToken"`
	Expiration      time.Time `json:"Expiration"`
}

// authorizedRole is one entry from the platform ConfigMap.
type authorizedRole struct {
	RoleARN       string `yaml:"role_arn"`
	AccountID     string `yaml:"account_id"`
	SessionPolicy string `yaml:"session_policy"`
}

// teamConfig maps environment name → authorizedRole.
type teamConfig struct {
	Environments map[string]authorizedRole `yaml:"environments"`
}

var teamSlugRe = regexp.MustCompile(`^[a-z0-9][a-z0-9-]*$`)

var (
	jenkinsBaseURL      = envOrDefault("JENKINS_URL", "http://jenkins-operator-http-jenkins.jenkins.svc.cluster.local:8080")
	jenkinsAPIUser      = envOrDefault("JENKINS_API_USER", "token-service")
	jenkinsAPIToken     = os.Getenv("JENKINS_API_TOKEN")
	cosignPublicKeyPath = envOrDefault("COSIGN_PUBLIC_KEY_PATH", "/cosign/cosign.pub")
	rolesConfigDir      = envOrDefault("ROLES_CONFIG_DIR", "/roles")
	listenAddr          = envOrDefault("LISTEN_ADDR", ":8080")
	k8sAPIURL           = envOrDefault("K8S_API_URL", "https://kubernetes.default.svc")
	k8sSATokenPath      = envOrDefault("K8S_SA_TOKEN_PATH", "/var/run/secrets/kubernetes.io/serviceaccount/token")
	k8sCACertPath       = envOrDefault("K8S_CA_CERT_PATH", "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt")
	k8sDeployerSub      = envOrDefault("K8S_DEPLOYER_SA", "system:serviceaccount:jenkins:platform-deployer")
)

func main() {
	http.HandleFunc("/token", handleToken)
	http.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusOK) })
	log.Printf("token-service listening on %s", listenAddr)
	log.Fatal(http.ListenAndServe(listenAddr, nil))
}

func handleToken(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var req TokenRequest
	body, err := io.ReadAll(io.LimitReader(r.Body, 4096))
	if err != nil || json.Unmarshal(body, &req) != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}
	if req.ImageRef == "" || req.Environment == "" || req.RoleARN == "" {
		http.Error(w, "image_ref, environment, and role_arn are required", http.StatusBadRequest)
		return
	}

	// Step 1: Verify Jenkins OIDC JWT
	authHeader := r.Header.Get("Authorization")
	if !strings.HasPrefix(authHeader, "Bearer ") {
		http.Error(w, "missing Bearer token", http.StatusUnauthorized)
		return
	}
	rawToken := strings.TrimPrefix(authHeader, "Bearer ")

	claims, err := verifyJWT(rawToken)
	if err != nil {
		log.Printf("JWT verification failed: %v", err)
		http.Error(w, "invalid JWT", http.StatusUnauthorized)
		return
	}

	// Step 1b: Verify the Kubernetes projected SA token from the deployer pod.
	// This is a second independent identity proof: the request must come from a pod
	// running as platform-deployer SA on this cluster, not just from anyone with a JWT.
	k8sToken := r.Header.Get("X-K8s-Token")
	if k8sToken == "" {
		http.Error(w, "missing X-K8s-Token header", http.StatusUnauthorized)
		return
	}
	if err := verifyK8sDeployerToken(r.Context(), k8sToken); err != nil {
		log.Printf("K8s SA token verification failed: %v", err)
		http.Error(w, "invalid deployer identity", http.StatusForbidden)
		return
	}

	// Step 2: sub must be platform/{team}/release
	sub, _ := claims["sub"].(string)
	parts := strings.Split(sub, "/")
	if len(parts) != 3 || parts[0] != "platform" || parts[2] != "release" {
		log.Printf("JWT sub rejected: %q", sub)
		http.Error(w, "JWT sub must be platform/{team}/release", http.StatusForbidden)
		return
	}
	teamSlug := parts[1]

	// Step 2b: Verify the release build is currently in progress via Jenkins API.
	// Prevents JWT replay: a token from a finished build will fail this check.
	buildNumber, _ := claims["build_number"].(string)
	if buildNumber == "" {
		http.Error(w, "JWT missing build_number claim", http.StatusForbidden)
		return
	}
	if err := verifyBuildInProgress(r.Context(), teamSlug, buildNumber); err != nil {
		log.Printf("Jenkins build-in-progress check failed: %v", err)
		http.Error(w, "release build not in progress", http.StatusForbidden)
		return
	}

	// Step 3a: Verify the image being deployed belongs to this team.
	// Image refs must be harbor.tuxgrid.com/{team}/... — prevents team-a from
	// deploying team-b's image using team-a's release pipeline.
	if err := validateImageOwnership(req.ImageRef, teamSlug); err != nil {
		log.Printf("image ownership check failed: %v", err)
		http.Error(w, "image does not belong to requesting team", http.StatusForbidden)
		return
	}

	// Step 3b: Check role_arn is authorized for this team + environment.
	// Returns the full role config including the team-defined session policy.
	role, err := authorizeRole(teamSlug, req.Environment, req.RoleARN)
	if err != nil {
		log.Printf("role authorization failed for team=%s env=%s: %v", teamSlug, req.Environment, err)
		http.Error(w, "role not authorized for this team/environment", http.StatusForbidden)
		return
	}

	// Step 4: Verify scan/v1 attestation on the image — also checks predicate.job
	// is within teams/{teamSlug}/ to prevent using another team's scan attestation.
	if err := verifyScanAttestation(req.ImageRef, teamSlug); err != nil {
		log.Printf("scan attestation verification failed for %s: %v", req.ImageRef, err)
		http.Error(w, "scan/v1 attestation missing or invalid", http.StatusForbidden)
		return
	}

	// Step 5: AssumeRole via STS with session tags, source identity, and session policy.
	// The session policy is defined by the team in the platform ConfigMap and reviewed
	// by the platform team — it further restricts what the session can do beyond the
	// role's own policies. If omitted, the role's permissions apply in full.
	creds, err := assumeRole(r.Context(), req.RoleARN, teamSlug, req.ImageRef, req.Environment, buildNumber, role.SessionPolicy)
	if err != nil {
		log.Printf("sts:AssumeRole failed for %s: %v", req.RoleARN, err)
		http.Error(w, "failed to assume role", http.StatusInternalServerError)
		return
	}

	log.Printf("issued credentials: team=%s env=%s role=%s image=%s", teamSlug, req.Environment, req.RoleARN, req.ImageRef)
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(creds)
}

// verifyJWT validates the token against Jenkins' OIDC provider via discovery.
// go-oidc fetches /.well-known/openid-configuration, resolves the JWKS URI,
// and verifies signature + audience in one call.
func verifyJWT(rawToken string) (map[string]interface{}, error) {
	ctx := context.Background()
	provider, err := oidc.NewProvider(ctx, jenkinsBaseURL)
	if err != nil {
		return nil, fmt.Errorf("OIDC discovery failed: %w", err)
	}
	verifier := provider.Verifier(&oidc.Config{ClientID: "platform-token-service"})
	idToken, err := verifier.Verify(ctx, rawToken)
	if err != nil {
		return nil, fmt.Errorf("token verification failed: %w", err)
	}
	var claims map[string]interface{}
	if err := idToken.Claims(&claims); err != nil {
		return nil, fmt.Errorf("extracting claims: %w", err)
	}
	return claims, nil
}

// validateImageOwnership ensures the image ref is under harbor.tuxgrid.com/{teamSlug}/.
// Prevents a team's release pipeline from deploying another team's image.
func validateImageOwnership(imageRef, teamSlug string) error {
	prefix := fmt.Sprintf("harbor.tuxgrid.com/%s/", teamSlug)
	if !strings.HasPrefix(imageRef, prefix) {
		return fmt.Errorf("image %q does not start with %q", imageRef, prefix)
	}
	return nil
}

// authorizeRole checks the platform ConfigMap that this team/environment pair is allowed
// to use roleARN, and returns the full role config including the session policy.
func authorizeRole(teamSlug, environment, roleARN string) (authorizedRole, error) {
	if !teamSlugRe.MatchString(teamSlug) {
		return authorizedRole{}, fmt.Errorf("invalid team slug %q", teamSlug)
	}
	path := fmt.Sprintf("%s/%s.yaml", rolesConfigDir, teamSlug)
	data, err := os.ReadFile(path)
	if err != nil {
		return authorizedRole{}, fmt.Errorf("no config for team %q: %w", teamSlug, err)
	}
	var cfg teamConfig
	if err := yaml.Unmarshal(data, &cfg); err != nil {
		return authorizedRole{}, fmt.Errorf("parsing team config: %w", err)
	}
	role, ok := cfg.Environments[environment]
	if !ok {
		return authorizedRole{}, fmt.Errorf("environment %q not configured for team %q", environment, teamSlug)
	}
	if role.RoleARN != roleARN {
		return authorizedRole{}, fmt.Errorf("role_arn mismatch: got %q, authorized %q", roleARN, role.RoleARN)
	}
	if strings.TrimSpace(role.SessionPolicy) == "" {
		return authorizedRole{}, fmt.Errorf("no session_policy defined for team %q environment %q — deployment refused", teamSlug, environment)
	}
	return role, nil
}

// verifyScanAttestation calls cosign to verify the scan/v1 predicate, checks pass flags,
// and verifies the predicate's job field belongs to teamSlug's folder.
// The job cross-reference prevents using a scan attestation from another team's build.
func verifyScanAttestation(imageRef, teamSlug string) error {
	cmd := exec.Command("cosign", "verify-attestation",
		"--key", cosignPublicKeyPath,
		"--type", "https://tuxgrid.com/attestation/scan/v1",
		"--output", "json",
		imageRef,
	)
	out, err := cmd.Output()
	if err != nil {
		return fmt.Errorf("cosign verify-attestation: %w", err)
	}

	// cosign outputs one JSON object per line; take the first
	line := strings.SplitN(strings.TrimSpace(string(out)), "\n", 2)[0]
	var envelope struct {
		Payload string `json:"payload"`
	}
	if err := json.Unmarshal([]byte(line), &envelope); err != nil {
		return fmt.Errorf("parsing cosign envelope: %w", err)
	}

	decoded, err := base64.StdEncoding.DecodeString(envelope.Payload)
	if err != nil {
		return fmt.Errorf("decoding payload: %w", err)
	}

	var statement struct {
		Predicate struct {
			Job     string `json:"job"`
			Trivy   struct{ Passed bool `json:"passed"` } `json:"trivy"`
			Checkov struct{ Passed bool `json:"passed"` } `json:"checkov"`
		} `json:"predicate"`
	}
	if err := json.Unmarshal(decoded, &statement); err != nil {
		return fmt.Errorf("parsing predicate: %w", err)
	}

	expectedJobPrefix := fmt.Sprintf("teams/%s/", teamSlug)
	if !strings.HasPrefix(statement.Predicate.Job, expectedJobPrefix) {
		return fmt.Errorf("scan predicate job %q does not belong to team %q", statement.Predicate.Job, teamSlug)
	}
	if !statement.Predicate.Trivy.Passed {
		return fmt.Errorf("scan/v1 predicate: trivy.passed is false")
	}
	if !statement.Predicate.Checkov.Passed {
		return fmt.Errorf("scan/v1 predicate: checkov.passed is false")
	}
	return nil
}

// verifyBuildInProgress calls the Jenkins build API to confirm the release build is
// currently running. Prevents JWT replay: tokens from finished builds are rejected.
func verifyBuildInProgress(ctx context.Context, teamSlug, buildNumber string) error {
	apiURL := fmt.Sprintf("%s/job/platform/%s/release/%s/api/json",
		jenkinsBaseURL, teamSlug, buildNumber)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, apiURL, nil)
	if err != nil {
		return fmt.Errorf("building jenkins request: %w", err)
	}
	if jenkinsAPIToken != "" {
		req.SetBasicAuth(jenkinsAPIUser, jenkinsAPIToken)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return fmt.Errorf("jenkins build API: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotFound {
		return fmt.Errorf("build platform/%s/release/%s not found in Jenkins", teamSlug, buildNumber)
	}
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("jenkins API returned %d for build platform/%s/release/%s", resp.StatusCode, teamSlug, buildNumber)
	}
	var info struct {
		Building bool `json:"building"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 4096)).Decode(&info); err != nil {
		return fmt.Errorf("decoding jenkins response: %w", err)
	}
	if !info.Building {
		return fmt.Errorf("build platform/%s/release/%s is not currently in progress — possible JWT replay", teamSlug, buildNumber)
	}
	return nil
}

// verifyK8sDeployerToken calls the Kubernetes TokenReview API to confirm the token belongs
// to the platform-deployer ServiceAccount. Uses the cluster CA and the Token Service's own
// SA token to authenticate to the K8s API. This proves the request came from a pod on
// this cluster running as platform-deployer — something a stolen Jenkins JWT alone cannot prove.
func verifyK8sDeployerToken(ctx context.Context, token string) error {
	saToken, err := os.ReadFile(k8sSATokenPath)
	if err != nil {
		return fmt.Errorf("reading service account token: %w", err)
	}
	caCert, err := os.ReadFile(k8sCACertPath)
	if err != nil {
		return fmt.Errorf("reading cluster CA: %w", err)
	}
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(caCert) {
		return fmt.Errorf("parsing cluster CA cert")
	}
	k8sClient := &http.Client{
		Transport: &http.Transport{
			TLSClientConfig: &tls.Config{RootCAs: pool},
		},
	}

	body, _ := json.Marshal(map[string]interface{}{
		"apiVersion": "authentication.k8s.io/v1",
		"kind":       "TokenReview",
		"spec": map[string]interface{}{
			"token":     token,
			"audiences": []string{"platform-token-service"},
		},
	})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		k8sAPIURL+"/apis/authentication.k8s.io/v1/tokenreviews",
		bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("building token review request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+strings.TrimSpace(string(saToken)))

	resp, err := k8sClient.Do(req)
	if err != nil {
		return fmt.Errorf("token review API: %w", err)
	}
	defer resp.Body.Close()

	var review struct {
		Status struct {
			Authenticated bool   `json:"authenticated"`
			Error         string `json:"error"`
			User          struct {
				Username string `json:"username"`
			} `json:"user"`
		} `json:"status"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 4096)).Decode(&review); err != nil {
		return fmt.Errorf("decoding token review response: %w", err)
	}
	if !review.Status.Authenticated {
		return fmt.Errorf("K8s SA token not authenticated: %s", review.Status.Error)
	}
	if review.Status.User.Username != k8sDeployerSub {
		return fmt.Errorf("K8s SA subject %q, expected %q", review.Status.User.Username, k8sDeployerSub)
	}
	return nil
}

// assumeRole calls sts:AssumeRole with a 15-minute TTL, session tags, a SourceIdentity
// encoding the exact pipeline + build, and a session policy supplied by the team.
//
// The session policy is mandatory — authorizeRole refuses to return a role without one.
// Effective permissions are the intersection of the role's policies and the session policy,
// so the session can never exceed what the team explicitly declared in the ConfigMap PR.
func assumeRole(ctx context.Context, roleARN, teamSlug, imageRef, environment, buildNumber, sessionPolicy string) (*TokenResponse, error) {
	cfg, err := config.LoadDefaultConfig(ctx)
	if err != nil {
		return nil, fmt.Errorf("loading AWS config: %w", err)
	}

	// Truncate imageRef to stay within STS tag value limit (256 chars)
	imageTag := imageRef
	if len(imageTag) > 256 {
		imageTag = imageTag[:256]
	}

	input := &sts.AssumeRoleInput{
		RoleArn:         aws.String(roleARN),
		RoleSessionName: aws.String("platform-release"),
		SourceIdentity:  aws.String(fmt.Sprintf("platform/%s/release/%s", teamSlug, buildNumber)),
		DurationSeconds: aws.Int32(900), // 15 minutes
		Tags: []stypes.Tag{
			{Key: aws.String("team"),        Value: aws.String(teamSlug)},
			{Key: aws.String("environment"), Value: aws.String(environment)},
			{Key: aws.String("image"),       Value: aws.String(imageTag)},
			{Key: aws.String("build"),       Value: aws.String(buildNumber)},
		},
	}
	if sessionPolicy != "" {
		input.Policy = aws.String(sessionPolicy)
	}

	client := sts.NewFromConfig(cfg)
	result, err := client.AssumeRole(ctx, input)
	if err != nil {
		return nil, err
	}

	return &TokenResponse{
		AccessKeyId:     aws.ToString(result.Credentials.AccessKeyId),
		SecretAccessKey: aws.ToString(result.Credentials.SecretAccessKey),
		SessionToken:    aws.ToString(result.Credentials.SessionToken),
		Expiration:      aws.ToTime(result.Credentials.Expiration),
	}, nil
}

func envOrDefault(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
