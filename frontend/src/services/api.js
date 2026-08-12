/**
 * API Service Layer for Flow Designer
 * Handles all communication with Spring Boot backend
 *
 * All flow data is in THUB deployment format (ThubDeploymentData).
 * Auth is handled via session cookie (GitLab OAuth2). No userId parameter needed.
 */

const API_BASE = '/api';

class ApiError extends Error {
  constructor(message, errors = []) {
    super(message);
    this.name = 'ApiError';
    this.errors = errors;
  }
}

/**
 * Helper to handle API responses
 */
async function handleResponse(response) {
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    const message = body.detail || body.message || `HTTP ${response.status}: ${response.statusText}`;
    const errors = Array.isArray(body.errors) ? body.errors : [];
    throw new ApiError(message, errors);
  }

  if (response.status === 204) {
    return null;
  }

  return response.json();
}

function getCsrfToken() {
  const match = document.cookie.match(/(^|; )XSRF-TOKEN=([^;]+)/);
  return match ? decodeURIComponent(match[2]) : null;
}

function getReadHeaders(branch = 'main') {
  return { 'X-Branch': branch };
}

function getMutationHeaders(branch = 'main') {
  const h = { 'Content-Type': 'application/json', 'X-Branch': branch };
  const csrf = getCsrfToken();
  if (csrf) h['X-XSRF-TOKEN'] = csrf;
  return h;
}

// ============================================================================
// CONFIG
// ============================================================================

export async function getConfig() {
  const response = await fetch(`${API_BASE}/config`, { credentials: 'include' });
  return handleResponse(response);
}

// ============================================================================
// WORKSPACE OPERATIONS
// ============================================================================

export async function createWorkspace(branch) {
  const response = await fetch(`${API_BASE}/workspaces`, {
    method: 'POST',
    credentials: 'include',
    headers: getMutationHeaders(branch),
    body: JSON.stringify({ branchName: branch })
  });
  return handleResponse(response);
}

export async function listWorkspaces() {
  const response = await fetch(`${API_BASE}/workspaces`, {
    credentials: 'include',
    headers: getReadHeaders()
  });
  return handleResponse(response);
}

export async function getCurrentWorkspace(branch) {
  const response = await fetch(`${API_BASE}/workspaces/current`, {
    credentials: 'include',
    headers: getReadHeaders(branch)
  });
  return handleResponse(response);
}

export async function deleteWorkspace(branch) {
  const response = await fetch(`${API_BASE}/workspaces`, {
    method: 'DELETE',
    credentials: 'include',
    headers: getMutationHeaders(branch)
  });
  return handleResponse(response);
}

export async function getWorkspaceStatus(branch) {
  const response = await fetch(`${API_BASE}/workspaces/status`, {
    credentials: 'include',
    headers: getReadHeaders(branch)
  });
  return handleResponse(response);
}

// ============================================================================
// BRANCH OPERATIONS
// ============================================================================

export async function listBranches() {
  const response = await fetch(`${API_BASE}/branches`, { credentials: 'include' });
  return handleResponse(response);
}

// ============================================================================
// MAIN BRANCH OPERATIONS (Read-only)
// ============================================================================

export async function listFlowsFromMain() {
  const response = await fetch(`${API_BASE}/flows`, { credentials: 'include' });
  return handleResponse(response);
}

export async function getFlowFromMain(flowName) {
  const response = await fetch(`${API_BASE}/flows/${flowName}`, { credentials: 'include' });
  return handleResponse(response);
}

export async function validateFlow(deploymentData) {
  const response = await fetch(`${API_BASE}/flows/validate`, {
    method: 'POST',
    credentials: 'include',
    headers: getMutationHeaders(),
    body: JSON.stringify(deploymentData)
  });
  return handleResponse(response);
}

// ============================================================================
// STATUS OPERATIONS
// ============================================================================

export async function listStatusesFromMain() {
  const response = await fetch(`${API_BASE}/statuses`, { credentials: 'include' });
  return handleResponse(response);
}

export async function listWorkspaceStatuses(branch) {
  const response = await fetch(`${API_BASE}/workspaces/statuses`, {
    credentials: 'include',
    headers: getReadHeaders(branch)
  });
  return handleResponse(response);
}

// ============================================================================
// WORKSPACE FLOW OPERATIONS
// ============================================================================

export async function listWorkspaceFlows(branch) {
  const response = await fetch(`${API_BASE}/workspaces/flows`, {
    credentials: 'include',
    headers: getReadHeaders(branch)
  });
  return handleResponse(response);
}

export async function getWorkspaceFlow(flowName, branch) {
  const response = await fetch(`${API_BASE}/workspaces/flows/${flowName}`, {
    credentials: 'include',
    headers: getReadHeaders(branch)
  });
  return handleResponse(response);
}

export async function createFlow(flowTypeId, deploymentData, branch) {
  const response = await fetch(`${API_BASE}/workspaces/flows`, {
    method: 'POST',
    credentials: 'include',
    headers: getMutationHeaders(branch),
    body: JSON.stringify({ flowTypeId, deploymentData })
  });
  return handleResponse(response);
}

export async function updateFlow(flowName, deploymentData, branch) {
  const response = await fetch(`${API_BASE}/workspaces/flows/${flowName}`, {
    method: 'PUT',
    credentials: 'include',
    headers: getMutationHeaders(branch),
    body: JSON.stringify({ deploymentData })
  });
  return handleResponse(response);
}

export async function deleteFlow(flowName, branch) {
  const response = await fetch(`${API_BASE}/workspaces/flows/${flowName}`, {
    method: 'DELETE',
    credentials: 'include',
    headers: getMutationHeaders(branch)
  });
  return handleResponse(response);
}

export async function renameFlow(oldName, newName, branch) {
  const response = await fetch(`${API_BASE}/workspaces/flows/${oldName}/rename`, {
    method: 'POST',
    credentials: 'include',
    headers: getMutationHeaders(branch),
    body: JSON.stringify({ newName })
  });
  return handleResponse(response);
}

// ============================================================================
// GIT OPERATIONS
// ============================================================================

export async function commitChanges(message, branch) {
  const response = await fetch(`${API_BASE}/workspaces/commit`, {
    method: 'POST',
    credentials: 'include',
    headers: getMutationHeaders(branch),
    body: JSON.stringify({ message })
  });
  return handleResponse(response);
}

export async function pushToRemote(branch) {
  const response = await fetch(`${API_BASE}/workspaces/push`, {
    method: 'POST',
    credentials: 'include',
    headers: getMutationHeaders(branch)
  });
  return handleResponse(response);
}

export async function pullFromRemote(branch) {
  const response = await fetch(`${API_BASE}/workspaces/pull`, {
    method: 'POST',
    credentials: 'include',
    headers: getMutationHeaders(branch)
  });
  return handleResponse(response);
}

export async function createBranch(newBranch, currentBranch) {
  const response = await fetch(`${API_BASE}/workspaces/branch`, {
    method: 'POST',
    credentials: 'include',
    headers: getMutationHeaders(currentBranch),
    body: JSON.stringify({ branchName: newBranch })
  });
  return handleResponse(response);
}

export { ApiError };
