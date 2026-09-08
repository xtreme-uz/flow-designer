import { useAuth } from '../contexts/AuthContext';
import './LoginPage.css';

export default function LoginPage() {
  const { login } = useAuth();

  // Set by the backend when the account is not on the allowlist
  const loginDenied = new URLSearchParams(window.location.search).get('error') === 'login_denied';

  return (
    <div className="login-page">
      <div className="login-card">
        <div className="login-header">
          <h1>Flow Designer</h1>
          <p>Visual payment flow editor</p>
        </div>
        <div className="login-body">
          {loginDenied && (
            <p className="login-error" role="alert">
              This account does not have access to Flow Designer. Ask an administrator to add it.
            </p>
          )}
          <button className="login-btn login-btn-gitlab" onClick={login}>
            Sign in with GitLab
          </button>
          <p className="login-hint">You will be redirected to your GitLab instance</p>
        </div>
      </div>
    </div>
  );
}
